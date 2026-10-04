package com.termi.app;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;

/**
 * 通过 Shizuku（shell 身份）执行文件操作。
 *
 * <h3>为什么需要它</h3>
 * rootfs 放在 {@code /data/local/tmp}（SELinux 标签 {@code shell_data_file}），
 * 这是<b>唯一</b>终端（app 身份）与 MCP（shell 身份）都能读的位置。
 * 但 <b>app 身份对该目录只读</b>，因此写入必须委托给 shell 身份的 Shizuku。
 *
 * <h3>实现约定</h3>
 * <ul>
 *   <li>命令执行<b>复用 {@link ShellExecutor}</b>（已验证的 Shizuku 调用路径），
 *       不再自行实现——自行实现时只读 stdout 会导致 stderr 管道写满而卡死。</li>
 *   <li>所有失败原因记入 {@link #getLastError()}，供上层拼进用户可见的错误信息。</li>
 * </ul>
 */
public final class ShizukuFs {

    private static final String TAG = "ShizukuFs";

    /** 环境根目录（shell_data_file，两身份可读；写需 Shizuku）。 */
    public static final String ENV_ROOT = "/data/local/tmp/terx";

    /** 最近一次失败原因（供上层展示给用户）。 */
    private static volatile String lastError = "";

    private ShizukuFs() {}

    /** 取最近一次失败原因。 */
    public static String getLastError() {
        return lastError;
    }

    private static void fail(String msg) {
        lastError = msg;
        LogStore.getInstance().error(TAG, msg);
    }

    private static void clearError() {
        lastError = "";
    }

    /** Shizuku 是否可用（binder 存活 + 已授权）。 */
    public static boolean available() {
        boolean ready = ShellExecutor.isShizukuReady();
        boolean perm = ready && ShellExecutor.hasPermission();
        if (!ready) {
            lastError = "Shizuku 服务未运行";
        } else if (!perm) {
            lastError = "Shizuku 未授权";
        }
        return ready && perm;
    }

    // ------------------------------------------------------------ 基础执行

    /**
     * 执行 shell 命令。
     *
     * <p>复用 {@link ShellExecutor#exec} —— 它是已验证的 Shizuku 调用路径
     * （深度自检即用它）。自行实现时容易漏读 stderr 导致管道阻塞。
     */
    public static Exec exec(String cmd) {
        if (!available()) {
            return null;
        }
        clearError();
        try {
            ShellExecutor.Result r = ShellExecutor.exec(cmd, null, 60L, false);
            if (r == null) {
                fail("Shizuku 调用无返回: " + cmd);
                return null;
            }
            Exec e = new Exec();
            StringBuilder sb = new StringBuilder();
            if (r.stdout != null) sb.append(r.stdout);
            if (r.stderr != null && !r.stderr.isEmpty()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(r.stderr);
            }
            e.output = sb.toString();
            e.exitCode = r.exitCode;
            if (!e.ok()) {
                fail("命令失败 exit=" + r.exitCode + " cmd=" + cmd
                        + " out=" + truncate(e.output, 300));
            }
            return e;
        } catch (Throwable t) {
            fail("exec 异常: " + t);
            return null;
        }
    }

    public static final class Exec {
        public String output = "";
        public int exitCode = -1;

        public boolean ok() {
            return exitCode == 0;
        }
    }

    // ------------------------------------------------------------ 常用操作

    /** 确保环境根目录存在且可写（幂等）。 */
    public static boolean ensureEnvRoot() {
        Exec e = exec("mkdir -p " + ENV_ROOT + "/envs " + ENV_ROOT + "/lib "
                + ENV_ROOT + "/tmp && chmod 777 " + ENV_ROOT + " " + ENV_ROOT + "/envs "
                + ENV_ROOT + "/lib " + ENV_ROOT + "/tmp");
        return e != null && e.ok();
    }

    /** 目录是否存在（用 shell 判断，避免 app 身份读不到时的误判）。 */
    public static boolean exists(String path) {
        Exec e = exec("test -e " + q(path) + " && echo YES || echo NO");
        return e != null && e.output.contains("YES");
    }

    /** 递归删除。 */
    public static boolean deleteRecursive(String path) {
        Exec e = exec("rm -rf " + q(path));
        boolean ok = e != null && e.ok() && !exists(path);
        if (!ok && lastError.isEmpty()) {
            fail("删除失败: " + path);
        }
        return ok;
    }

    /** 读取文本文件内容（小文件）。 */
    public static String readText(String path) {
        Exec e = exec("cat " + q(path) + " 2>/dev/null");
        return e == null ? null : e.output;
    }

    /** 写入文本文件。 */
    public static boolean writeText(String path, String content) {
        String b64 = android.util.Base64.encodeToString(
                content.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                android.util.Base64.NO_WRAP);
        Exec e = exec("mkdir -p $(dirname " + q(path) + ") && echo " + q(b64)
                + " | base64 -d > " + q(path));
        return e != null && e.ok();
    }

    /**
     * 通过管道把目录内容复制到目标位置（App 侧产生字节，Shizuku 侧落盘）。
     *
     * <p>用于导入 rootfs：App 解压到自己的 cache 后，用 tar 打成流，
     * 由 shell 身份解到 {@code /data/local/tmp}。
     *
     * <p>注意：需要双向管道（写 stdin、读 stdout），因此必须直接用
     * {@code newProcess}，不能复用 {@link ShellExecutor}。
     * 这里同时读 stdout 与 stderr，避免管道写满。
     */
    public static boolean pipeCopyDir(File sourceDir, String destDir) {
        if (!available()) return false;
        clearError();
        Process proc = null;
        Process tar = null;
        try {
            // 关键：解包后必须放开权限。
            // 原因：App 进程 umask=0077，File.mkdirs() 建出的暂存目录是 0700，
            // tar 会把这权限一并打包并还原，导致 rootfs 变成 shell:shell 0700，
            // 而 App（untrusted_app，不同 uid）无法进入 → 校验报"不是有效的 rootfs"。
            // 另外终端里的 proot 以 App 身份运行，apt 安装需要写 rootfs，故必须 777。
            String parent = new File(destDir).getParent();
            String cmd = "mkdir -p " + q(destDir) + " && tar -xf - -C " + q(destDir)
                    + " && chmod -R 777 " + q(parent == null ? destDir : parent);
            proc = newProcess(new String[]{"sh", "-c", cmd}, null, null);

            // 同时读 stdout 与 stderr，避免任一管道写满导致死锁
            final Process fp = proc;
            final StringBuilder out = new StringBuilder();
            Thread r1 = drain(fp.getInputStream(), out, "shizuku-pipe-out");
            Thread r2 = drain(fp.getErrorStream(), out, "shizuku-pipe-err");

            // 关键：用 "-C <源目录> ." 打包【目录内容】，而不是目录本身。
            // 若写成 "-C <父目录> <目录名>"，解出来会多一层同名目录，
            // 导致调用方按 <dest>/rootfs 找不到内容而校验失败。
            tar = new ProcessBuilder("tar", "-cf", "-", "-C",
                    sourceDir.getAbsolutePath(), ".")
                    .redirectErrorStream(true)
                    .start();
            try (OutputStream os = proc.getOutputStream();
                 InputStream is = tar.getInputStream()) {
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = is.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
                os.flush();
            }
            tar.waitFor();
            proc.waitFor();
            r1.join(500L);
            r2.join(500L);

            int code = proc.exitValue();
            if (code != 0) {
                fail("管道复制失败 exit=" + code + " out=" + truncate(out.toString(), 300));
                return false;
            }
            LogStore.getInstance().info(TAG, "管道复制完成 → " + destDir);
            return true;
        } catch (Throwable t) {
            fail("pipeCopyDir 异常: " + t);
            return false;
        } finally {
            if (tar != null) {
                try { tar.destroyForcibly(); } catch (Throwable ignored) {}
            }
            if (proc != null) {
                try { proc.destroyForcibly(); } catch (Throwable ignored) {}
            }
        }
    }

    /** 启动一个读取线程，把流内容追加到 sb。 */
    private static Thread drain(final InputStream in, final StringBuilder sb, String name) {
        Thread t = new Thread(() -> {
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(in))) {
                String line;
                while ((line = r.readLine()) != null) {
                    synchronized (sb) {
                        sb.append(line).append('\n');
                    }
                }
            } catch (Throwable ignored) {}
        }, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    // ------------------------------------------------------------ 辅助

    private static String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /**
     * 以 <b>shell 身份</b>启动一个进程（供终端使用）。
     *
     * <p>为何终端也要走 Shizuku：rootfs 位于 {@code /data/local/tmp}
     * （SELinux {@code shell_data_file}），实测 App（{@code untrusted_app} 域）
     * <b>无法读软链、无法执行程序</b>，而 proot 两者都必须。
     * 以 shell 身份启动后，终端与 MCP 共享同一份 rootfs。
     *
     * <p><b>调用形式必须与 {@link ShellExecutor} 一致</b>：
     * {@code newProcess(["sh","-c",cmd], null, null)}。
     * 实测直接用完整 argv + 非 null env 时，返回的 Process 其
     * {@code getInputStream()} <b>读不到任何数据</b>（跨进程流异常）；
     * 而 {@code sh -c} 形式（ShellExecutor 已验证可用）则正常。
     *
     * @param shellCmd 完整的 shell 命令字符串
     * @return 进程对象；失败返回 null
     */
    public static Process startProcess(String shellCmd) {
        if (!available()) {
            LogStore.getInstance().error(TAG, "Shizuku 不可用，无法启动进程（"
                    + lastError + "）");
            return null;
        }
        try {
            return newProcess(new String[]{"sh", "-c", shellCmd}, null, null);
        } catch (Throwable t) {
            fail("启动进程失败: " + t);
            return null;
        }
    }

    /** 反射调用 Shizuku.newProcess（与 ShellExecutor 同法）。 */
    public static Process newProcess(String[] cmd, String[] env, String dir) throws Exception {
        Method m = rikka.shizuku.Shizuku.class.getDeclaredMethod(
                "newProcess", String[].class, String[].class, String.class);
        m.setAccessible(true);
        return (Process) m.invoke(null, cmd, env, dir);
    }
}
