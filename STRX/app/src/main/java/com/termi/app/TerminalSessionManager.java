package com.termi.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * 长驻 Linux 终端会话管理（文件通道，绕开 Shizuku 流限制）。
 *
 * <h3>为什么不用 Shizuku 的流</h3>
 * 实测 {@code Shizuku.newProcess} 返回的 {@link Process}，
 * 其 {@code getInputStream()} 对<b>长驻交互进程</b>读不到任何数据
 * （短命令如 {@link ShellExecutor} 那种用完即退的可以）。
 * 故改用文件作为通信通道。
 *
 * <h3>链路</h3>
 * <pre>
 * App 敲命令
 *   → 写入 App 私有暂存文件（app 身份可写）
 *   → ShizukuFs.exec("cat staging >> cmd.txt")   （短命令，可用）
 *   → tail -f cmd.txt | proot sh -l -i > out.txt （长驻，shell 身份）
 *   → App 轮询读 out.txt 的增量 → 显示
 * </pre>
 *
 * <h3>为何用 tail -f</h3>
 * 让 proot 的 stdin 保持打开（否则 shell 读完即退），
 * 同时把 App 追加的命令逐条喂进去，从而保持 shell 长驻
 * （{@code cd} / {@code export} 等会话内状态得以保留）。
 */
public final class TerminalSessionManager {

    private static final String TAG = "TermSession";

    /** 会话目录（shell_data_file；App 只读，写需经 Shizuku）。 */
    private static final String SESSION_DIR = ShizukuFs.ENV_ROOT + "/session";
    private static final String CMD_FILE = SESSION_DIR + "/cmd.txt";
    private static final String OUT_FILE = SESSION_DIR + "/out.txt";
    /** 记录会话进程 PID，用于可靠停止（pkill 匹配不可靠，见 stop() 注释）。 */
    private static final String PID_FILE = SESSION_DIR + "/pids.txt";

    /** 轮询间隔（毫秒）。 */
    private static final long POLL_INTERVAL_MS = 60L;

    /** 输出回调。 */
    public interface OutputListener {
        void onOutput(String text);
        /** 会话结束（进程退出或出错）。 */
        void onEnded(String reason);
    }

    private final Context appContext;
    private final Handler main = new Handler(Looper.getMainLooper());

    private OutputListener listener;
    private volatile boolean running = false;
    private Thread pollThread;
    /** 已读到的 out.txt 字节偏移。 */
    private long readOffset = 0L;

    /** 启动期间积压的命令（会话就绪后按序补发）。 */
    private final java.util.List<String> pending = new java.util.ArrayList<>();

    public TerminalSessionManager(Context ctx) {
        this.appContext = ctx.getApplicationContext();
    }

    public void setListener(OutputListener l) {
        this.listener = l;
    }

    public boolean isRunning() {
        return running;
    }

    // ------------------------------------------------------------ 启动

    /**
     * 启动长驻会话。
     *
     * @return 成功与否
     */
    public boolean start() {
        if (running) {
            LogStore.getInstance().warn(TAG, "会话已在运行");
            return true;
        }
        if (!ShizukuFs.available()) {
            LogStore.getInstance().error(TAG, "Shizuku 不可用，无法启动会话");
            return false;
        }
        if (!RuntimeManager.isEnvUsable(appContext)) {
            LogStore.getInstance().error(TAG, "环境不可用，无法启动会话");
            return false;
        }

        // 1) 准备会话目录与文件
        ShizukuFs.exec("rm -rf " + SESSION_DIR + " && mkdir -p " + SESSION_DIR
                + " && : > " + CMD_FILE + " && : > " + OUT_FILE
                + " && chmod -R 777 " + SESSION_DIR);

        // 2) 构建长驻命令：tail -f cmd.txt | proot ... > out.txt
        String[][] spec = RuntimeManager.buildLinuxShellArgv(appContext);
        if (spec == null) {
            LogStore.getInstance().error(TAG, "无法构建 Linux shell 参数");
            return false;
        }
        String[] argv = spec[0];
        String[] env = spec[1];

        // 构建 proot 执行部分（含环境变量内联）
        StringBuilder proot = new StringBuilder();
        for (String kv : env) {
            int i = kv.indexOf('=');
            if (i > 0) {
                proot.append("export ").append(kv.substring(0, i)).append('=')
                     .append(shellQ(kv.substring(i + 1))).append("; ");
            }
        }
        proot.append("exec");
        for (String a : argv) {
            proot.append(' ').append(shellQ(a));
        }

        // 完整链路：tail -f cmd.txt | { 设置环境; exec proot } >> out.txt 2>&1
        //
        // 注意：tail -f 与 proot 都是长驻进程，命令永不返回。
        // 因此用 setsid + 重定向 + & 彻底后台化，让 Shizuku 的 exec 立即返回；
        // setsid 可使其脱离当前进程组，避免 Shizuku 侧回收时连带杀掉。
        //
        // 关键：启动后把 tail 与 proot 的 PID 写入 PID_FILE，
        // 供 stop() 精确 kill（pkill 匹配不可靠，会导致僵尸进程累积）。
        String pipeline = "tail -f " + shellQ(CMD_FILE) + " | { "
                + proot + "; } >> " + shellQ(OUT_FILE) + " 2>&1";
        String cmd = "cd " + shellQ(SESSION_DIR)
                + " && setsid sh -c " + shellQ(pipeline)
                + " </dev/null >/dev/null 2>&1 &"
                + " sleep 0.5;"
                + " ps -A -o PID,ARGS 2>/dev/null"
                + " | grep -E " + shellQ("tail -f " + CMD_FILE) + " | awk '{print $1}'"
                + " > " + shellQ(PID_FILE) + " 2>/dev/null;"
                + " ps -A -o PID,ARGS 2>/dev/null | grep " + shellQ("libproot.so")
                + " | grep -v grep | awk '{print $1}' >> " + shellQ(PID_FILE) + " 2>/dev/null;"
                + " sort -u " + shellQ(PID_FILE) + " -o " + shellQ(PID_FILE) + " 2>/dev/null;"
                + " chmod 666 " + shellQ(PID_FILE) + " 2>/dev/null;"
                + " echo STARTED pid=$(cat " + shellQ(PID_FILE) + " 2>/dev/null | tr '\\n' ' ')";
        LogStore.getInstance().info(TAG, "启动长驻会话: " + cmd);

        ShizukuFs.Exec e = ShizukuFs.exec(cmd);
        if (e == null || !e.ok()) {
            LogStore.getInstance().error(TAG, "启动会话失败: "
                    + (e == null ? "Shizuku 不可用" : e.output));
            return false;
        }

        running = true;
        readOffset = 0L;
        startPolling();
        // 补发"启动期间"积压的命令（LOGIN 后立刻敲的第一条往往落在这里）
        flushPending();
        LogStore.getInstance().info(TAG, "会话已启动");
        return true;
    }

    // ------------------------------------------------------------ 发送命令

    /**
     * 发送一条命令到长驻 shell。
     *
     * <p><b>为何不经过暂存文件</b>：Shizuku 以 shell 身份运行，
     * <b>读不到 App 私有目录</b>（SELinux app_data_file 对 shell 拒绝），
     * 实测 {@code cat /data/user/0/<pkg>/cache/...} 直接 Permission denied。
     * 故改用 {@code printf} 由 shell 侧直接追加，内容经参数传入。
     */
    public boolean sendCommand(String line) {
        if (!running) {
            // 会话仍在启动中（start() 需多次 Shizuku 调用，约 2~3 秒）。
            // 此窗口内的命令不能丢弃，先入队，待 start() 完成后按序补发，
            // 否则会出现"LOGIN 后第一条命令无反应"的问题。
            synchronized (pending) {
                pending.add(line);
            }
            LogStore.getInstance().info(TAG, "会话未就绪，命令入队: [" + line
                    + "]（队列长度=" + pendingSize() + "）");
            return true;
        }
        return doSend(line);
    }

    /** 实际执行一次发送（会话已就绪时调用）。 */
    private boolean doSend(String line) {
        try {
            // 用单引号包裹命令内容交给 printf 追加。
            // 只需按 shell 单引号规则转义（把 ' 变成 '\''），
            // 不要额外处理反斜杠 —— printf 的 %s 不解析转义序列，
            // 多转义会导致反斜杠被原样写入。
            String escaped = line.replace("'", "'\\''");
            ShizukuFs.Exec e = ShizukuFs.exec("printf '%s\\n' '" + escaped + "' >> "
                    + shellQ(CMD_FILE));
            if (e == null || !e.ok()) {
                LogStore.getInstance().error(TAG, "发送命令失败: "
                        + (e == null ? "Shizuku 不可用" : e.output));
                return false;
            }
            return true;
        } catch (Throwable t) {
            LogStore.getInstance().error(TAG, "sendCommand 异常: " + t);
            return false;
        }
    }

    /** 补发启动期间积压的命令。 */
    private void flushPending() {
        java.util.List<String> copy;
        synchronized (pending) {
            if (pending.isEmpty()) return;
            copy = new java.util.ArrayList<>(pending);
            pending.clear();
        }
        LogStore.getInstance().info(TAG, "补发积压命令 " + copy.size() + " 条");
        for (String l : copy) {
            doSend(l);
        }
    }

    private int pendingSize() {
        synchronized (pending) {
            return pending.size();
        }
    }

    // ------------------------------------------------------------ 轮询读取

    private void startPolling() {
        pollThread = new Thread(() -> {
            RandomAccessFile raf = null;
            try {
                File out = new File(OUT_FILE);
                while (running) {
                    if (raf == null) {
                        if (!out.isFile()) {
                            Thread.sleep(POLL_INTERVAL_MS);
                            continue;
                        }
                        raf = new RandomAccessFile(out, "r");
                    }
                    long len = raf.length();
                    if (len > readOffset) {
                        raf.seek(readOffset);
                        int avail = (int) Math.min(len - readOffset, 64 * 1024);
                        byte[] buf = new byte[avail];
                        int n = raf.read(buf);
                        if (n > 0) {
                            readOffset += n;
                            final String text = new String(buf, 0, n,
                                    java.nio.charset.StandardCharsets.UTF_8);
                            main.post(() -> {
                                if (listener != null) listener.onOutput(text);
                            });
                        }
                    } else if (len < readOffset) {
                        // 文件被截断（会话重启），重置偏移
                        readOffset = 0;
                    }
                    Thread.sleep(POLL_INTERVAL_MS);
                }
            } catch (InterruptedException ie) {
                // 正常停止
            } catch (Throwable t) {
                LogStore.getInstance().error(TAG, "轮询异常: " + t);
                main.post(() -> {
                    if (listener != null) listener.onEnded("轮询异常: " + t.getMessage());
                });
            } finally {
                if (raf != null) {
                    try { raf.close(); } catch (Throwable ignored) {}
                }
            }
        }, "terx-session-poll");
        pollThread.setDaemon(true);
        pollThread.start();
    }

    // ------------------------------------------------------------ 停止

    /** 停止会话（杀 tail 与 proot 进程）。 */
    public void stop() {
        running = false;
        if (pollThread != null) {
            pollThread.interrupt();
            pollThread = null;
        }
        // 杀进程：优先按 PID 文件精确杀（可靠），再兜底按命令行特征杀。
        //
        // 为何不能只靠 pkill -f：实测发现 Shizuku 执行时命令会经多层 sh -c 包装，
        // 单引号可能被吃掉导致匹配失败；且 setsid 后进程脱离原进程组，
        // 会话反复切换会累积大量僵尸 tail/proot（实测累积 7 组）。
        String killCmd = "if [ -f " + PID_FILE + " ]; then "
                + "for p in $(cat " + PID_FILE + "); do kill -9 $p 2>/dev/null; done; "
                + "rm -f " + PID_FILE + "; fi; "
                + "pkill -9 -f " + shellQ("tail -f " + CMD_FILE) + " 2>/dev/null; "
                + "pkill -9 -f " + shellQ("proot") + " 2>/dev/null; true";
        ShizukuFs.exec(killCmd);
        LogStore.getInstance().info(TAG, "会话已停止");
    }

    // ------------------------------------------------------------ 辅助

    private static String shellQ(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
