package com.yfapp.mobide.logsender.socket;

import java.util.UUID;

/**
 * 解析 log sender 建连后发来的第一条命令。
 */
public final class SocketCommandParser {

    /**
     * 解析发送端建连后发来的首行命令。
     *
     * <p><b>本方法是全函数（total）：对任何输入都不抛异常</b>，非命令一律返回 {@code null}。
     * 与 MobIDE 侧 {@code SocketCommandParser} 保持逻辑一致（同一协议工具，两端同步加固）。
     *
     * @return 解析出的命令；非法或无法识别时为 {@code null}。
     */
    public static ISocketCommand parse(String line) {
        // 空串 / 无前导 '/' 直接判否：原实现 line.substring(1) 会对空串抛
        // StringIndexOutOfBoundsException（非受检），必须前置判空保证不抛。
        if (line == null || line.isEmpty() || line.charAt(0) != '/') {
            return null;
        }

        // remove leading '/'
        final String body = line.substring(1);
        if (!body.contains(ISocketCommand.PARAM_DELIMITER)) {
            return create(body);
        }

        final String[] segments = body.split(ISocketCommand.PARAM_DELIMITER);
        // 有参数的命令目前只有 /sender，它必须恰好带 senderId + packageName 共 3 段；
        // 原实现用 >=2 放行后又在 createParameterized 里取 segments[2]，会越界。
        if (segments.length >= 3) {
            return createParameterized(segments);
        }

        return null;
    }

    private static ISocketCommand create(String name) {
        if (name.equals(SignalCommand.STOP.getName())) {
            return SignalCommand.STOP;
        }

        return null;
    }

    private static ISocketCommand createParameterized(String[] segments) {
        final String command = segments[0];
        if (SenderInfoCommand.NAME.equals(command)) {
            try {
                // validate the sender ID
                // noinspection ResultOfMethodCallIgnored
                UUID.fromString(segments[1]);
            } catch (Exception e) {
                return null;
            }

            return new SenderInfoCommand(segments[1], segments[2]);
        }
        return null;
    }
}
