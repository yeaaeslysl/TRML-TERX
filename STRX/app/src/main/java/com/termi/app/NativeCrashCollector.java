package com.termi.app;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.FileObserver;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Native 崩溃 / ANR 采集器。
 *
 * <p>不依赖任何第三方 native crash 库：通过监视系统 tombstone 目录
 * （/data/tombstones，需要 root/Shizuku 或本应用为 debuggable 才可读），
 * 以及 /data/anr/traces.txt，在文件产生/变化时把内容写入
 * {@link LogStore} 的 crash 目录，作为 native 崩溃现场的证据。
 *
 * <p>同时使用 {@link FileObserver} 监听应用自身 files/crash 目录，
 * 兜底采集系统 debuggerd 因权限无法读取时的降级信息。
 */
public final class NativeCrashCollector {

    private static final String TAG = "NativeCrashCollector";

    private static final String[] TOMBSTONE_DIRS = {
            "/data/tombstones",
            "/data/tombstones/",
    };

    private static final String ANR_TRACES = "/data/anr/traces.txt";
    private static final String ANR_DIR = "/data/anr";

    private static volatile boolean installed = false;

    /**
     * 单线程 IO 执行器：FileObserver.onEvent 与 scanExistingTombstones
     * 里的读文件/写 crash 目录都放到这个线程，避免阻塞回调线程或主线程。
     */
    private static final ExecutorService sIoExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "NativeCrash-io");
        t.setDaemon(true);
        return t;
    });

    private NativeCrashCollector() {
    }

    public static synchronized void install(Application app) {
        if (installed) {
            return;
        }
        installed = true;

        // 1) 先把当前已存在的 tombstone 采集一次，覆盖“安装前已崩溃”的情况
        scanExistingTombstones();

        // 2) 监听 tombstone 目录，崩溃后 debuggerd 落盘时抓取
        watchDir("/data/tombstones", "tombstone");

        // 3) 监听 ANR traces
        watchFile(ANR_TRACES, "anr-traces");

        // 注意：绝不能监听应用自身的 crash 目录。
        // captureFile() 会调用 dumpToCrashDir() 往同一目录写文件，
        // 若该目录被 FileObserver 监听，会形成「写文件 -> 触发事件 -> 再写文件」
        // 的无限自我繁殖（实测每 0.1s 生成一个文件，直到 IO 打满触发 ANR）。
    }

    private static void scanExistingTombstones() {
        for (String dirPath : TOMBSTONE_DIRS) {
            File dir = new File(dirPath);
            if (!dir.isDirectory()) {
                continue;
            }
            File[] files = dir.listFiles();
            if (files == null) {
                continue;
            }
            for (File f : files) {
                if (f.isFile() && f.getName().startsWith("tombstone")) {
                    captureFile(f, "tombstone-existing");
                }
            }
        }
        captureFile(new File(ANR_TRACES), "anr-traces-existing");
    }

    private static void watchDir(String path, String tag) {
        File dir = new File(path);
        // FileObserver 对无权限/不存在的目录不会抛异常，只会在收到事件后才失败；
        // 这里先做一次可读性预检，避免对 /data/tombstones 之类不可读目录
        // 注册 observer，导致每次文件变动都触发一次失败的 IO。
        if (!dir.isDirectory() || !dir.canRead()) {
            LogStore.getInstance().append(LogStore.LEVEL_INFO, TAG,
                    "skip watch " + path + " (" + tag + ", not readable)");
            return;
        }
        try {
            FileObserver observer = new FileObserver(path, FileObserver.CREATE
                    | FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO) {
                @Override
                public void onEvent(int event, String fileName) {
                    if (fileName == null) {
                        return;
                    }
                    final File f = new File(path, fileName);
                    // onEvent 在 FileObserver 自己的线程回调；读文件与写日志
                    // 都必须离开该线程，避免阻塞后续事件、也避免与主线程竞争。
                    sIoExecutor.execute(() -> captureFile(f, tag));
                }
            };
            observer.startWatching();
            LogStore.getInstance().append(LogStore.LEVEL_INFO, TAG,
                    "watching " + path + " (" + tag + ")");
        } catch (Throwable t) {
            LogStore.getInstance().append(LogStore.LEVEL_WARN, TAG,
                    "cannot watch " + path + ": " + t.getMessage());
        }
    }

    private static void watchFile(String path, String tag) {
        File f = new File(path);
        File parent = f.getParentFile();
        if (parent != null && parent.isDirectory()) {
            watchDir(parent.getAbsolutePath(), tag);
        } else {
            LogStore.getInstance().append(LogStore.LEVEL_INFO, TAG,
                    "skip watch " + path + " (no permission or not exist)");
        }
    }

    private static void captureFile(File f, String tag) {
        if (f == null || !f.isFile() || !f.canRead()) {
            return;
        }
        // 只采集近期变化的文件，避免重复灌入历史数据
        long age = System.currentTimeMillis() - f.lastModified();
        if (age > 5 * 60 * 1000L) {
            return;
        }
        String content = readAll(f);
        if (content == null || content.isEmpty()) {
            return;
        }
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date());
        String header = "===== " + tag + " " + f.getName() + " @ " + stamp
                + " (device=" + Build.MANUFACTURER + " " + Build.MODEL
                + ", api=" + Build.VERSION.SDK_INT + ") =====\n";
        LogStore.getInstance().append(LogStore.LEVEL_ERROR, "NATIVE", header + content);
        LogStore.getInstance().flush();
        LogStore.getInstance().dumpToCrashDir(tag, header + content);
    }

    private static String readAll(File f) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(f));
            StringBuilder sb = new StringBuilder();
            String line;
            int limit = 20000;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
                if (sb.length() > limit) {
                    sb.append("... [truncated]\n");
                    break;
                }
            }
            return sb.toString();
        } catch (IOException e) {
            Log.w(TAG, "read failed: " + f, e);
            return null;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
