package com.termi.app;

import android.app.Application;

/**
 * 应用入口：在进程最早阶段安装全局崩溃捕获。
 *
 * <p>必须在 AndroidManifest.xml 的 &lt;application&gt; 上通过
 * {@code android:name=".TermiApp"} 注册，否则本类不会被加载。
 */
public class TermiApp extends Application {

    private static final String TAG = "TermiApp";

    @Override
    public void onCreate() {
        // 【第一行】启动存活探针：不依赖任何业务类，覆盖 Provider → Application 之间的崩溃盲区
        StartupProbe.install(this);

        super.onCreate();

        // 初始化日志存储（创建目录、加载内存缓冲）
        try {
            LogStore.getInstance().init(this);
        } catch (Throwable t) {
            android.util.Log.e(TAG, "LogStore init failed", t);
        }

        // 安装 Java 未捕获异常捕获器（已含兜底同步写入）
        CrashHandler.install(this);

        // 安装 native 崩溃 / ANR 采集：读取系统 tombstone 与 traces，
        // 写入 LogStore 的 crash 目录，配合 Java 异常形成完整现场。
        try {
            NativeCrashCollector.install(this);
            LogStore.getInstance().append(LogStore.LEVEL_INFO, TAG, "NativeCrashCollector installed");
        } catch (Throwable t) {
            android.util.Log.e(TAG, "NativeCrashCollector install failed", t);
        }

        // 全部初始化完成，标记本次启动存活
        StartupProbe.markAlive();
    }
}
