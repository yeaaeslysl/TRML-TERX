package com.termi.app;

import android.content.Context;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * rootfs 解压器：按"多把锁多把钥匙"原则，自动识别压缩格式并选用对应解码器。
 *
 * <h3>支持的格式</h3>
 * <ul>
 *   <li>{@code .tar.gz} / {@code .tgz} —— 用 {@link GZIPInputStream}（JDK 自带）</li>
 *   <li>{@code .tar.xz} —— 用 {@link XZCompressorInputStream}（org.tukaani:xz）</li>
 *   <li>{@code .tar} —— 裸 tar，不压缩</li>
 * </ul>
 *
 * <h3>为什么不用系统 tar</h3>
 * 设备无 {@code xz} 二进制，toybox tar 调不到外部 xz。
 * 且若依赖系统二进制，会陷入"要先有 proot 才能解压 proot"的死循环。
 * 故全部用纯 Java 实现。
 *
 * <h3>安全</h3>
 * 拒绝包含 {@code ..} 或绝对路径的条目（防 zip-slip / tar 逃逸）。
 */
public final class RootfsExtractor {

    private static final String TAG = "RootfsExtractor";
    /** 缓冲区：256KB（阶段 1 优化，原 64KB）。App 私有目录为本地文件系统，大缓冲收益明显。 */
    private static final int BUF = 256 * 1024;

    /** 解压进度回调。 */
    public interface Progress {
        /**
         * @param bytes   已处理字节
         * @param total   总字节（未知时为 0）
         * @param entries 已解出条目数
         */
        void onProgress(long bytes, long total, int entries);
    }

    /** 解压结果。 */
    public static final class Result {
        public boolean ok;
        public int entries;
        public long bytes;
        public String error = "";
        public final List<String> warnings = new ArrayList<>();

        public String summary() {
            if (ok) {
                return "解压完成：" + entries + " 个条目，"
                        + (bytes / 1024 / 1024) + " MB"
                        + (warnings.isEmpty() ? "" : "（" + warnings.size() + " 条警告）");
            }
            return "解压失败：" + error;
        }
    }

    private RootfsExtractor() {}

    /** 从文件名推断压缩类型。 */
    public enum Compression {
        GZIP, XZ, NONE;

        public static Compression of(String name) {
            String n = name == null ? "" : name.toLowerCase();
            if (n.endsWith(".tar.gz") || n.endsWith(".tgz")) return GZIP;
            if (n.endsWith(".tar.xz") || n.endsWith(".txz")) return XZ;
            return NONE;
        }
    }

    /**
     * 解压 rootfs 到目标目录。
     *
     * @param in         包输入流（由调用方负责关闭）
     * @param fileName   包文件名（用于推断压缩格式）
     * @param destDir    目标目录（会被清空后重建）
     * @param progress   进度回调，可为 null
     */
    public static Result extract(InputStream in, String fileName,
                                 File destDir, Progress progress) {
        Result r = new Result();
        Compression c = Compression.of(fileName);
        LogStore.getInstance().info(TAG, "开始解压 " + fileName
                + " → " + destDir + " 格式=" + c);

        try {
            // 清空目标目录（重装/换发行版场景）
            if (destDir.exists()) {
                String old = readDistroHint(destDir);
                LogStore.getInstance().info(TAG, "检测到已有环境"
                        + (old.isEmpty() ? "" : "（" + old + "）") + "，清理中…");
                deleteRecursively(destDir);
                if (destDir.exists()) {
                    LogStore.getInstance().warn(TAG, "旧环境清理不彻底，继续解压");
                } else {
                    LogStore.getInstance().info(TAG, "旧环境已清理");
                }
            }
            if (!destDir.mkdirs() && !destDir.isDirectory()) {
                r.error = "无法创建目标目录: " + destDir;
                return r;
            }

            InputStream raw = new BufferedInputStream(in, BUF);
            InputStream decompressed;
            switch (c) {
                case GZIP:
                    decompressed = new GZIPInputStream(raw, BUF);
                    break;
                case XZ:
                    decompressed = new XZCompressorInputStream(raw);
                    break;
                case NONE:
                default:
                    decompressed = raw;
                    break;
            }

            TarArchiveInputStream tar = new TarArchiveInputStream(decompressed);
            byte[] buf = new byte[BUF];
            TarArchiveEntry e;
            while ((e = tar.getNextTarEntry()) != null) {
                String name = e.getName();
                File target = resolveSafely(destDir, name);
                if (target == null) {
                    r.warnings.add("跳过不安全路径: " + name);
                    continue;
                }

                if (e.isDirectory()) {
                    target.mkdirs();
                } else if (e.isSymbolicLink()) {
                    target.getParentFile().mkdirs();
                    linkBestEffort(target, e.getLinkName(), r);
                } else if (e.isLink()) {
                    // 硬链接：宿主文件系统禁硬链接，降级为软链
                    target.getParentFile().mkdirs();
                    linkBestEffort(target, e.getLinkName(), r);
                } else if (e.isFile()) {
                    target.getParentFile().mkdirs();
                    try (OutputStream os = new FileOutputStream(target)) {
                        int n;
                        while ((n = tar.read(buf)) > 0) {
                            os.write(buf, 0, n);
                            r.bytes += n;
                        }
                    }
                    applyMode(target, e.getMode());
                } else {
                    // 其它类型（字符设备等）跳过
                    continue;
                }
                r.entries++;
                if (progress != null && (r.entries & 0x3F) == 0) {
                    progress.onProgress(r.bytes, 0, r.entries);
                }
            }
            tar.close();

            // 关键目录放开写权限（阶段 0 实测必需）
            relaxPerms(destDir, r);

            r.ok = true;
            if (progress != null) {
                progress.onProgress(r.bytes, r.bytes, r.entries);
            }
            LogStore.getInstance().info(TAG, r.summary());
        } catch (Throwable t) {
            r.ok = false;
            r.error = t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage());
            LogStore.getInstance().error(TAG, "解压异常: " + t);
        }
        return r;
    }

    // ------------------------------------------------------------ 辅助

    /**
     * 安全解析条目路径：拒绝绝对路径与 {@code ..} 逃逸。
     * 返回 null 表示该条目应被跳过。
     */
    private static File resolveSafely(File destDir, String name) {
        if (name == null || name.isEmpty()) return null;
        String n = name.replace('\\', '/');
        // 去掉前导 ./ 与 /
        while (n.startsWith("./")) n = n.substring(2);
        while (n.startsWith("/")) n = n.substring(1);
        if (n.isEmpty()) return null;
        File f = new File(destDir, n);
        try {
            String destPath = destDir.getCanonicalPath();
            String fPath = f.getCanonicalPath();
            if (!fPath.equals(destPath) && !fPath.startsWith(destPath + File.separator)) {
                return null; // 逃逸
            }
        } catch (IOException ex) {
            return null;
        }
        return f;
    }

    /** 读取已有 rootfs 的发行版标识（用于日志提示）。 */
    private static String readDistroHint(File rootfs) {
        try {
            File osr = new File(rootfs, "etc/os-release");
            if (osr.exists()) {
                try (java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(new java.io.FileInputStream(osr)))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (line.startsWith("PRETTY_NAME=")) {
                            return line.substring("PRETTY_NAME=".length())
                                    .replace("\"", "").trim();
                        }
                    }
                }
            }
            File ar = new File(rootfs, "etc/alpine-release");
            if (ar.exists()) {
                try (java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(new java.io.FileInputStream(ar)))) {
                    String v = br.readLine();
                    if (v != null) return "Alpine " + v.trim();
                }
            }
        } catch (Throwable ignored) {}
        return "";
    }

    /** 建软链（失败降级为跳过，记录警告）。 */
    private static void linkBestEffort(File link, String targetName, Result r) {
        try {
            link.delete();
            android.system.Os.symlink(targetName, link.getAbsolutePath());
        } catch (Throwable t) {
            // 部分链接指向包外（如 /bin/busybox），解析失败不影响解压
            r.warnings.add("软链失败 " + link.getName() + " → " + targetName);
        }
    }

    /** 尽力恢复权限位（Android 上 chmod 可能受限）。
     *
     * <p>性能：原实现对每个文件调用 3 次 setXxx（3 次系统调用）。
     * 改为用 Os.chmod 一次设置，且仅在 tar 记录非默认权限时才调用。
     */
    private static void applyMode(File f, int mode) {
        if (mode <= 0) return;
        // 只取低 12 位（权限位），屏蔽 setuid/setgid/sticky 之外的标志
        int perm = mode & 0777;
        // 0644 是绝大多数普通文件的默认值，跳过以减少系统调用
        if (perm == 0644) return;
        try {
            android.system.Os.chmod(f.getAbsolutePath(), perm);
        } catch (Throwable ignored) {}
    }

    /**
     * 放开关键目录权限（阶段 0 实测：不放开会装不上包）。
     *
     * <p>rootfs 解压后部分目录权限为 700 且 owner 为打包时 uid，
     * proot 内可能无法写。放开这些目录的权限位。
     */
    private static void relaxPerms(File rootfs, Result r) {
        String[] rel = {
                "tmp", "root", "var", "var/lib", "var/lib/dpkg",
                "var/cache", "var/cache/apt", "var/cache/apk",
                "lib/apk", "lib/apk/db", "etc/apk", "etc/apt", "run", "dev",
        };
        for (String p : rel) {
            File d = new File(rootfs, p);
            if (d.exists()) {
                chmod777(d);
            }
        }
        LogStore.getInstance().info(TAG, "已放开关键目录权限");
    }

    /** 尽力 chmod 777（目录）。 */
    private static void chmod777(File f) {
        try {
            android.system.Os.chmod(f.getAbsolutePath(), 0777);
        } catch (Throwable ignored) {}
    }

    /**
     * 递归删除目录树（重装前清场）。
     *
     * <p>与 {@code RuntimeManager.deleteRecursively} 同理：必须用 {@code lstat} 判断，
     * 软链直接 unlink，否则悬空软链会导致父目录删不掉。
     */
    private static void deleteRecursively(File f) {
        if (f == null) return;
        try {
            int type = android.system.Os.lstat(f.getAbsolutePath()).st_mode
                    & android.system.OsConstants.S_IFMT;
            if (type == android.system.OsConstants.S_IFLNK) {
                f.delete();
                return;
            }
            if (type != android.system.OsConstants.S_IFDIR) {
                f.delete();
                return;
            }
        } catch (Throwable t) {
            if (!f.exists()) return;
        }
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) deleteRecursively(c);
        }
        f.delete();
    }
}
