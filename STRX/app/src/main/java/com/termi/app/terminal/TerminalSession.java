package com.termi.app.terminal;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 终端会话：管理 PTY 输入输出流，驱动模拟器解析。
 * 由外部线程启动 readLoop()，持续读取 PTY 输出并喂给 TerminalEmulator。
 */
public final class TerminalSession {
    private final InputStream ptyInput;
    private final TerminalEmulator emulator;
    private volatile boolean running = false;

    public TerminalSession(InputStream ptyInput, TerminalEmulator emulator) {
        this.ptyInput = ptyInput;
        this.emulator = emulator;
    }

    /**
     * 阻塞式读取循环，必须在独立线程中调用。
     * 读取 PTY 原始字节，解码为 UTF-8 字符串后追加到模拟器。
     */
    public void readLoop() {
        running = true;
        byte[] buf = new byte[4096];
        try {
            while (running) {
                int n = ptyInput.read(buf);
                if (n == -1) break;
                String text = new String(buf, 0, n, StandardCharsets.UTF_8);
                emulator.append(text.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Logger.logError("TerminalSession", "readLoop failed: " + e.getMessage());
        } finally {
            running = false;
        }
    }

    public void stop() {
        running = false;
    }
}
