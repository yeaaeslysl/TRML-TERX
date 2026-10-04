package com.termi.app;

import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 命令执行器。
 *
 * 关键点：
 * 1. 所有命令都在后台线程执行，绝不阻塞调用线程（尤其是主线程）。
 * 2. 读取 stdout/stderr 使用独立线程，避免管道写满导致子进程卡死。
 * 3. 真实超时：到点后强制 destroy 进程，并标记 timedOut。
 * 4. 输出上限：超过 MAX_OUTPUT 字节后丢弃多余内容并标记 truncated。
 * 5. 四类收尾日志：成功 / 失败 / 超时 / 取消，均有明确结束日志。
 */
public final class ShellExecutor {

    private ShellExecutor() {}

    private static final String TAG = "ShellExecutor";
    private static final int MAX_OUTPUT = 64 * 1024; // 单流最多保留 64KB
    /** 需要读取二进制（base64）内容时使用的更大上限，如 view_image。 */
    public static final int MAX_OUTPUT_LARGE = 4 * 1024 * 1024;
    private static final int LOG_OUTPUT_LIMIT = 2000; // 单条收尾日志 stdout/stderr 上限
    private static final long KILL_GRACE_MS = 2000L; // destroyForcibly 后等待退出码的宽限期
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 特殊命令：轻量自检，只探测 Shizuku binder，不真正起进程。 */
    public static final String CMD_PROBE = "__probe__";

    public interface Callback {
        void onResult(Result r);
    }

    public static class Result {
        public final String stdout;
        public final String stderr;
        public final int exitCode;
        public final long costMs;
        public final boolean timedOut;
        public final boolean cancelled;
        public final boolean truncated;

        public Result(String stdout, String stderr, int exitCode, long costMs,
                      boolean timedOut, boolean cancelled, boolean truncated) {
            this.stdout = stdout;
            this.stderr = stderr;
            this.exitCode = exitCode;
            this.costMs = costMs;
            this.timedOut = timedOut;
            this.cancelled = cancelled;
            this.truncated = truncated;
        }

        public boolean isSuccess() {
            return !timedOut && !cancelled && exitCode == 0;
        }
    }

    /** 可取消句柄。 */
    public static class Handle {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private volatile Process process;

        void attach(Process p) { this.process = p; }

        public void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                Process p = process;
                if (p != null) {
                    try { p.destroyForcibly(); } catch (Throwable ignored) {
                        try { p.destroy(); } catch (Throwable ignored2) {}
                    }
                }
            }
        }

        public boolean isCancelled() { return cancelled.get(); }
    }

    public static boolean isShizukuReady() {
        try {
            return rikka.shizuku.Shizuku.pingBinder();
        } catch (Throwable t) {
            LogStore.getInstance().append("ERROR", TAG, "pingBinder failed: " + t);
            return false;
        }
    }

    public static boolean hasPermission() {
        if (!isShizukuReady()) return false;
        try {
            return rikka.shizuku.Shizuku.checkSelfPermission()
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 异步执行命令。回调运行在主线程。
     * 若 cmd == CMD_PROBE，则只做轻量探测，不创建进程。
     */
    public static Handle execAsync(String cmd, String cwd, long timeoutSec,
                                   boolean useRoot, Callback cb) {
        final Handle handle = new Handle();
        final long startMs = System.currentTimeMillis();
        final long timeoutMs = Math.max(1L, timeoutSec) * 1000L;

        LogStore.getInstance().append("INFO", TAG,
                "exec start cmd=" + cmd + " cwd=" + cwd + " useRoot=" + useRoot
                        + " timeoutSec=" + timeoutSec);

        Thread worker = new Thread(() -> {
            Result r = runInternal(cmd, cwd, timeoutMs, useRoot, handle, startMs);
            dispatch(cb, r);
        }, "shell-exec");
        worker.setDaemon(true);
        worker.start();
        return handle;
    }

    /** 同步便捷方法（仅供后台线程调用；不要在主线程用）。 */
    public static Result exec(String cmd, String cwd, long timeoutSec, boolean useRoot) {
        return exec(cmd, cwd, timeoutSec, useRoot, MAX_OUTPUT);
    }

    /** 同步执行，并指定单流输出上限（读取图片等大内容时用 MAX_OUTPUT_LARGE）。 */
    public static Result exec(String cmd, String cwd, long timeoutSec, boolean useRoot, int maxOutput) {
        Handle h = new Handle();
        long startMs = System.currentTimeMillis();
        long timeoutMs = Math.max(1L, timeoutSec) * 1000L;
        LogStore.getInstance().append("INFO", TAG,
                "exec(sync) start cmd=" + cmd + " timeoutSec=" + timeoutSec);
        return runInternal(cmd, cwd, timeoutMs, useRoot, h, startMs, maxOutput);
    }

    private static void dispatch(Callback cb, Result r) {
        if (cb == null) return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cb.onResult(r);
        } else {
            MAIN.post(() -> cb.onResult(r));
        }
    }

    private static Result runInternal(String cmd, String cwd, long timeoutMs,
                                      boolean useRoot, Handle handle, long startMs) {
        return runInternal(cmd, cwd, timeoutMs, useRoot, handle, startMs, MAX_OUTPUT);
    }

    private static Result runInternal(String cmd, String cwd, long timeoutMs,
                                      boolean useRoot, Handle handle, long startMs,
                                      int maxOutput) {
        try {
            return runInternalChecked(cmd, cwd, timeoutMs, useRoot, handle, startMs, maxOutput);
        } catch (Throwable t) {
            // 兜底：任何异常都不得逃逸到线程顶层，必须闭环成 exec fail。
            long cost = System.currentTimeMillis() - startMs;
            try {
                LogStore.getInstance().append("ERROR", TAG,
                        "exec fail (uncaught) costMs=" + cost + " ex=" + t);
            } catch (Throwable ignored) {}
            try { handle.cancel(); } catch (Throwable ignored) {}
            String msg = t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : (": " + t.getMessage()));
            return new Result("", msg, -1, cost, false, false, false);
        }
    }

    private static Result runInternalChecked(String cmd, String cwd, long timeoutMs,
                                             boolean useRoot, Handle handle, long startMs,
                                             int maxOutput) {
        // 安全黑名单检查
        String lowerCmd = cmd.toLowerCase();
        String[] blocked = {"--break-system-packages", "--break-system-package", "--no-preserve-root"};
        for (String b : blocked) {
            if (lowerCmd.contains(b)) {
                long cost = System.currentTimeMillis() - startMs;
                LogStore.getInstance().append("WARN", TAG, "exec blocked dangerous option: " + b);
                return new Result("", "ERROR: Blocked dangerous option '" + b + "'", -1, cost, false, false, false);
            }
        }

        // 轻量探测：不创建进程。
        if (CMD_PROBE.equals(cmd)) {
            boolean ready = isShizukuReady();
            boolean perm = ready && hasPermission();
            String out = "shizukuBinder=" + ready + ", permission=" + perm;
            long cost = System.currentTimeMillis() - startMs;
            LogStore.getInstance().append(ready ? "INFO" : "ERROR", TAG,
                    "probe done ready=" + ready + " perm=" + perm + " costMs=" + cost);
            return new Result(out, ready ? "" : "Shizuku not running", ready ? 0 : -1,
                    cost, false, false, false);
        }

        if (handle.isCancelled()) {
            long cost = System.currentTimeMillis() - startMs;
            LogStore.getInstance().append("WARN", TAG, "exec cancelled before start costMs=" + cost);
            return new Result("", "cancelled", -1, cost, false, true, false);
        }

        if (!isShizukuReady()) {
            long cost = System.currentTimeMillis() - startMs;
            LogStore.getInstance().append("ERROR", TAG, "Shizuku not running, cmd=" + cmd);
            return new Result("", "Shizuku not running", -1, cost, false, false, false);
        }

        String fullCmd = buildCommand(cmd, cwd, useRoot);

        Process proc;
        try {
            proc = newProcessCompat(new String[]{"sh", "-c", fullCmd}, null, null);
        } catch (Throwable t) {
            long cost = System.currentTimeMillis() - startMs;
            LogStore.getInstance().append("ERROR", TAG,
                    "newProcess failed: " + t + " cmd=" + cmd);
            return new Result("", "newProcess failed: " + t.getMessage(), -1, cost,
                    false, false, false);
        }
        handle.attach(proc);

        // 启动两个读线程，避免管道写满阻塞子进程。
        StreamReader outReader = new StreamReader(proc.getInputStream(), maxOutput);
        StreamReader errReader = new StreamReader(proc.getErrorStream(), maxOutput);
        Thread tOut = new Thread(outReader, "shell-stdout");
        Thread tErr = new Thread(errReader, "shell-stderr");
        tOut.setDaemon(true);
        tErr.setDaemon(true);
        tOut.start();
        tErr.start();

        boolean timedOut = false;
        boolean cancelled = false;
        int exitCode = -1;
        boolean exited = false;
        int[] exitHolder = new int[1];
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (true) {
            if (handle.isCancelled()) {
                cancelled = true;
                try { proc.destroyForcibly(); } catch (Throwable ignored) {
                    try { proc.destroy(); } catch (Throwable ignored2) {}
                }
                break;
            }
            if (System.currentTimeMillis() >= deadline) {
                timedOut = true;
                try { proc.destroyForcibly(); } catch (Throwable ignored) {
                    try { proc.destroy(); } catch (Throwable ignored2) {}
                }
                break;
            }
            if (tryExitValue(proc, exitHolder)) {
                exitCode = exitHolder[0];
                exited = true;
                break;
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cancelled = true;
                try { proc.destroyForcibly(); } catch (Throwable ignored) {}
                break;
            }
        }

        // 若因超时/取消/中断退出循环，给进程一个短暂宽限期，尝试拿到真实退出码。
        if (!exited) {
            long graceDeadline = System.currentTimeMillis() + KILL_GRACE_MS;
            while (System.currentTimeMillis() < graceDeadline) {
                if (tryExitValue(proc, exitHolder)) {
                    exitCode = exitHolder[0];
                    exited = true;
                    break;
                }
                try {
                    Thread.sleep(20L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        // 宽限期内仍拿不到退出码时，保持 exitCode = -1，绝不再调 exitValue()。

        // 等待读线程收尾（最多再等 1 秒）。
        joinQuietly(tOut, 1000L);
        joinQuietly(tErr, 1000L);

        String out = outReader.text();
        String err = errReader.text();
        boolean truncated = outReader.isTruncated() || errReader.isTruncated();

        long cost = System.currentTimeMillis() - startMs;
        logCompletion(exitCode, cost, timedOut, cancelled, truncated, out, err);

        return new Result(out, err, exitCode, cost, timedOut, cancelled, truncated);
    }

    private static void logCompletion(int exitCode, long cost, boolean timedOut,
                                      boolean cancelled, boolean truncated,
                                      String out, String err) {
        // 闭环保证：cancelled / timeout / success / fail 四选一，必落且含 costMs。
        if (cancelled) {
            LogStore.getInstance().append("WARN", TAG,
                    "exec cancelled exit=" + exitCode + " costMs=" + cost);
        } else if (timedOut) {
            LogStore.getInstance().append("ERROR", TAG,
                    "exec timeout exit=" + exitCode + " costMs=" + cost
                            + " stdout=" + truncate(out, 500)
                            + " stderr=" + truncate(err, 500));
        } else if (exitCode == 0) {
            LogStore.getInstance().append("INFO", TAG,
                    "exec success costMs=" + cost
                            + (truncated ? " (output truncated)" : "")
                            + " stdout=" + truncate(out, 500)
                            + " stderr=" + truncate(err, 500));
        } else {
            LogStore.getInstance().append("ERROR", TAG,
                    "exec fail exit=" + exitCode + " costMs=" + cost
                            + " stdout=" + truncate(out, 500)
                            + " stderr=" + truncate(err, 500));
        }
    }

    /**
     * 安全读取退出码：先判进程存活，再调 exitValue()，
     * 全程 try/catch(Throwable) 兜住 IllegalThreadStateException /
     * IllegalArgumentException("process hasn't exited") 等异常。
     * 成功时写入 out[0] 并返回 true。
     */
    private static boolean tryExitValue(Process proc, int[] out) {
        try {
            if (proc == null || proc.isAlive()) {
                return false;
            }
            out[0] = proc.exitValue();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void joinQuietly(Thread t, long ms) {
        try {
            t.join(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String buildCommand(String cmd, String cwd, boolean useRoot) {
        String prefix = useRoot ? "su -c " : "sh -c ";
        if (cwd == null || cwd.isEmpty()) {
            return prefix + shellQuote(cmd);
        }
        return prefix + shellQuote("cd " + shellQuote(cwd) + " && " + cmd);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...(truncated)";
    }

    /**
     * Shizuku 13.x 未公开 newProcess，这里通过反射调用隐藏的公开静态方法；
     * 运行期在 Shizuku 已授权时可用，未授权/调用失败时抛异常由上层兜底。
     */
    private static Process newProcessCompat(String[] cmd, String[] env, String dir) throws Exception {
        Method m = rikka.shizuku.Shizuku.class.getDeclaredMethod(
                "newProcess", String[].class, String[].class, String.class);
        m.setAccessible(true);
        return (Process) m.invoke(null, cmd, env, dir);
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** 后台读流线程：带上限（默认 64KB，读图片等大内容时用更大值）。 */
    private static final class StreamReader implements Runnable {
        private final InputStream in;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private final AtomicBoolean truncated = new AtomicBoolean(false);
        private final int maxOutput;

        StreamReader(InputStream in) { this(in, MAX_OUTPUT); }

        StreamReader(InputStream in, int maxOutput) {
            this.in = in;
            this.maxOutput = maxOutput > 0 ? maxOutput : MAX_OUTPUT;
        }

        @Override
        public void run() {
            byte[] tmp = new byte[4096];
            try {
                int n;
                while ((n = in.read(tmp)) > 0) {
                    if (buf.size() < maxOutput) {
                        int allow = Math.min(n, maxOutput - buf.size());
                        buf.write(tmp, 0, allow);
                        if (allow < n) truncated.set(true);
                    } else {
                        truncated.set(true);
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                try { in.close(); } catch (Throwable ignored) {}
            }
        }

        String text() {
            return new String(buf.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        }

        boolean isTruncated() { return truncated.get(); }
    }
}
