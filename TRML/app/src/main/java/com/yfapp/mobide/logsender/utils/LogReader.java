package com.yfapp.mobide.logsender.utils;

import com.yfapp.mobide.logsender.socket.ISocketCommand;
import com.yfapp.mobide.logsender.socket.SenderInfoCommand;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reads application logs with {@code logcat}.
 *
 * <p>在本进程执行 {@code logcat -v threadtime}——读取「自己」的日志无需任何权限，
 * 因此免 root、免 READ_LOGS，Android 10+ 同样可用。
 *
 * <p>唯一的外部依赖是 {@code android.permission.INTERNET}：日志要经 TCP socket 回传给 IDE。
 */
public class LogReader extends Thread {

    private final String senderId;
    private final String packageName;
    private final int port;
    private final ProcessBuilder processBuilder;
    private final AtomicBoolean isInterrupted = new AtomicBoolean(false);

    public LogReader(String senderId, String packageName, int port) {
        this(senderId, packageName, port, defaultCmd());
    }

    public LogReader(String senderId, String packageName, int port, String[] cmd) {
        super("MobIDE-LogReader");
        this.senderId = senderId;
        this.packageName = packageName;
        this.port = port;

        this.processBuilder = new ProcessBuilder(cmd);
        this.processBuilder.redirectErrorStream(true);
    }

    private static String[] defaultCmd() {
        return new String[]{"logcat", "-v", "threadtime"};
    }

    /** 建连重试上限：应对接收端 ServerSocket 尚未就绪的冷启动竞态。 */
    private static final int CONNECT_MAX_ATTEMPTS = 5;

    /** 每次建连重试的间隔（毫秒）。 */
    private static final long CONNECT_RETRY_INTERVAL_MS = 300L;

    @Override
    public void run() {
        Logger.info("Starting to read logs...");

        final Socket socket = connect();
        if (socket == null) {
            // connect() 内部已打好可辨识的错误日志，这里直接结束线程即可。
            return;
        }

        try (final Socket s = socket) {
            final Process process = processBuilder.start();

            try (final BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {

                final OutputStream outputStream = s.getOutputStream();

                // Send the sender info
                writeCommand(new SenderInfoCommand(this.senderId, this.packageName), outputStream);

                String line;
                while (!isInterrupted.get() && (line = reader.readLine()) != null) {
                    line += "\n";
                    outputStream.write(line.getBytes());
                }

            } catch (IOException ioError) {
                Logger.error("Error reading from the logcat process or writing to the socket", ioError);
            } finally {
                s.close();
            }
        } catch (IOException ioError) {
            Logger.error("LogReader 无法启动 logcat 或写入 socket (127.0.0.1:" + port + ")", ioError);
        }
    }

    /**
     * 建立到接收端口环 socket 的连接，带<b>有界</b>冷启动重试。
     *
     * <p>冷启动竞态：被调试 App 可能在 MobIDE 的 ServerSocket 真正 bind 完成前就来连，
     * 此时对端尚未 listening，会抛 Connection refused。这里最多重试
     * {@link #CONNECT_MAX_ATTEMPTS} 次、每次间隔 {@link #CONNECT_RETRY_INTERVAL_MS} ms，
     * 耗尽后打印可辨识错误并返回 {@code null}。<b>不做无限重试</b>，也不会新增线程或依赖。
     *
     * <p>显式 IPv4 回环 127.0.0.1：{@code getLocalHost()} 可能解析到非回环地址、
     * {@code getLoopbackAddress()} 可能返回 IPv6(::1)，都会与接收端 ServerSocket 的地址族不一致
     * 而连接失败。钉死 127.0.0.1 保证两端同族。
     *
     * <p>注意：Android 上创建任何 INET socket（含回环）都要求 {@code android.permission.INTERNET}。
     * 没有它这里会抛 {@code SocketException: socket failed: EPERM}——日志里会看到
     * 「LogReader 无法连接 127.0.0.1:<port>」重复 N 次后以 EPERM 告终。该权限由 MobIDE 注入 Manifest 时补齐。
     *
     * @return 已连接的 socket；重试耗尽仍失败则返回 {@code null}。
     */
    private Socket connect() {
        IOException lastError = null;
        for (int attempt = 1; attempt <= CONNECT_MAX_ATTEMPTS; attempt++) {
            try {
                return new Socket(InetAddress.getByName("127.0.0.1"), port);
            } catch (IOException e) {
                lastError = e;
                Logger.warn("LogReader 无法连接 127.0.0.1:" + port
                        + "（第 " + attempt + "/" + CONNECT_MAX_ATTEMPTS + " 次）", e);
                if (attempt < CONNECT_MAX_ATTEMPTS) {
                    try {
                        Thread.sleep(CONNECT_RETRY_INTERVAL_MS);
                    } catch (InterruptedException ie) {
                        // 被 cancel() 打断：恢复中断标记并放弃重试，避免线程悬挂。
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
            }
        }
        Logger.error("LogReader 无法连接 127.0.0.1:" + port
                + "，已重试 " + CONNECT_MAX_ATTEMPTS + " 次仍失败", lastError);
        return null;
    }

    /**
     * 写入一条命令：命令单独占一行（带换行），避免与第一条日志在接收端 readLine() 时粘包。
     */
    private void writeCommand(ISocketCommand command, OutputStream outputStream) throws IOException {
        outputStream.write((command.toString() + "\n").getBytes());
        outputStream.flush();
    }

    public void cancel() {
        this.isInterrupted.set(true);
        this.interrupt();
    }
}
