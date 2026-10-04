package com.termi.app;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * TERX 运行时管理：proot 二进制定位、环境探测、启动命令构建。
 *
 * <h3>设计要点（源自阶段 0 与里程碑 1 实测）</h3>
 * <ul>
 *   <li><b>落位</b>：proot 二进制内置在 APK 的 {@code nativeLibraryDir}（命名为 {@code lib*.so}），
 *       该目录是 SELinux 允许应用执行程序的少数位置之一；App 私有目录（filesDir）
 *       在 targetSdk≥28 上被禁止执行，外置存储则挂载为 noexec。</li>
 *   <li><b>loader</b>：{@code libproot_loader.so} 是 proot 在 Android 上启动 guest 进程的必需品，
 *       通过 {@code PROOT_LOADER} 环境变量指定。</li>
 *   <li><b>库名</b>：{@code libproot.so} 的 NEEDED 项是 {@code libtalloc.so.2}（按 SONAME），
 *       而 AGP 只打包 {@code *.so} 结尾的文件，无法直接提供 {@code libtalloc.so.2}。
 *       因此运行时在 {@code codeCacheDir/prootlib} 建立同名软链，并让 {@code LD_LIBRARY_PATH}
 *       指向该目录 —— 软链只是名字别名，真正被 mmap 的文件仍在 nativeLibraryDir（可执行）。</li>
 *   <li><b>--link2symlink</b>：必需参数。本机 /data 与 /storage 均禁止硬链接，
 *       dpkg/apk 的备份与原子替换依赖 link()，不加此参数会全部失败。</li>
 * </ul>
 */
public final class RuntimeManager {

    private static final String TAG = "RuntimeManager";

    /** APK 内置文件名（nativeLibraryDir 内）。 */
    private static final String PROOT_SO = "libproot.so";
    private static final String LOADER_SO = "libproot_loader.so";
    private static final String LOADER32_SO = "libproot_loader32.so";
    private static final String TALLOC_SO = "libtalloc.so";
    private static final String SHMEM_SO = "libandroid-shmem.so";

    /** 软链目录名（codeCacheDir 下），用于补齐 SONAME 别名。 */
    private static final String LIBS_DIR = "prootlib";

    private RuntimeManager() {}

    // ------------------------------------------------------------ 路径

    /** App 原生库目录（jniLibs 解包处，SELinux 允许应用执行）。 */
    public static File nativeLibDir(Context ctx) {
        return new File(ctx.getApplicationInfo().nativeLibraryDir);
    }

    /**
     * 补齐 SONAME 别名的软链目录。
     *
     * <p><b>位置在 {@code /data/local/tmp/terx/lib}</b>（SELinux {@code shell_data_file}）——
     * 这是终端（app 身份）与 MCP（shell 身份）都能读的位置。
     * 若放在 App 私有目录，MCP 侧读不到软链，proot 会报 {@code libtalloc.so.2 not found}。
     */
    public static File libsDir(Context ctx) {
        return EnvStore.libDir();
    }

    public static File prootBin(Context ctx) {
        return new File(nativeLibDir(ctx), PROOT_SO);
    }

    public static File loader(Context ctx) {
        return new File(nativeLibDir(ctx), LOADER_SO);
    }

    /** APK 内置的 proot 相关文件是否齐全。 */
    public static boolean isProotReady(Context ctx) {
        File[] need = {
                prootBin(ctx),
                loader(ctx),
                new File(nativeLibDir(ctx), TALLOC_SO),
                new File(nativeLibDir(ctx), SHMEM_SO),
        };
        for (File f : need) {
            if (!f.isFile()) {
                LogStore.getInstance().warn(TAG, "缺少文件: " + f.getAbsolutePath());
                return false;
            }
        }
        return true;
    }

    /**
     * 建立/刷新 SONAME 软链目录（幂等）。
     *
     * <p>关键：{@code libtalloc.so} 的 SONAME 是 {@code libtalloc.so.2}，
     * 动态链接器按 SONAME 查找，故必须有名为 {@code libtalloc.so.2} 的文件。
     * AGP 无法把该名字打进 APK，只能在运行时建软链。
     *
     * <p><b>必须走 Shizuku</b>：软链目录在 {@code /data/local/tmp}，app 身份写不了。
     *
     * @return 软链目录；失败返回 null
     */
    public static File ensureLibLinks(Context ctx) {
        File dir = libsDir(ctx);
        if (!ShizukuFs.available()) {
            LogStore.getInstance().error(TAG, "Shizuku 不可用，无法建立软链目录");
            return null;
        }
        if (!ShizukuFs.ensureEnvRoot()) {
            LogStore.getInstance().error(TAG, "无法创建环境根目录");
            return null;
        }
        File nl = nativeLibDir(ctx);
        // 目标名 -> nativeLibraryDir 中的真实文件名
        String[][] links = {
                {"libproot.so", PROOT_SO},
                {"libtalloc.so.2", TALLOC_SO},
                {"libtalloc.so", TALLOC_SO},
                {"libandroid-shmem.so", SHMEM_SO},
        };
        StringBuilder sb = new StringBuilder("mkdir -p " + q(dir.getAbsolutePath()) + " && ");
        for (String[] pair : links) {
            File target = new File(nl, pair[1]);
            if (!target.isFile()) {
                LogStore.getInstance().warn(TAG, "软链目标缺失: " + target);
                continue;
            }
            sb.append("ln -sf ").append(q(target.getAbsolutePath()))
              .append(" ").append(q(new File(dir, pair[0]).getAbsolutePath()))
              .append(" && ");
        }
        sb.append("chmod -R 777 ").append(q(dir.getAbsolutePath()));
        ShizukuFs.Exec e = ShizukuFs.exec(sb.toString());
        if (e == null || !e.ok()) {
            LogStore.getInstance().error(TAG, "建软链失败: "
                    + (e == null ? "Shizuku 不可用" : e.output));
            return null;
        }
        return dir;
    }

    /** shell 单引号转义。 */
    private static String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    // ------------------------------------------------------------ 环境探测

    /** 一次性探测结果。 */
    public static final class Probe {
        public boolean prootPresent;
        public boolean prootExecutable;
        public boolean loaderPresent;
        public boolean libsReady;
        public String prootVersion = "";
        public String nativeLibDir = "";
        public String libsDir = "";
        public String abi = "";
        public int sdk;
        public String error = "";

        public boolean isOk() {
            return prootPresent && prootExecutable && loaderPresent && libsReady;
        }

        public String summary() {
            if (isOk()) {
                return "proot 就绪 (" + prootVersion + ")";
            }
            return "proot 不可用: " + (error.isEmpty() ? "未知原因" : error);
        }
    }

    /**
     * 探测 proot 是否可用。会真正执行一次 {@code proot --version}。
     * 注意：必须在后台线程调用（涉及进程创建与 IO）。
     */
    public static Probe probe(Context ctx) {
        Probe p = new Probe();
        p.sdk = Build.VERSION.SDK_INT;
        p.abi = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?";
        p.nativeLibDir = nativeLibDir(ctx).getAbsolutePath();

        File bin = prootBin(ctx);
        File ld = loader(ctx);
        p.prootPresent = bin.isFile();
        p.loaderPresent = ld.isFile();
        if (!p.prootPresent) {
            p.error = "nativeLibraryDir 中找不到 " + PROOT_SO;
            LogStore.getInstance().error(TAG, p.error);
            return p;
        }
        if (!p.loaderPresent) {
            p.error = "nativeLibraryDir 中找不到 " + LOADER_SO;
            LogStore.getInstance().error(TAG, p.error);
            return p;
        }

        // 可执行位（useLegacyPackaging 正常时应有；无则尝试补）
        if (!bin.canExecute()) {
            LogStore.getInstance().warn(TAG, "proot 无可执行位，尝试补: "
                    + bin.setExecutable(true, false));
        }
        if (!ld.canExecute()) {
            ld.setExecutable(true, false);
        }

        // 建立 SONAME 软链
        File libs = ensureLibLinks(ctx);
        if (libs == null) {
            p.error = "建立 SONAME 软链失败";
            LogStore.getInstance().error(TAG, p.error);
            return p;
        }
        p.libsReady = true;
        p.libsDir = libs.getAbsolutePath();

        String out = execCapture(new String[]{bin.getAbsolutePath(), "--version"},
                buildProotEnv(ctx), 8000);
        if (out == null) {
            p.error = "执行 proot 失败（可能被 SELinux 拦截或 ABI 不符）";
            LogStore.getInstance().error(TAG, p.error);
            return p;
        }
        p.prootExecutable = true;
        // 版本号在 banner 的最后一行
        for (String line : out.split("\n")) {
            String t = line.trim();
            if (t.matches(".*\\d+\\.\\d+\\.\\d+.*")) {
                p.prootVersion = t;
            }
        }
        if (p.prootVersion.isEmpty()) {
            p.prootVersion = out.replace("\n", " ").trim();
        }
        LogStore.getInstance().info(TAG, "proot 探测成功: " + p.prootVersion
                + " | abi=" + p.abi + " sdk=" + p.sdk);
        return p;
    }

    /** 执行命令并捕获合并输出；失败返回 null。 */
    private static String execCapture(String[] cmd, String[] env, long timeoutMs) {
        Process proc = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            if (env != null) {
                pb.environment().putAll(parseEnv(env));
            }
            proc = pb.start();
            final Process fp = proc;
            final StringBuilder sb = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(fp.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        sb.append(line).append('\n');
                    }
                } catch (Throwable ignored) {}
            }, "proot-probe-reader");
            reader.setDaemon(true);
            reader.start();

            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                try {
                    if (proc.exitValue() >= 0) break;
                } catch (IllegalThreadStateException stillRunning) {
                    Thread.sleep(20L);
                }
            }
            reader.join(500L);
            return sb.toString();
        } catch (Throwable t) {
            LogStore.getInstance().error(TAG, "execCapture 失败: " + t);
            return null;
        } finally {
            if (proc != null) {
                try { proc.destroyForcibly(); } catch (Throwable ignored) {}
            }
        }
    }

    private static java.util.Map<String, String> parseEnv(String[] env) {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        for (String kv : env) {
            int i = kv.indexOf('=');
            if (i > 0) m.put(kv.substring(0, i), kv.substring(i + 1));
        }
        return m;
    }

    // ------------------------------------------------------------ 启动命令

    /** proot 运行所需的环境变量（LD_LIBRARY_PATH / PROOT_LOADER / PROOT_TMP_DIR）。 */
    public static String[] buildProotEnv(Context ctx) {
        File libs = libsDir(ctx);
        // LD_LIBRARY_PATH 必须包含软链目录（提供 libtalloc.so.2 这个名字），
        // 同时带上 nativeLibraryDir 作为兜底。
        String libPath = libs.getAbsolutePath()
                + File.pathSeparator + nativeLibDir(ctx).getAbsolutePath();
        return new String[]{
                "LD_LIBRARY_PATH=" + libPath,
                "PROOT_LOADER=" + loader(ctx).getAbsolutePath(),
                // TMP 目录也在 /data/local/tmp（两身份可读；私有目录 MCP 侧写不了）
                "PROOT_TMP_DIR=" + EnvStore.tmpDir().getAbsolutePath(),
        };
    }

    /**
     * 构建 proot 启动命令（阶段 0 验证通过的模板）。
     *
     * @param rootfs  rootfs 目录
     * @param workdir guest 内的工作目录（rootfs 内路径，如 /root）
     * @param command guest 内要执行的命令（如 /bin/bash -c '...'）
     */
    public static List<String> buildProotCommand(Context ctx, File rootfs,
                                                 String workdir, String... command) {
        List<String> c = new ArrayList<>();
        c.add(prootBin(ctx).getAbsolutePath());
        c.add("--link2symlink");   // 必需：宿主禁硬链接
        c.add("-0");               // 假 root
        c.add("-r");
        c.add(rootfs.getAbsolutePath());
        c.add("-b");
        c.add("/dev");
        c.add("-b");
        c.add("/proc");
        c.add("-b");
        c.add("/sys");
        // 绑定用户存储：让 guest 能读写 /storage/emulated/0/（含隐藏文件）。
        // 这是 proot 层的能力，不需要 App 申请「所有文件访问」权限。
        // 若宿主路径不存在则跳过（部分设备/精简系统可能没有）。
        File storage = new File("/storage");
        if (storage.isDirectory()) {
            c.add("-b");
            c.add("/storage");
        }
        // 绑定 resolv.conf：rootfs 内没有 DNS 配置时解析会失败（阶段 0 实测必需）
        File resolv = EnvStore.resolvConf(EnvStore.getActiveEnv(ctx));
        if (resolv.isFile()) {
            c.add("-b");
            c.add(resolv.getAbsolutePath() + ":/etc/resolv.conf");
        }
        if (workdir != null && !workdir.isEmpty()) {
            c.add("-w");
            c.add(workdir);
        }
        c.add("/usr/bin/env");
        c.add("-i");
        c.add("HOME=/root");
        c.add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        c.add("TERM=xterm-256color");
        c.add("LANG=C.UTF-8");
        // 注意：不在此处设 PS1 —— 登录 shell 会 source /etc/profile，
        // 其中的 PS1 赋值会覆盖这里的值。改用 ENV 指向的 rc 文件（见 buildLinuxShellProcess）。
        for (String s : command) {
            c.add(s);
        }
        return c;
    }

    /**
     * 环境是否可用于终端/MCP：proot 就绪 <b>且</b> rootfs 有效。
     */
    public static boolean isEnvUsable(Context ctx) {
        return isProotReady(ctx) && isRootfsValid(ctx);
    }

    /**
     * 选择 rootfs 内的登录 shell。
     *
     * <p>不同发行版默认 shell 不同：Debian/Ubuntu 有 bash，Alpine 只有 ash。
     * 按优先级探测，均不存在时退回 /bin/sh。
     */
    public static String pickShell(File rootfs) {
        String[] cands = {"bin/bash", "usr/bin/bash", "bin/ash", "usr/bin/ash", "bin/sh"};
        for (String c : cands) {
            if (entryExists(new File(rootfs, c))) {
                return "/" + c;
            }
        }
        return "/bin/sh";
    }

    /**
     * 生成 Linux 环境的 ENV rc 文件（设置自定义提示符）。
     *
     * <p><b>为何不直接在 proot env 里传 PS1</b>：登录 shell 会 source /etc/profile，
     * 其中的 PS1 赋值会覆盖环境变量。实测把 PS1 写在 ENV 指向的文件里才稳定生效
     * （ash 在 profile 之后 source ENV，故最终生效）。
     *
     * <p>文件写在 rootfs 内（{@code /root/.terxrc}），随环境持久化，
     * 用户也可以自行编辑它来定制提示符。
     *
     * <p>注意：rootfs 在 {@code /data/local/tmp}，写入必须走 Shizuku。
     */
    public static String ensureLinuxEnvRc(Context ctx) {
        return ensureLinuxEnvRc(ctx, EnvStore.getActiveEnv(ctx));
    }

    /** 为指定环境生成 ENV rc 文件。 */
    public static String ensureLinuxEnvRc(Context ctx, String envName) {
        File rc = new File(EnvStore.rootfsDir(envName), "root/.terxrc");
        try {
            // 已存在且非空则复用（保留用户的定制）
            String existing = ShizukuFs.readText(rc.getAbsolutePath());
            if (existing != null && !existing.trim().isEmpty()) {
                return "/root/.terxrc";
            }
            String content = "PS1=\"[STRX] \\w # \"\n";
            if (!ShizukuFs.writeText(rc.getAbsolutePath(), content)) {
                LogStore.getInstance().warn(TAG, "写 Linux ENV rc 失败: " + rc);
                return null;
            }
            return "/root/.terxrc";
        } catch (Throwable t) {
            LogStore.getInstance().warn(TAG, "写 Linux ENV rc 异常: " + t);
            return null;
        }
    }

    /**
     * 构建 <b>Linux 环境</b>的终端 {@link ProcessBuilder}（proot + 登录交互 shell）。
     *
     * <p><b>为何必须加 {@code -i}</b>：ash/bash 只在<b>交互模式</b>下打印提示符。
     * 本终端的 stdin 是管道（非 tty），不加 {@code -i} 就不算交互模式，
     * 结果是<b>完全没有提示符</b>（但命令仍会执行，表现为"能跑但一片空白"）。
     * 实测：{@code ash -l} → 无提示符；{@code ash -l -i} → 正常显示提示符。
     *
     * <p>登录 shell（{@code -l}）会读取 /etc/profile 与 ~/.profile，
     * 这是环境变量、alias 得以持久化的关键。
     *
     * <p>注意：proot 依赖 {@code LD_LIBRARY_PATH} / {@code PROOT_LOADER} / {@code PROOT_TMP_DIR}，
     * 必须通过 ProcessBuilder 注入。
     */
    public static ProcessBuilder buildLinuxShellProcess(Context ctx) {
        File rootfs = EnvStore.rootfsDir(ctx);
        String shell = pickShell(rootfs);
        // -l 登录（读 profile，保证持久化）；-i 交互（无 tty 时也要打印提示符）
        List<String> cmd = buildProotCommand(ctx, rootfs, "/root", shell, "-l", "-i");
        // ENV 指向 rc 文件：ash 在 /etc/profile 之后 source 它，提示符不会被 profile 覆盖
        String envRc = ensureLinuxEnvRc(ctx);
        if (envRc != null) {
            // 插到 /usr/bin/env -i 之后（env 参数区）
            int idx = cmd.indexOf("/usr/bin/env");
            if (idx >= 0) {
                cmd.add(idx + 2, "ENV=" + envRc);
            }
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        for (String kv : buildProotEnv(ctx)) {
            int i = kv.indexOf('=');
            if (i > 0) {
                pb.environment().put(kv.substring(0, i), kv.substring(i + 1));
            }
        }
        pb.redirectErrorStream(true);
        LogStore.getInstance().info(TAG, "终端启动 Linux 环境: shell=" + shell
                + " rootfs=" + rootfs);
        return pb;
    }

    /**
     * 构建 Linux 环境 shell 的 <b>命令数组与环境数组</b>（供 Shizuku 以 shell 身份启动）。
     *
     * <p>与 {@link #buildLinuxShellProcess} 的区别：本方法返回原始参数，
     * 由调用方通过 {@code Shizuku.newProcess} 启动（shell 身份），
     * 从而能访问 {@code /data/local/tmp} 上的 rootfs。
     *
     * @return 长度 2 的数组：{argv, env}；环境不可用时返回 null
     */
    public static String[][] buildLinuxShellArgv(Context ctx) {
        if (!isEnvUsable(ctx)) return null;
        File rootfs = EnvStore.rootfsDir(ctx);
        String shell = pickShell(rootfs);
        List<String> cmd = buildProotCommand(ctx, rootfs, "/root", shell, "-l", "-i");
        String envRc = ensureLinuxEnvRc(ctx);
        if (envRc != null) {
            int idx = cmd.indexOf("/usr/bin/env");
            if (idx >= 0) {
                cmd.add(idx + 2, "ENV=" + envRc);
            }
        }
        String[] argv = cmd.toArray(new String[0]);
        String[] env = buildProotEnv(ctx);
        LogStore.getInstance().info(TAG, "构建 Linux shell argv: shell=" + shell
                + " rootfs=" + rootfs);
        return new String[][]{argv, env};
    }

    /**
     * 构建 MCP 用的 <b>proot 前缀</b>（可嵌入到 {@code sh -c '<前缀> <命令>'} 中）。
     *
     * <p>与 {@link #startLinuxShellAsShell} 的区别：后者用于交互式终端（带 {@code -l -i}），
     * 本方法用于"执行单条命令即退出"，故用 {@code <shell> -c} 而非登录交互 shell。
     *
     * <p><b>为何不直接用 Shizuku 的流</b>：MCP 的 shell_exec 走的正是
     * {@code ShellExecutor}（Shizuku + sh -c），是已验证可用的短命令路径，
     * 故这里只需把 proot 调用内联进命令字符串即可。
     *
     * @param envName 环境名；为空则用当前激活环境
     * @return 形如 {@code export LD_LIBRARY_PATH='...'; exec '.../libproot.so' ... '/bin/sh' -c} 的字符串；
     *         环境不可用时返回 null
     */
    public static String buildProotPrefix(Context ctx, String envName) {
        try {
            String name = (envName == null || envName.isEmpty())
                    ? EnvStore.getActiveEnv(ctx) : envName;
            File rootfs = EnvStore.rootfsDir(name);
            if (!isProotReady(ctx) || !isRootfsValid(rootfs)) {
                LogStore.getInstance().warn(TAG, "环境不可用，无法构建 proot 前缀: " + name);
                return null;
            }

            String shell = pickShell(rootfs);
            List<String> argv = buildProotCommand(ctx, rootfs, "/root", shell, "-c");
            String envRc = ensureLinuxEnvRc(ctx, name);
            if (envRc != null) {
                int idx = argv.indexOf("/usr/bin/env");
                if (idx >= 0) argv.add(idx + 2, "ENV=" + envRc);
            }

            StringBuilder sb = new StringBuilder();
            for (String kv : buildProotEnv(ctx)) {
                int i = kv.indexOf('=');
                if (i > 0) {
                    sb.append("export ").append(kv.substring(0, i)).append('=')
                      .append(q(kv.substring(i + 1))).append("; ");
                }
            }
            sb.append("exec");
            for (String a : argv) {
                sb.append(' ').append(q(a));
            }
            // 末尾追加一个占位参数位：调用方拼接 " <单引号包裹的命令>"
            String prefix = sb.toString();
            LogStore.getInstance().info(TAG, "构建 proot 前缀 env=" + name
                    + " shell=" + shell);
            return prefix;
        } catch (Throwable t) {
            LogStore.getInstance().error(TAG, "buildProotPrefix 异常: " + t);
            return null;
        }
    }

    /** 一个已安装包的信息。 */
    public static final class PkgInfo {
        public String name = "";
        /** 安装大小（KB）；未知为 0。 */
        public long sizeKb = 0L;

        public String sizeText() {
            if (sizeKb <= 0) return "—";
            if (sizeKb < 1024) return sizeKb + " KB";
            return String.format(java.util.Locale.US, "%.1f MB", sizeKb / 1024.0);
        }
    }

    /** 包列表查询结果。 */
    public static final class PkgListResult {
        public boolean ok;
        /** 包管理器类型：dpkg / apk / 未知。 */
        public String manager = "";
        public final java.util.List<PkgInfo> packages = new java.util.ArrayList<>();
        public String error = "";

        /** 总安装大小（KB）。 */
        public long totalKb() {
            long s = 0;
            for (PkgInfo p : packages) s += p.sizeKb;
            return s;
        }

        public String totalText() {
            long kb = totalKb();
            if (kb <= 0) return "—";
            if (kb < 1024) return kb + " KB";
            return String.format(java.util.Locale.US, "%.1f MB", kb / 1024.0);
        }
    }

    /**
     * 查询指定环境内已安装的软件包及其大小。
     *
     * <p>按发行版的包管理器分别处理：
     * <ul>
     *   <li>Debian/Ubuntu：{@code dpkg-query -W -f='${Package} ${Installed-Size}\n'}
     *       （Installed-Size 单位为 KB）</li>
     *   <li>Alpine：{@code apk info -v} 取包名，再 {@code apk info -s} 取大小
     *       （Alpine 的 apk 不易一次同时拿到名与大小，故分两步，见实现）</li>
     * </ul>
     *
     * <p><b>注意</b>：必须在后台线程调用（会起 proot 进程）。
     */
    public static PkgListResult listPackages(Context ctx, String envName) {
        PkgListResult r = new PkgListResult();
        String prefix = buildProotPrefix(ctx, envName);
        if (prefix == null) {
            r.error = "环境不可用";
            return r;
        }

        // 探测包管理器，并按对应命令查询
        String cmd =
                // Debian / Ubuntu
                "if command -v dpkg-query >/dev/null 2>&1; then "
                + "dpkg-query -W -f='${Package} ${Installed-Size}\\n' 2>/dev/null; "
                + "elif command -v apk >/dev/null 2>&1; then "
                +   "apk info -v 2>/dev/null | sed 's/^/PKG /'; "
                + "fi";

        ShellExecutor.Result sr = ShellExecutor.execInEnv(cmd, null, 60L, false, prefix);
        if (sr == null) {
            r.error = "查询失败（无返回）";
            return r;
        }

        String out = sr.stdout == null ? "" : sr.stdout;
        for (String line : out.split("\n")) {
            String s = line.trim();
            if (s.isEmpty()) continue;
            if (s.startsWith("PKG ")) {
                // Alpine：只有包名（含版本），大小未知
                r.manager = "apk";
                PkgInfo p = new PkgInfo();
                p.name = s.substring(4).trim();
                r.packages.add(p);
            } else {
                // Debian/Ubuntu： "包名 大小KB"
                String[] parts = s.split("\\s+");
                if (parts.length >= 1 && !parts[0].isEmpty()) {
                    r.manager = "dpkg";
                    PkgInfo p = new PkgInfo();
                    p.name = parts[0];
                    if (parts.length >= 2) {
                        try { p.sizeKb = Long.parseLong(parts[1]); }
                        catch (Throwable ignored) {}
                    }
                    r.packages.add(p);
                }
            }
        }

        // 按大小降序（大的在前，便于定位占用）
        java.util.Collections.sort(r.packages, (a, b) -> Long.compare(b.sizeKb, a.sizeKb));
        r.ok = true;
        LogStore.getInstance().info(TAG, "包列表查询完成 env=" + envName
                + " manager=" + r.manager + " count=" + r.packages.size());
        return r;
    }

    /**
     * 以 <b>shell 身份</b>启动 Linux 环境 shell（供终端使用）。
     *
     * <p>返回标准 {@link Process}，调用方用 {@code getInputStream()} /
     * {@code getOutputStream()} 交互。
     *
     * <p><b>为何拼成单个 shell 字符串</b>：实测 {@code Shizuku.newProcess}
     * 只有在 {@code ["sh","-c",cmd]} 形式下，返回的 Process 才能正常读到输出；
     * 直接传完整 argv + 非 null env 时 {@code getInputStream()} 读不到数据。
     * 故把 proot 的全部参数与所需环境变量内联进一条 {@code sh -c} 命令。
     *
     * @return 进程对象；环境不可用或 Shizuku 不可用时返回 null
     */
    public static Process startLinuxShellAsShell(Context ctx) {
        String[][] spec = buildLinuxShellArgv(ctx);
        if (spec == null) {
            LogStore.getInstance().warn(TAG, "环境不可用，无法以 shell 身份启动");
            return null;
        }
        String[] argv = spec[0];
        String[] env = spec[1];
        // 拼：export K=V; ... ; exec argv...
        StringBuilder sb = new StringBuilder();
        for (String kv : env) {
            int i = kv.indexOf('=');
            if (i > 0) {
                sb.append("export ").append(kv.substring(0, i)).append('=')
                  .append(q(kv.substring(i + 1))).append("; ");
            }
        }
        sb.append("exec");
        for (String a : argv) {
            sb.append(' ').append(q(a));
        }
        String cmd = sb.toString();
        LogStore.getInstance().info(TAG, "以 shell 身份启动: " + cmd);
        return ShizukuFs.startProcess(cmd);
    }

    /**
     * 构建 <b>Android 原生 shell</b>的 {@link ProcessBuilder}（回退用）。
     *
     * <p><b>提示符处理</b>：Android 的 {@code /system/bin/sh} 是 mksh，
     * <b>忽略环境变量 PS1</b>（实测 {@code env PS1=... sh -i} 无效）。
     * 实测有效的做法是 {@code ENV} 环境变量指向一个 rc 文件 ——
     * shell 启动时会在打印首个提示符<b>之前</b> source 它，
     * 因此提示符从一开始就是干净的（不会出现默认提示符叠加）。
     *
     * @param envRcFile ENV 指向的 rc 文件（由 {@link #ensureAndroidEnvRc} 生成），
     *                  为 null 时跳过（保持默认提示符）
     */
    public static ProcessBuilder buildAndroidShellProcess(File envRcFile) {
        ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-i");
        if (envRcFile != null && envRcFile.isFile()) {
            pb.environment().put("ENV", envRcFile.getAbsolutePath());
        }
        pb.redirectErrorStream(true);
        return pb;
    }

    /**
     * 生成 Android shell 的 ENV rc 文件（设置干净提示符）。
     *
     * <p>放在 App 私有目录，避免占用外置存储、也避免被清理。
     */
    public static File ensureAndroidEnvRc(Context ctx) {
        File rc = new File(ctx.getFilesDir(), "android_shell_env");
        try {
            if (!rc.isFile() || rc.length() == 0) {
                try (java.io.FileWriter w = new java.io.FileWriter(rc)) {
                    // 简洁提示符；不覆盖用户后续手动设置的 PS1
                    w.write("PS1='$ '\n");
                }
            }
            return rc;
        } catch (Throwable t) {
            LogStore.getInstance().warn(TAG, "写 ENV rc 失败: " + t);
            return null;
        }
    }

    /**
     * 构建终端 shell 的 {@link ProcessBuilder}（按环境状态自动选择）。
     *
     * <p>保留此方法以兼容旧调用；新代码请用
     * {@link #buildLinuxShellProcess} / {@link #buildAndroidShellProcess} 明确指定模式。
     */
    public static ProcessBuilder buildShellProcess(Context ctx) {
        return isEnvUsable(ctx)
                ? buildLinuxShellProcess(ctx)
                : buildAndroidShellProcess(ensureAndroidEnvRc(ctx));
    }

    /** 终端模式标签（供欢迎语展示，使用真实发行版名）。 */
    public static String terminalModeLabel(Context ctx) {
        if (!isEnvUsable(ctx)) {
            return "Android Shell (/system/bin/sh)";
        }
        String distro = EnvStore.getDistro(ctx);
        if (distro.isEmpty()) {
            distro = detectDistro(ctx);
        }
        return distro.isEmpty() ? "TERX Linux" : distro;
    }

    // ------------------------------------------------------------ 环境安装

    /** 安装进度回调。 */
    public interface InstallProgress {
        void onStage(String stage, long bytes, long total, int entries);
    }

    /** 安装结果。 */
    public static final class InstallResult {
        public boolean ok;
        public String envName = "";
        public String distro = "";
        public String error = "";
        public int entries;
        public long bytes;

        public String summary() {
            if (ok) {
                return "环境安装完成：" + distro
                        + "（" + entries + " 条目，" + (bytes / 1024 / 1024) + " MB）";
            }
            return "环境安装失败：" + error;
        }
    }

    /**
     * 从输入流安装环境（rootfs 包）。
     *
     * <h3>流程</h3>
     * <pre>
     * 1. 解压到 App 私有 cache（app 身份可写）
     * 2. 通过 Shizuku 用管道把结果搬到 /data/local/tmp/terx/envs/&lt;name&gt;/
     * 3. 校验、探测发行版、写 resolv.conf
     * 4. 登记环境并标记已安装
     * </pre>
     *
     * <p><b>为什么不能直接解压到目标位置</b>：目标是 {@code /data/local/tmp}，
     * app 身份对该目录<b>只读</b>（SELinux shell_data_file），必须经 Shizuku。
     *
     * <p>注意：必须在后台线程调用。
     *
     * @param in        包输入流（调用方负责关闭）
     * @param fileName  包文件名（推断压缩格式）
     * @param envName   环境名（目录名，如 "alpine"）
     * @param progress  进度回调，可为 null
     */
    public static InstallResult install(Context ctx, java.io.InputStream in,
                                        String fileName, String envName,
                                        InstallProgress progress) {
        InstallResult r = new InstallResult();
        final String name = (envName == null || envName.isEmpty())
                ? EnvStore.DEFAULT_ENV : envName;
        File cacheStage = new File(ctx.getCacheDir(), "terx_stage");
        try {
            // 前置：Shizuku 必须可用（写入 /data/local/tmp 的唯一通道）
            if (!ShizukuFs.available()) {
                r.error = "需要 Shizuku 才能安装环境："
                        + (ShizukuFs.getLastError().isEmpty()
                            ? "请在控制面板授权 Shizuku" : ShizukuFs.getLastError());
                LogStore.getInstance().error(TAG, r.error);
                return r;
            }
            if (!ShizukuFs.ensureEnvRoot()) {
                r.error = "无法创建环境根目录：" + ShizukuFs.getLastError();
                LogStore.getInstance().error(TAG, r.error);
                return r;
            }

            // 1) 解压到 cache（app 身份可写）
            if (progress != null) progress.onStage("解压中", 0, 0, 0);
            LogStore.getInstance().info(TAG, "解压到临时目录: " + cacheStage);
            RootfsExtractor.Result er = RootfsExtractor.extract(
                    in, fileName, cacheStage, (bytes, total, entries) -> {
                        if (progress != null) progress.onStage("解压中", bytes, total, entries);
                    });
            if (!er.ok) {
                r.error = er.error;
                LogStore.getInstance().error(TAG, "解压失败: " + er.error);
                deleteQuietly(cacheStage);
                return r;
            }
            r.entries = er.entries;
            r.bytes = er.bytes;

            // 2) 通过 Shizuku 搬运到 /data/local/tmp
            // 注意：目标是 rootfsDir（envs/<name>/rootfs），不是 envDir ——
            // 因为压缩包解开后内容直接在顶层（如 etc/、bin/），需要落在 rootfs/ 之内，
            // 否则 isRootfsValid 检查 <env>/rootfs/etc 会失败。
            if (progress != null) progress.onStage("部署中", 0, 0, 0);
            String dest = EnvStore.rootfsDir(name).getAbsolutePath();
            // 先清理同名旧环境（整个 envDir，避免残留其它文件）
            ShizukuFs.deleteRecursive(EnvStore.envDir(name).getAbsolutePath());
            if (!ShizukuFs.pipeCopyDir(cacheStage, dest)) {
                r.error = "部署到 " + dest + " 失败（Shizuku 管道中断）";
                LogStore.getInstance().error(TAG, r.error);
                ShizukuFs.deleteRecursive(EnvStore.envDir(name).getAbsolutePath());
                deleteQuietly(cacheStage);
                return r;
            }
            deleteQuietly(cacheStage);

            // 3) 校验与元信息
            if (progress != null) progress.onStage("校验中", 0, 0, 0);
            File rootfs = EnvStore.rootfsDir(name);
            if (!isRootfsValid(rootfs)) {
                r.error = "部署结果不是有效的 rootfs（缺 etc 或 shell）";
                LogStore.getInstance().error(TAG, r.error + " | " + rootfs);
                ShizukuFs.deleteRecursive(EnvStore.envDir(name).getAbsolutePath());
                return r;
            }
            r.distro = detectDistro(EnvStore.rootfsDir(name));
            writeResolvConf(name);

            EnvStore.registerEnv(ctx, name);
            EnvStore.setDistro(ctx, name, r.distro);
            EnvStore.setSourceName(ctx, name, fileName);
            EnvStore.setInstalled(ctx, name, true);
            EnvStore.setActiveEnv(ctx, name);

            r.ok = true;
            r.envName = name;
            LogStore.getInstance().info(TAG, r.summary());
        } catch (Throwable t) {
            r.ok = false;
            r.error = t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage());
            LogStore.getInstance().error(TAG, "安装异常: " + t);
            deleteQuietly(cacheStage);
            ShizukuFs.deleteRecursive(EnvStore.envDir(name).getAbsolutePath());
        }
        return r;
    }

    /** 静默删除本地目录（app 身份可写的 cache）。 */
    private static void deleteQuietly(File f) {
        try {
            if (f != null && f.exists()) deleteRecursively(f);
        } catch (Throwable ignored) {}
    }

    /**
     * 安装失败回滚：删除环境目录，清除安装标记。
     *
     * <p>目的：避免留下"解压了一半"的残骸。
     * 实测教训——早期版本失败时不回滚，外置存储里残留了 12750 个条目（458MB），
     * 用户既不知情也无法通过界面清理。
     *
     * <p>不做断点续传的原因：源包在本地，重读很快；失败原因（文件系统限制、
     * 空间不足、包损坏）也不是"续传"能绕过的；部分解压的中间态本身不可信。
     */
    private static void rollback(Context ctx, File envDir, InstallResult r) {
        try {
            if (envDir.exists()) {
                deleteRecursively(envDir);
                boolean gone = !envDir.exists();
                LogStore.getInstance().info(TAG, "安装失败已回滚: " + envDir
                        + " 彻底=" + gone);
                if (!gone) {
                    r.error += "（注意：回滚未彻底，可能有残留）";
                }
            }
            EnvStore.setInstalled(ctx, false);
            EnvStore.setDistro(ctx, "");
            EnvStore.setSourceName(ctx, "");
        } catch (Throwable t) {
            LogStore.getInstance().error(TAG, "回滚异常: " + t);
            r.error += "（回滚异常：" + t.getMessage() + "）";
        }
    }

    /**
     * rootfs 是否有效（含 etc 目录与至少一个 shell）。
     *
     * <p><b>关键</b>：判断条目存在性必须用 {@code lstat}，不能用 {@code File.exists()}。
     * 原因：Alpine 的 {@code /bin/sh -> /bin/busybox} 是<b>绝对路径软链</b>，
     * 在宿主上看该目标不存在（宿主 /bin 里没有 busybox），
     * {@code exists()} 会跟随软链并返回 false，导致 Alpine 被误判为无效 rootfs。
     */
    public static boolean isRootfsValid(Context ctx) {
        return isRootfsValid(EnvStore.rootfsDir(ctx));
    }

    /**
     * 指定 rootfs 目录是否有效。
     *
     * <p><b>为何用「目录」而非「软链」判断</b>：实测发现，App（{@code untrusted_app} 域）
     * 对 {@code /data/local/tmp}（SELinux {@code shell_data_file}）下的
     * <b>目录可以 lstat（mode=40777 OK），但软链会被拒绝（EACCES）</b>。
     * 而 Alpine 的 {@code /bin/sh}、{@code /bin/ash} 全是软链，
     * 若按软链判断，App 侧永远判定为"无 shell"，校验必然失败。
     *
     * <p>因此改为判断<b>目录存在性</b>（App 可访问），
     * 具体 shell 由 {@link #pickShell} 在 shell 身份下探测。
     */
    public static boolean isRootfsValid(File rootfs) {
        if (rootfs == null) return false;
        // /etc 是各发行版必备目录
        if (!entryExists(new File(rootfs, "etc"))) return false;
        // shell 所在目录：bin 或 usr/bin 至少存在一个
        return entryExists(new File(rootfs, "bin"))
                || entryExists(new File(rootfs, "usr/bin"));
    }

    /** 判断条目本身是否存在（不跟随软链）。 */
    private static boolean entryExists(File f) {
        try {
            android.system.Os.lstat(f.getAbsolutePath());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 探测当前激活环境的发行版名称。 */
    public static String detectDistro(Context ctx) {
        return detectDistro(EnvStore.rootfsDir(ctx));
    }

    /** 探测指定 rootfs 的发行版名称。 */
    public static String detectDistro(File rootfs) {
        if (rootfs == null) return "未知发行版";
        // 注意：Alpine 的 /etc/os-release 可能是软链，用 lstat 判断存在性
        File osr = new File(rootfs, "etc/os-release");
        if (entryExists(osr)) {
            String v = firstLineWithPrefix(osr, "PRETTY_NAME=");
            if (v != null) return v.replace("\"", "").trim();
        }
        File ar = new File(rootfs, "etc/alpine-release");
        if (entryExists(ar)) {
            String v = firstLineWithPrefix(ar, null);
            if (v != null && !v.isEmpty()) return "Alpine " + v.trim();
        }
        File dv = new File(rootfs, "etc/debian_version");
        if (entryExists(dv)) {
            String v = firstLineWithPrefix(dv, null);
            if (v != null && !v.isEmpty()) return "Debian " + v.trim();
        }
        return "未知发行版";
    }

    /** 读文件：prefix 非空时找以它开头的行并返回其后内容；否则返回首行。 */
    private static String firstLineWithPrefix(File f, String prefix) {
        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(f)))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (prefix == null) return line;
                if (line.startsWith(prefix)) return line.substring(prefix.length());
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * 写 resolv.conf（DNS 兜底，阶段 0 实测必需）。
     *
     * <p>目标在 {@code /data/local/tmp}，app 身份写不了，必须走 Shizuku。
     */
    public static void writeResolvConf(String envName) {
        String path = EnvStore.resolvConf(envName).getAbsolutePath();
        String content = "nameserver 223.5.5.5\n"
                + "nameserver 8.8.8.8\n"
                + "nameserver 1.1.1.1\n";
        if (!ShizukuFs.writeText(path, content)) {
            LogStore.getInstance().warn(TAG, "写 resolv.conf 失败: " + path);
        }
    }

    /** 兼容旧调用：写当前激活环境的 resolv.conf。 */
    public static void writeResolvConf(Context ctx) {
        writeResolvConf(EnvStore.getActiveEnv(ctx));
    }

    /**
     * 卸载环境（删除环境目录）。
     *
     * <p>目录在 {@code /data/local/tmp}，app 身份删不掉，必须走 Shizuku。
     *
     * @return 删除是否彻底（目录已不存在）
     */
    public static boolean uninstall(Context ctx) {
        String name = EnvStore.getActiveEnv(ctx);
        return uninstall(ctx, name);
    }

    /** 卸载指定环境。 */
    public static boolean uninstall(Context ctx, String envName) {
        String dir = EnvStore.envDir(envName).getAbsolutePath();
        boolean gone = ShizukuFs.deleteRecursive(dir);
        if (gone) {
            EnvStore.unregisterEnv(ctx, envName);
            LogStore.getInstance().info(TAG, "环境已卸载: " + dir + " env=" + envName);
        } else {
            LogStore.getInstance().error(TAG, "卸载失败（Shizuku 删除未彻底）: " + dir);
        }
        return gone;
    }

    /**
     * 递归删除目录树。
     *
     * <p><b>关键</b>：必须用 {@code Os.lstat} 判断类型，不能用 {@code File.listFiles()} + {@code isDirectory()}。
     * 原因：rootfs 内含大量<b>悬空软链</b>（如 {@code etc/alternatives/awk -> /usr/bin/mawk}），
     * {@code listFiles()} 遇到悬空链会返回 null，导致递归中断、父目录因非空而删不掉
     * （实测残留 1217 个条目，其中 1004 个软链）。
     *
     * <p>正确做法：软链一律直接 {@code unlink}，绝不递归进入。
     */
    private static boolean deleteRecursively(File f) {
        if (f == null) return true;
        try {
            android.system.StructStat st = android.system.Os.lstat(f.getAbsolutePath());
            int type = st.st_mode & android.system.OsConstants.S_IFMT;
            if (type == android.system.OsConstants.S_IFLNK) {
                // 软链：直接删链，不跟随、不递归
                return f.delete() || !f.exists();
            }
            if (type != android.system.OsConstants.S_IFDIR) {
                return f.delete() || !f.exists();
            }
        } catch (Throwable t) {
            // lstat 失败（不存在或权限）：若已不存在视为成功
            if (!f.exists()) return true;
        }

        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursively(c);
            }
        }
        boolean ok = f.delete();
        if (!ok && f.exists()) {
            // 二次尝试：逐项清理后再删
            File[] rest = f.listFiles();
            if (rest != null) {
                for (File c : rest) {
                    c.delete();
                }
            }
            ok = f.delete();
        }
        return ok || !f.exists();
    }

    // ------------------------------------------------------------ 状态

    /** 环境状态快照（供界面展示）。 */
    public static final class Status {
        public boolean prootReady;
        public String prootVersion = "";
        public String envName = "";
        public boolean envInstalled;
        public String distro = "";
        public String location = "";
        public String locationLabel = "";
        public String rootfsPath = "";
        public long sizeBytes;

        public String sizeText() {
            if (sizeBytes <= 0) return "0 B";
            if (sizeBytes < 1024) return sizeBytes + " B";
            if (sizeBytes < 1024 * 1024) return (sizeBytes / 1024) + " KB";
            if (sizeBytes < 1024L * 1024 * 1024) {
                return String.format(java.util.Locale.US, "%.1f MB",
                        sizeBytes / 1024.0 / 1024.0);
            }
            return String.format(java.util.Locale.US, "%.2f GB",
                    sizeBytes / 1024.0 / 1024.0 / 1024.0);
        }
    }

    /** 采集当前状态（涉及目录遍历，建议后台线程）。 */
    public static Status status(Context ctx) {
        Status s = new Status();
        s.prootReady = isProotReady(ctx);
        s.prootVersion = prootBin(ctx).isFile() ? "5.1.107.95" : "";
        s.envInstalled = EnvStore.isInstalled(ctx) && isRootfsValid(ctx);
        s.distro = EnvStore.getDistro(ctx);
        s.location = EnvStore.getLocation(ctx);
        s.locationLabel = EnvStore.locationLabel(s.location);
        s.rootfsPath = EnvStore.rootfsDir(ctx).getAbsolutePath();
        s.sizeBytes = s.envInstalled ? envSize(ctx) : 0L;
        s.envName = EnvStore.getActiveEnv(ctx);
        return s;
    }

    /**
     * 环境占用空间（字节）。
     *
     * <p>注意：环境在 {@code /data/local/tmp}，app 身份可读，
     * 因此可直接遍历统计（无需走 Shizuku）。
     * 遍历用 {@code lstat} 判断类型，软链不递归（否则 {@code /bin -> usr/bin} 会被重复计算）。
     */
    public static long envSize(Context ctx) {
        return envSizeOf(ctx, EnvStore.getActiveEnv(ctx));
    }

    /**
     * 按环境名统计占用（各自独立，互不影响）。
     *
     * <p>遍历用 lstat 判断类型，软链不递归（否则 {@code /bin -> usr/bin} 会被重复计算）。
     */
    public static long envSizeOf(Context ctx, String envName) {
        File d = EnvStore.envDir(envName);
        if (!d.isDirectory()) return 0L;
        long[] total = {0L};
        walkSize(d, total);
        return total[0];
    }

    private static void walkSize(File f, long[] acc) {
        File[] children = f.listFiles();
        if (children == null) return;
        for (File c : children) {
            try {
                android.system.StructStat st = android.system.Os.lstat(c.getAbsolutePath());
                int type = st.st_mode & android.system.OsConstants.S_IFMT;
                if (type == android.system.OsConstants.S_IFLNK) continue;
                if (type == android.system.OsConstants.S_IFDIR) {
                    walkSize(c, acc);
                } else {
                    acc[0] += st.st_size;
                }
            } catch (Throwable t) {
                // 权限/竞态：跳过
            }
        }
    }
}
