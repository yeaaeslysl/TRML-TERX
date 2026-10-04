package com.yfapp.mobide.logsender.socket;

import java.util.Objects;

/**
 * 用于向发送端/接收端发送信号的命令：线格式为 {@code /stop}。
 */
public final class SignalCommand extends AbstractSocketCommand {

    public static final ISocketCommand STOP = new SignalCommand("stop");
    private final String name;

    public SignalCommand(String name) {
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SignalCommand)) {
            return false;
        }
        SignalCommand that = (SignalCommand) o;
        return Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }
}
