package com.yfapp.mobide.logsender.socket;

/**
 * Base class for socket commands.
 *
 * <p>序列化格式：{@code /<name>[:<param>[:<param>...]]}
 */
public abstract class AbstractSocketCommand implements ISocketCommand {

    protected String[] getParams() {
        return null;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("/");
        sb.append(getName());

        final String[] params = getParams();
        if (params != null && params.length > 0) {
            sb.append(PARAM_DELIMITER);
            for (int i = 0; i < params.length; i++) {
                sb.append(params[i]);
                if (i < params.length - 1) {
                    sb.append(PARAM_DELIMITER);
                }
            }
        }

        return sb.toString();
    }
}
