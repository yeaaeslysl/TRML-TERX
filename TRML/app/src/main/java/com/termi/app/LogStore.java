package com.termi.app;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 全局内存日志系统（增强版）。
 *
 * 能力：
 * - 日志分级：DEBUG / INFO / WARN / ERROR
 * - 内存环形缓冲（5000 条）
 * - 按天落盘 + 单文件超限轮转（保留历史）
 * - 系统 logcat 缓冲区 dump（main / system / crash / events）
 * - 完整导出：内存日志 + 磁盘历史日志 + 崩溃目录 + 系统缓冲区快照
 * - flush()：崩溃前强制同步落盘
 */
public class LogStore {

    private static final String TAG = "LogStore";
    private static final int MAX_ENTRIES = 5000;
    private static final String DIR_NAME = "logs";
    private static final String CRASH_DIR_NAME = "crash";
    /** 单个日志文件超过该大小即轮转。 */
    private static final long MAX_FILE_BYTES = 512 * 1024L;
    private static final int MAX_ROTATED_FILES = 10;

    /** 导出产物文件名前缀；导出时必须排除所有该前缀文件，避免自包含递归。 */
    public static final String EXPORT_PREFIX = "log_export_";
    /** 导出总大小硬上限：默认 20MB。 */
    public static final long EXPORT_MAX_BYTES = 20L * 1024 * 1024;
    /** 导出前要求的最小剩余磁盘空间：低于则放弃。 */
    public static final long EXPORT_MIN_FREE_BYTES = 500L * 1024 * 1024;
    /** 历史文件最多纳入导出个数。 */
    private static final int EXPORT_MAX_HISTORY_FILES = 3;
    /** 崩溃文件最多纳入导出个数。 */
    private static final int EXPORT_MAX_CRASH_FILES = 3;
    /** 导出总行数上限。 */
    private static final int EXPORT_MAX_LINES = 20000;
    /** logcat dump 行数上限，单次执行。 */
    private static final int EXPORT_LOGCAT_LINES = 5000;
    /** 保留最近多少个导出文件，其余轮转删除。 */
    private static final int EXPORT_KEEP_FILES = 3;

    public static final String LEVEL_DEBUG = "DEBUG";
    public static final String LEVEL_INFO = "INFO";
    public static final String LEVEL_WARN = "WARN";
    public static final String LEVEL_ERROR = "ERROR";

    public interface Listener {
        void onLogAdded(Entry e);
    }

    public static class Entry {
        public final long timestamp;
        public final String level;
        public final String tag;
        public final String message;

        public Entry(long timestamp, String level, String tag, String message) {
            this.timestamp = timestamp;
            this.level = level;
            this.tag = tag;
            this.message = message;
        }

        public String format() {
            String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                    .format(new Date(timestamp));
            return "[" + time + "][" + level + "][" + tag + "] " + message;
        }
    }

    private static volatile LogStore sInstance;

    public static LogStore getInstance() {
        if (sInstance == null) {
            synchronized (LogStore.class) {
                if (sInstance == null) {
                    sInstance = new LogStore();
                }
            }
        }
        return sInstance;
    }

    private final LinkedList<Entry> mBuffer = new LinkedList<>();
    private final CopyOnWriteArrayList<Listener> mListeners = new CopyOnWriteArrayList<>();

    private volatile Context mAppContext;
    private final Object mFileLock = new Object();
    private final Object mBufferLock = new Object();

    /**
     * 单线程落盘执行器：避免在 append() 调用线程（可能是主线程）里做文件 IO，
     * 也避免每次写一条日志都 new 一个 BufferedWriter / FileWriter。
     */
    private final ExecutorService mDiskExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "LogStore-disk");
        t.setDaemon(true);
        return t;
    });

    private LogStore() {
    }

    /** 由 Application / MainActivity 在启动时调用，用于初始化文件目录。 */
    public void init(Context context) {
        if (context != null) {
            mAppContext = context.getApplicationContext();
        }
    }

    public void addListener(Listener l) {
        if (l != null && !mListeners.contains(l)) {
            mListeners.add(l);
        }
    }

    public void removeListener(Listener l) {
        if (l != null) {
            mListeners.remove(l);
        }
    }

    /** 单条日志消息上限：超过即截断。防止 view_image 的 base64 等超大响应拖垮内存与 UI。 */
    private static final int MAX_MESSAGE_CHARS = 4096;

    public void append(String level, String tag, String message) {
        if (level == null) level = LEVEL_INFO;
        if (tag == null) tag = "-";
        if (message == null) message = "";
        if (message.length() > MAX_MESSAGE_CHARS) {
            message = message.substring(0, MAX_MESSAGE_CHARS)
                    + "...[truncated " + (message.length() - MAX_MESSAGE_CHARS) + " chars]";
        }

        long now = System.currentTimeMillis();
        Entry entry = new Entry(now, level, tag, message);

        synchronized (mBufferLock) {
            mBuffer.addLast(entry);
            while (mBuffer.size() > MAX_ENTRIES) {
                mBuffer.removeFirst();
            }
        }

        final Entry diskEntry = entry;
        mDiskExecutor.execute(() -> writeToFile(diskEntry));

        for (Listener l : mListeners) {
            try {
                l.onLogAdded(entry);
            } catch (Throwable t) {
                Log.w(TAG, "listener error", t);
            }
        }
    }

    public void debug(String tag, String message) {
        append(LEVEL_DEBUG, tag, message);
    }

    public void info(String tag, String message) {
        append(LEVEL_INFO, tag, message);
    }

    public void warn(String tag, String message) {
        append(LEVEL_WARN, tag, message);
    }

    public void error(String tag, String message) {
        append(LEVEL_ERROR, tag, message);
    }

    public List<Entry> snapshot() {
        synchronized (mBufferLock) {
            return new ArrayList<>(mBuffer);
        }
    }

    public void clear() {
        synchronized (mBufferLock) {
            mBuffer.clear();
        }
    }

    public String dumpText() {
        return dumpText(0);
    }

    /**
     * @param limit 最多导出多少条（0 表示全部）
     */
    public String dumpText(int limit) {
        List<Entry> list;
        synchronized (mBufferLock) {
            list = new ArrayList<>(mBuffer);
        }
        if (limit > 0 && list.size() > limit) {
            list = list.subList(list.size() - limit, list.size());
        }
        StringBuilder sb = new StringBuilder();
        for (Entry e : list) {
            sb.append(e.format()).append('\n');
        }
        return sb.toString();
    }

    /** 崩溃前调用：把缓冲区内所有条目重新同步写盘（幂等追加）。 */
    public void flush() {
        for (Entry e : snapshot()) {
            writeToFile(e);
        }
    }

    /**
     * 完整导出：内存日志 + 磁盘历史日志 + 崩溃目录 + 系统缓冲区快照。
     *
     * 安全约束（修复自包含递归导致写满磁盘的缺陷）：
     * - 在内存中一次性构造完整内容，写盘只做一次，禁止边读目录边追加写同一文件；
     * - 枚举 logs 目录时排除目标文件自身以及所有 log_export_*.txt 导出产物；
     * - 总大小硬上限 EXPORT_MAX_BYTES（默认 20MB），超出即截断并在文件头标注 TRUNCATED；
     * - logcat dump 仅执行一次，命令固定为 logcat -b main,crash,system -d -v threadtime -t 5000；
     * - 写盘前用 StatFs 检查剩余空间，低于 EXPORT_MIN_FREE_BYTES 直接放弃；
     * - 所有循环均有可判定的终止条件（文件数 / 行数 / 字节上限）。
     */
    public boolean exportFullToFile(String absPath) {
        if (absPath == null || absPath.isEmpty()) return false;
        File f = new File(absPath);
        File parent = f.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            Log.w(TAG, "exportFullToFile: cannot create parent dir for " + absPath);
            return false;
        }
        final String targetPath = f.getAbsolutePath();

        // 5. 写盘前检查剩余空间，低于阈值直接放弃。
        long freeBytes = Long.MAX_VALUE;
        try {
            android.os.StatFs stat = new android.os.StatFs(parent != null ? parent.getAbsolutePath() : targetPath);
            freeBytes = stat.getAvailableBytes();
        } catch (Throwable t) {
            Log.w(TAG, "exportFullToFile: StatFs failed, skip free-space check", t);
        }
        if (freeBytes < EXPORT_MIN_FREE_BYTES) {
            warn(TAG, "导出放弃：剩余空间不足 " + freeBytes + " bytes < " + EXPORT_MIN_FREE_BYTES);
            return false;
        }

        synchronized (mFileLock) {
            try {
                // 6. 在内存中一次性构造全部内容，之后单次写盘。
                StringBuilder sb = new StringBuilder();
                boolean truncated = false;
                int lineCount = 0;

                sb.append("########## MCP LOG EXPORT ##########\n");
                sb.append("exported at: ")
                        .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                                .format(new Date())).append('\n');

                // 1. 内存日志（导出按时间倒序：最新在最上方；仅此处倒序，运行时缓冲/落盘/界面顺序均不动）
                List<Entry> mem = snapshot();
                sb.append("\n===== 1. memory buffer (").append(mem.size()).append(" entries) =====\n");
                sb.append("# 导出时间: ")
                        .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()))
                        .append("  日志按时间倒序排列（最新在最上方）\n");
                sb.append('\n');
                for (int i = mem.size() - 1; i >= 0; i--) {
                    if (lineCount >= EXPORT_MAX_LINES) { truncated = true; break; }
                    sb.append(mem.get(i).format()).append('\n');
                    lineCount++;
                }

                // 2. 历史文件（最多 EXPORT_MAX_HISTORY_FILES 个，排除导出产物与目标自身）
                //    与内存日志合并后统一按 timestamp 倒序输出（方案 A），避免段内顺序与整体倒序冲突。
                sb.append("\n===== 2. history files (merged, desc) =====\n");
                File logDir = getLogDir();
                File[] historyFiles = collectExportableFiles(logDir, targetPath, EXPORT_MAX_HISTORY_FILES);
                lineCount = appendMergedHistory(sb, historyFiles, targetPath, lineCount);

                // 3. 崩溃报告（最多 EXPORT_MAX_CRASH_FILES 个）
                sb.append("\n===== 3. crash reports =====\n");
                File[] crashFiles = collectExportableFiles(getCrashDir(), targetPath, EXPORT_MAX_CRASH_FILES);
                for (File file : crashFiles) {
                    if (lineCount >= EXPORT_MAX_LINES) { truncated = true; break; }
                    lineCount = appendFileToBuffer(sb, file, lineCount);
                }

                // 4. 系统缓冲区：单次执行，命令固定
                sb.append("\n===== 4. system buffers (logcat dump) =====\n");
                sb.append(EXPORT_LOGCAT_CMD_MARK).append('\n');
                String logcat = readLogcatOnce();
                int logcatStart = sb.length();
                sb.append(logcat);
                // 单独对 logcat 段做行数截断
                int logcatLines = 0;
                for (int i = logcatStart; i < sb.length() && logcatLines < EXPORT_LOGCAT_LINES; i++) {
                    if (sb.charAt(i) == '\n') logcatLines++;
                }
                if (logcatLines >= EXPORT_LOGCAT_LINES && lineCount + logcatLines >= EXPORT_MAX_LINES) {
                    truncated = true;
                }
                lineCount += logcatLines;

                // 3. 总大小硬上限：超出即截断，并在文件头标注 TRUNCATED
                byte[] body = sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                if (body.length > EXPORT_MAX_BYTES) {
                    truncated = true;
                    int cut = (int) EXPORT_MAX_BYTES;
                    // 回退到最后一个完整行，避免半行乱码
                    while (cut > 0 && body[cut - 1] != '\n') cut--;
                    if (cut <= 0) cut = (int) EXPORT_MAX_BYTES;
                    byte[] trimmed = new byte[cut];
                    System.arraycopy(body, 0, trimmed, 0, cut);
                    body = trimmed;
                }

                StringBuilder header = new StringBuilder();
                header.append("########## MCP LOG EXPORT ##########\n");
                header.append("exported at: ")
                        .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                                .format(new Date())).append('\n');
                header.append("final bytes: ").append(body.length).append('\n');
                if (truncated) {
                    header.append("TRUNCATED: 导出内容超过上限 ")
                            .append(EXPORT_MAX_BYTES).append(" bytes，已截断\n");
                }
                header.append('\n');

                byte[] headerBytes = header.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                long finalSize = headerBytes.length + body.length;

                // 单次写盘：先写头，再写已构造好的正文
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f, false)) {
                    fos.write(headerBytes);
                    fos.write(body);
                    fos.flush();
                }

                // 8. 打印最终字节数；若超过上限记录失败原因
                final long WARN_BYTES = 5L * 1024 * 1024;
                if (truncated) {
                    warn(TAG, "导出完成（已截断）：finalSize=" + finalSize
                            + " bytes, 上限=" + EXPORT_MAX_BYTES + " bytes, 行数=" + lineCount);
                } else if (finalSize > WARN_BYTES) {
                    warn(TAG, "导出完成但体积较大：finalSize=" + finalSize
                            + " bytes（> 5MB）, 行数=" + lineCount);
                } else {
                    info(TAG, "导出完成：finalSize=" + finalSize + " bytes, 行数=" + lineCount);
                }

                // 轮转：导出路径下仅保留最近 EXPORT_KEEP_FILES 个导出文件
                pruneExportFiles();
                return true;
            } catch (Throwable t) {
                Log.e(TAG, "exportFullToFile failed: " + absPath, t);
                return false;
            }
        }
    }

    /** 兼容旧调用：默认走完整导出。 */
    public boolean exportToFile(String absPath) {
        return exportFullToFile(absPath);
    }

    /**
     * 枚举目录下可导出的文件，排除目标文件自身与所有 log_export_*.txt 导出产物，
     * 并按修改时间倒序取最多 max 个，避免把导出产物当输入造成自包含递归。
     */
    private File[] collectExportableFiles(File dir, String targetPath, int max) {
        if (dir == null || !dir.exists() || max <= 0) return new File[0];
        File[] files = dir.listFiles();
        if (files == null) return new File[0];
        List<File> picked = new ArrayList<>();
        for (File file : files) {
            if (!file.isFile()) continue;
            String name = file.getName();
            if (name.startsWith(EXPORT_PREFIX)) continue; // 排除导出产物
            if (file.getAbsolutePath().equals(targetPath)) continue; // 排除目标自身
            picked.add(file);
        }
        picked.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        if (picked.size() > max) picked = picked.subList(0, max);
        return picked.toArray(new File[0]);
    }

    /**
     * 合并内存日志与历史文件内容，统一按 timestamp 倒序输出（方案 A）。
     *
     * 说明：
     * - 内存段已在调用方倒序输出；本方法读取各历史文件行并按时间戳排序后倒序写入；
     * - 无时间戳的裸行按「紧随上一条有戳行」处理，时间沿用上一条；
     * - 遵守 EXPORT_MAX_LINES 行数上限，超限追加截断标记。
     */
    private int appendMergedHistory(StringBuilder sb, File[] historyFiles, String targetPath, int lineCount) {
        if (historyFiles == null || historyFiles.length == 0) return lineCount;

        List<Object[]> items = new ArrayList<>();
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

        for (File file : historyFiles) {
            if (file == null || !file.isFile()) continue;
            sb.append("\n----- file: ").append(file.getName())
                    .append(" (").append(file.length()).append(" bytes) -----\n");
            long lastTs = 0L;
            try (BufferedReader r = new BufferedReader(new java.io.FileReader(file))) {
                String line;
                while ((line = r.readLine()) != null) {
                    long ts = parseTimestamp(line, fmt);
                    if (ts <= 0L) ts = lastTs; // 裸行沿用上一条时间戳
                    else lastTs = ts;
                    items.add(new Object[]{ts, line});
                }
            } catch (Throwable t) {
                sb.append("[read failed: ").append(t).append("]\n");
            }
        }

        // 合并后按 timestamp 倒序（新→旧）
        items.sort((a, b) -> Long.compare((Long) b[0], (Long) a[0]));

        for (Object[] it : items) {
            if (lineCount >= EXPORT_MAX_LINES) {
                sb.append("[truncated: 已达行数上限]").append('\n');
                break;
            }
            sb.append((String) it[1]).append('\n');
            lineCount++;
        }
        return lineCount;
    }

    /** 从一行日志中解析时间戳（毫秒）；解析失败返回 0。 */
    private static long parseTimestamp(String line, SimpleDateFormat fmt) {
        if (line == null || line.length() < 25) return 0L;
        // 形如: [2024-01-02 03:04:05.678][INFO][tag] msg
        int start = line.indexOf('[');
        if (start < 0) return 0L;
        int end = line.indexOf(']', start + 1);
        if (end <= start) return 0L;
        String ts = line.substring(start + 1, end);
        try {
            Date d = fmt.parse(ts);
            return d != null ? d.getTime() : 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** 把文件内容追加到内存缓冲，返回更新后的总行数；有行数上限保护。 */
    private int appendFileToBuffer(StringBuilder sb, File file, int lineCount) {
        sb.append("\n----- file: ").append(file.getName())
                .append(" (").append(file.length()).append(" bytes) -----\n");
        try (BufferedReader r = new BufferedReader(new java.io.FileReader(file))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (lineCount >= EXPORT_MAX_LINES) {
                    sb.append("[truncated: 已达行数上限]").append('\n');
                    break;
                }
                sb.append(line).append('\n');
                lineCount++;
            }
        } catch (Throwable t) {
            sb.append("[read failed: ").append(t).append("]\n");
        }
        return lineCount;
    }

    private static final String EXPORT_LOGCAT_CMD_MARK =
            "# command: logcat -b main,crash,system -d -v threadtime -t " + EXPORT_LOGCAT_LINES;

    /** 单次执行 logcat dump，命令固定，禁止循环/流式无终止读取。 */
    private String readLogcatOnce() {
        StringBuilder sb = new StringBuilder();
        Process p = null;
        try {
            p = new ProcessBuilder("logcat", "-b", "main,crash,system", "-d",
                    "-v", "threadtime", "-t", String.valueOf(EXPORT_LOGCAT_LINES))
                    .redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                int read = 0;
                while ((line = r.readLine()) != null && read < EXPORT_LOGCAT_LINES) {
                    sb.append(line).append('\n');
                    read++;
                }
            }
            p.waitFor();
        } catch (Throwable t) {
            sb.append("[logcat dump failed: ").append(t).append("]\n");
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
        return sb.toString();
    }

    /** 导出目录轮转：仅保留最近 EXPORT_KEEP_FILES 个 log_export_*.txt。 */
    private void pruneExportFiles() {
        File dir = getLogDir();
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        List<File> exports = new ArrayList<>();
        for (File file : files) {
            if (file.isFile() && file.getName().startsWith(EXPORT_PREFIX)) {
                exports.add(file);
            }
        }
        if (exports.size() <= EXPORT_KEEP_FILES) return;
        exports.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = EXPORT_KEEP_FILES; i < exports.size(); i++) {
            //noinspection ResultOfMethodCallIgnored
            exports.get(i).delete();
        }
    }

    /** dump 四个系统缓冲区：main / system / crash / events。 */
    public String dumpLogcatBuffers() {
        StringBuilder sb = new StringBuilder();
        sb.append(dumpSingleBuffer("main", 3000));
        sb.append(dumpSingleBuffer("system", 2000));
        sb.append(dumpSingleBuffer("crash", 2000));
        sb.append(dumpSingleBuffer("events", 1000));
        return sb.toString();
    }

    /** 读取指定 logcat 缓冲区的末尾内容（使用 logcat -d，非阻塞）。 */
    public static String readLogcatBuffer(String buffer, int lines) {
        if (buffer == null || buffer.isEmpty()) buffer = "main";
        StringBuilder sb = new StringBuilder();
        Process p = null;
        try {
            p = new ProcessBuilder("logcat", "-d", "-b", buffer, "-v", "threadtime", "-t",
                    String.valueOf(Math.max(1, lines))).redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            p.waitFor();
        } catch (Throwable t) {
            sb.append("[dump " + buffer + " buffer failed: ").append(t).append("]\n");
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
        return sb.toString();
    }

    private String dumpSingleBuffer(String buffer, int lines) {
        return "\n----- logcat -b " + buffer + " -----\n" + readLogcatBuffer(buffer, lines);
    }

    private File getBaseDir() {
        Context ctx = mAppContext;
        File ext;
        if (ctx != null) {
            ext = ctx.getExternalFilesDir(null);
        } else {
            ext = Environment.getExternalStorageDirectory();
        }
        return ext;
    }

    private File getLogDir() {
        File base = getBaseDir();
        if (base == null) return null;
        File dir = new File(base, DIR_NAME);
        if (!dir.exists() && !dir.mkdirs()) return null;
        return dir;
    }

    /** 崩溃目录，供 NativeCrashCollector 等外部采集器写入。 */
    public File getCrashDir() {
        File base = getBaseDir();
        if (base == null) return null;
        File dir = new File(base, CRASH_DIR_NAME);
        if (!dir.exists() && !dir.mkdirs()) return null;
        return dir;
    }

    /**
     * 把一段崩溃现场文本单独写入 crash 目录，文件名带时间戳与 tag，
     * 便于事后从磁盘直接取证（不依赖内存缓冲）。
     */
    public File dumpToCrashDir(String tag, String content) {
        File dir = getCrashDir();
        if (dir == null || content == null) return null;
        String safeTag = (tag == null || tag.isEmpty()) ? "crash" : tag.replaceAll("[^a-zA-Z0-9_-]", "_");
        synchronized (mFileLock) {
            // 去重：同一 tag 下若已存在内容完全相同的崩溃文件，直接复用，避免同一次崩溃写多份。
            File dup = findDuplicateCrashFile(dir, content);
            if (dup != null) {
                Log.i(TAG, "dumpToCrashDir dedup hit: " + dup.getName());
                return dup;
            }
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss.SSS", Locale.US).format(new Date());
            File out = new File(dir, safeTag + "-" + stamp + ".txt");
            try (BufferedWriter w = new BufferedWriter(new FileWriter(out, false))) {
                w.write(content);
                if (!content.endsWith("\n")) w.newLine();
                w.flush();
                return out;
            } catch (Throwable t) {
                Log.w(TAG, "dumpToCrashDir failed: " + out, t);
                return null;
            }
        }
    }

    /**
     * 在 crash 目录中查找与 content 内容完全一致的文件（同名去重用）。
     * 只做内容比对，读取上限 512KB，避免大文件拖慢崩溃路径。
     */
    private File findDuplicateCrashFile(File dir, String content) {
        File[] files = dir.listFiles((d, n) -> n != null && n.endsWith(".txt"));
        if (files == null || files.length == 0) return null;
        final int MAX_CMP = 512 * 1024;
        for (File f : files) {
            if (!f.isFile()) continue;
            if (f.length() > MAX_CMP) continue;
            try {
                byte[] other = java.nio.file.Files.readAllBytes(f.toPath());
                String text = new String(other, java.nio.charset.StandardCharsets.UTF_8);
                if (text.equals(content) || (text + "\n").equals(content)) {
                    return f;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private void writeToFile(Entry e) {
        File dir = getLogDir();
        if (dir == null) return;
        String day = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date(e.timestamp));
        File logFile = new File(dir, "mcp-" + day + ".log");
        String line = e.format();
        synchronized (mFileLock) {
            try {
                rotateIfNeeded(logFile);
                try (BufferedWriter w = new BufferedWriter(new FileWriter(logFile, true))) {
                    w.write(line);
                    w.newLine();
                    w.flush();
                }
            } catch (Throwable t) {
                Log.w(TAG, "writeToFile failed", t);
            }
        }
    }

    /** 文件超限则轮转：重命名带序号，超出保留数量则删最旧。 */
    private void rotateIfNeeded(File logFile) {
        if (!logFile.exists() || logFile.length() < MAX_FILE_BYTES) return;
        File dir = logFile.getParentFile();
        if (dir == null) return;
        String baseName = logFile.getName();
        String rotatedName = baseName + "." + System.currentTimeMillis();
        File rotated = new File(dir, rotatedName);
        if (!logFile.renameTo(rotated)) {
            return;
        }
        pruneOldRotated(dir, baseName);
    }

    private void pruneOldRotated(File dir, String baseName) {
        File[] files = dir.listFiles();
        if (files == null) return;
        List<File> rotated = new ArrayList<>();
        for (File f : files) {
            if (f.isFile() && f.getName().startsWith(baseName + ".")) {
                rotated.add(f);
            }
        }
        if (rotated.size() <= MAX_ROTATED_FILES) return;
        rotated.sort((a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        int toDelete = rotated.size() - MAX_ROTATED_FILES;
        for (int i = 0; i < toDelete; i++) {
            //noinspection ResultOfMethodCallIgnored
            rotated.get(i).delete();
        }
    }
}
