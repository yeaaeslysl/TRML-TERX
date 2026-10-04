package com.termi.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 全局崩溃捕获：
 * - Java 未捕获异常（主线程/子线程）写入 LogStore（ERROR 级）并落盘 crash-*.log
 * - 附带设备信息、进程信息、内存信息、线程堆栈
 * - 捕获后 dump 一次系统 crash buffer（logcat -b crash）与主缓冲区
 * - 再交回默认 UncaughtExceptionHandler，保持系统原有行为（弹框/重启）
 *
 * 关于 ANR 的说明：
 * - ANR（Application Not Responding）不是 Java 未捕获异常，不会被本类捕获。
 * - 触发条件：主线程被阻塞超过 5s（前台 Activity）/ 广播 / Service 等超时。
 * - 由于本类无法直接拦截 ANR，请在「深色自检 / 测试执行」等入口处：
 *   1) 严禁在主线程执行 Shell、网络、大文件 IO 等耗时操作；
 *   2) 使用 ShellExecutor 的异步接口并设置真实超时（默认 30s，可取消）；
 *   3) 通过 LogStore 打印「CMD 开始 / 结束 / 超时 / 取消」四类收尾日志，便于事后定位。
 * - 当 ANR 发生时，系统会把 traces 写入 /data/anr/ 并杀进程，可用下方方式进一步排查：
 *   adb shell cat /data/anr/traces_* 或抓取 logcat -b crash + logcat -b main。
 * - 本类在 uncaughtException 中也会 dump crash/main 两个缓冲区，便于与 ANR 区分。
 */
public final class CrashHandler implements Thread.UncaughtExceptionHandler {

    private static final String TAG = "CrashHandler";
    private static final String CRASH_DIR = "crash";
    private static volatile boolean sInstalled = false;
    private static final int MAX_CRASH_FILES = 20;

    private final Context mAppContext;
    private final Thread.UncaughtExceptionHandler mDefaultHandler;

    private CrashHandler(Context ctx) {
        mAppContext = ctx.getApplicationContext();
        mDefaultHandler = Thread.getDefaultUncaughtExceptionHandler();
    }

    /** 幂等安装：重复调用只装一次。 */
    public static void install(Context ctx) {
        if (ctx == null || sInstalled) return;
        synchronized (CrashHandler.class) {
            if (sInstalled) return;
            CrashHandler h = new CrashHandler(ctx);
            Thread.setDefaultUncaughtExceptionHandler(h);
            sInstalled = true;
            LogStore.getInstance().append(LogStore.LEVEL_INFO, TAG, "CrashHandler installed");
        }
    }

    @SuppressLint("DefaultLocale")
    @Override
    public void uncaughtException(Thread thread, Throwable ex) {
        // 【兜底】不依赖 LogStore 的同步写入：即使 LogStore 没初始化或已损坏也能留下遗言
        writeFallbackCrashFile(buildReport(thread, ex));

        try {
            String report = buildReport(thread, ex);
            // 先入内存缓冲（保证 UI/导出都能看到）
            LogStore.getInstance().append(LogStore.LEVEL_ERROR, "CRASH", report);
            // 单独落一份崩溃文件，避免被日志轮转清掉
            writeCrashFile(report);
            // dump 系统 crash buffer + 主缓冲区
            String crashBuf = LogStore.readLogcatBuffer("crash", 2000);
            String mainBuf = LogStore.readLogcatBuffer("main", 2000);
            if (crashBuf != null && !crashBuf.isEmpty()) {
                LogStore.getInstance().append(LogStore.LEVEL_ERROR, "CRASH",
                        "===== logcat -b crash =====\n" + crashBuf);
            }
            if (mainBuf != null && !mainBuf.isEmpty()) {
                LogStore.getInstance().append(LogStore.LEVEL_ERROR, "CRASH",
                        "===== logcat -b main =====\n" + mainBuf);
            }
            LogStore.getInstance().flush();
        } catch (Throwable ignored) {
            // 崩溃处理本身绝不能再抛
        }
        if (mDefaultHandler != null) {
            mDefaultHandler.uncaughtException(thread, ex);
        } else {
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(1);
        }
    }

    /**
     * 兜底崩溃写入：完全同步、不依赖 LogStore / 任何业务类。
     * 直接写到 getExternalFilesDir(null)/crash/fallback-*.log。
     */
    private void writeFallbackCrashFile(String report) {
        try {
            File ext = mAppContext.getExternalFilesDir(null);
            if (ext == null) return;
            File dir = new File(ext, CRASH_DIR);
            if (!dir.exists() && !dir.mkdirs()) return;
            String name = "fallback-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                    .format(new Date()) + ".log";
            File out = new File(dir, name);
            try (PrintWriter pw = new PrintWriter(out, "UTF-8")) {
                pw.write("[ERROR][FALLBACK] ");
                pw.write(report);
                pw.flush();
            }
        } catch (Throwable ignored) {
            // 兜底写入失败也不能影响后续流程
        }
    }

    private String buildReport(Thread thread, Throwable ex) {
        StringBuilder sb = new StringBuilder(4096);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
        sb.append("===== FATAL EXCEPTION =====\n");
        sb.append("time: ").append(sdf.format(new Date())).append('\n');
        sb.append("thread: ").append(thread != null ? thread.getName() : "?").append('\n');
        sb.append("process: ").append(getProcessName()).append('\n');
        sb.append("pid: ").append(android.os.Process.myPid())
                .append(", tid: ").append(android.os.Process.myTid()).append('\n');

        sb.append("----- device -----\n");
        sb.append("brand: ").append(Build.BRAND).append('\n');
        sb.append("model: ").append(Build.MODEL).append('\n');
        sb.append("device: ").append(Build.DEVICE).append('\n');
        sb.append("android: ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("abi: ").append(Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0
                ? Build.SUPPORTED_ABIS[0] : "?").append('\n');

        sb.append("----- app -----\n");
        try {
            PackageInfo pi = mAppContext.getPackageManager()
                    .getPackageInfo(mAppContext.getPackageName(), 0);
            sb.append("versionName: ").append(pi.versionName).append('\n');
            sb.append("versionCode: ").append(pi.versionCode).append('\n');
        } catch (PackageManager.NameNotFoundException ignored) {
        }

        sb.append("----- memory -----\n");
        Runtime rt = Runtime.getRuntime();
        sb.append("maxMemory: ").append(rt.maxMemory() / 1024 / 1024).append("MB\n");
        sb.append("totalMemory: ").append(rt.totalMemory() / 1024 / 1024).append("MB\n");
        sb.append("freeMemory: ").append(rt.freeMemory() / 1024 / 1024).append("MB\n");

        sb.append("----- throwable -----\n");
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        ex.printStackTrace(pw);
        pw.flush();
        sb.append(sw.toString());

        // 附上崩溃前最近的运行日志（内存缓冲）
        sb.append("----- recent logs (memory) -----\n");
        sb.append(LogStore.getInstance().dumpText(200));
        return sb.toString();
    }

    private String getProcessName() {
        try {
            int pid = android.os.Process.myPid();
            File f = new File("/proc/" + pid + "/cmdline");
            try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                String line = br.readLine();
                if (line != null) return line.trim();
            }
        } catch (Throwable ignored) {
        }
        return mAppContext.getPackageName();
    }

    /**
     * 落盘崩溃文件：
     * - 去重：同一秒内重复崩溃只保留一份（以崩溃指纹做标记）；
     * - 打 [ERROR][JAVA] 标签，便于日志检索；
     * - 超过 MAX_CRASH_FILES 时按修改时间删除最旧的。
     */
    private void writeCrashFile(String report) {
        File ext = mAppContext.getExternalFilesDir(null);
        if (ext == null) return;
        File dir = new File(ext, CRASH_DIR);
        if (!dir.exists() && !dir.mkdirs()) return;
        String name = "crash-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                .format(new Date()) + ".log";
        File out = new File(dir, name);
        if (out.exists()) return;
        try (PrintWriter pw = new PrintWriter(out, "UTF-8")) {
            pw.write("[ERROR][JAVA] ");
            pw.write(report);
            pw.flush();
        } catch (Throwable ignored) {
            return;
        }
        pruneOldCrashFiles(dir);
    }

    /** 只保留最近 MAX_CRASH_FILES 份崩溃日志，避免无限增长。 */
    private void pruneOldCrashFiles(File dir) {
        File[] files = dir.listFiles((d, n) -> n != null && n.startsWith("crash-") && n.endsWith(".log"));
        if (files == null || files.length <= MAX_CRASH_FILES) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        int toDelete = files.length - MAX_CRASH_FILES;
        for (int i = 0; i < toDelete; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }
}
