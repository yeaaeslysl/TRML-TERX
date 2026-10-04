package com.termi.app;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 启动存活探针：在 Application.onCreate 最早期安装，
 * 覆盖 ContentProvider 之后、Application 初始化完成之前的崩溃盲区。
 *
 * <p>工作原理：
 * <ol>
 *   <li>install() 时立即写入 .start 标记文件（同步落盘）；</li>
 *   <li>markAlive() 在 Application 初始化成功后写入 .alive 标记；</li>
 *   <li>下次启动时若发现只有 .start 没有 .alive，说明上次是启动期崩的；</li>
 *   <li>同时注册一个极简 UncaughtExceptionHandler，不依赖 LogStore，
 *       直接把堆栈同步写到 crash/startup-*.log，确保即使 LogStore 没初始化也能留下遗言。</li>
 * </ol>
 *
 * <p>注意：本类必须在 TermiApp.onCreate 的第一行调用 install()，
 * 且不能依赖任何其它业务类（包括 LogStore），否则可能因依赖未就绪而二次崩溃。
 */
public final class StartupProbe {

    private static final String TAG = "StartupProbe";
    private static final String START_FILE = ".startup_probe_start";
    private static final String ALIVE_FILE = ".startup_probe_alive";
    private static final String CRASH_DIR = "crash";

    private static volatile boolean sInstalled = false;
    private static File sCrashDir;

    private StartupProbe() {}

    /**
     * 在 Application.onCreate 第一行调用。
     * 写 .start 标记 + 安装兜底异常处理器。
     */
    public static void install(Context context) {
        if (context == null || sInstalled) return;
        synchronized (StartupProbe.class) {
            if (sInstalled) return;
            sInstalled = true;

            Context app = context.getApplicationContext();
            File ext = app.getExternalFilesDir(null);
            if (ext != null) {
                sCrashDir = new File(ext, CRASH_DIR);
                if (!sCrashDir.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    sCrashDir.mkdirs();
                }
                // 1. 写 .start 标记（同步）
                writeMarker(new File(sCrashDir, START_FILE));
                // 2. 检查上次是否启动期崩溃
                File aliveFile = new File(sCrashDir, ALIVE_FILE);
                if (!aliveFile.exists()) {
                    // 上次没活过来，记录一次启动期崩溃提示
                    writeStartupCrashHint("Previous startup did not reach markAlive(). "
                            + "Check crash/startup-*.log or logcat for details.");
                } else {
                    // 上次正常活过，清掉旧标记重新开始本轮探测
                    //noinspection ResultOfMethodCallIgnored
                    aliveFile.delete();
                }
            }

            // 3. 安装兜底 UncaughtExceptionHandler（不依赖 LogStore）
            final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
                try {
                    writeStartupCrash(buildReport(thread, ex));
                } catch (Throwable ignored) {
                    // 兜底处理器绝不能再抛
                }
                if (prev != null) {
                    prev.uncaughtException(thread, ex);
                } else {
                    android.os.Process.killProcess(android.os.Process.myPid());
                    System.exit(1);
                }
            });

            Log.i(TAG, "StartupProbe installed");
        }
    }

    /**
     * 在 Application 初始化全部完成后调用，标记本次启动成功存活。
     */
    public static void markAlive() {
        if (sCrashDir == null) return;
        File alive = new File(sCrashDir, ALIVE_FILE);
        writeMarker(alive);
        Log.i(TAG, "StartupProbe markAlive written");
    }

    // ==================== 内部工具方法（全部同步、零外部依赖）====================

    private static void writeMarker(File file) {
        try (PrintWriter pw = new PrintWriter(new FileWriter(file, false))) {
            pw.write(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                    .format(new Date()));
            pw.flush();
        } catch (Throwable t) {
            Log.e(TAG, "writeMarker failed: " + file, t);
        }
    }

    private static void writeStartupCrash(String report) {
        if (sCrashDir == null) return;
        String name = "startup-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                .format(new Date()) + ".log";
        File out = new File(sCrashDir, name);
        try (PrintWriter pw = new PrintWriter(new FileWriter(out, false))) {
            pw.write("[ERROR][STARTUP] ");
            pw.write(report);
            pw.flush();
        } catch (Throwable t) {
            Log.e(TAG, "writeStartupCrash failed", t);
        }
    }

    private static void writeStartupCrashHint(String hint) {
        if (sCrashDir == null) return;
        String name = "startup-hint-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                .format(new Date()) + ".log";
        File out = new File(sCrashDir, name);
        try (PrintWriter pw = new PrintWriter(new FileWriter(out, false))) {
            pw.write("[WARN][STARTUP] ");
            pw.write(hint);
            pw.flush();
        } catch (Throwable t) {
            Log.e(TAG, "writeStartupCrashHint failed", t);
        }
    }

    private static String buildReport(Thread thread, Throwable ex) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("===== STARTUP FATAL EXCEPTION =====\n");
        sb.append("time: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                .format(new Date())).append('\n');
        sb.append("thread: ").append(thread != null ? thread.getName() : "?").append('\n');
        sb.append("pid: ").append(android.os.Process.myPid())
                .append(", tid: ").append(android.os.Process.myTid()).append('\n');
        sb.append("----- throwable -----\n");
        java.io.StringWriter sw = new java.io.StringWriter();
        java.io.PrintWriter pw = new java.io.PrintWriter(sw);
        ex.printStackTrace(pw);
        pw.flush();
        sb.append(sw.toString());
        return sb.toString();
    }
}
