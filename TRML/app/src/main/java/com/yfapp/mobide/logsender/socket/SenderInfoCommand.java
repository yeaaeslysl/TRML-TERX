package com.yfapp.mobide.logsender.socket;

/**
 * Command containing information about the log sender.
 *
 * <p>线格式：{@code /sender:<senderId>:<packageName>}
 */
public class SenderInfoCommand extends AbstractSocketCommand {

    public static final String NAME = "sender";

    public final String senderId, packageName;

    public SenderInfoCommand(String senderId, String packageName) {
        this.senderId = senderId;
        this.packageName = packageName;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    protected String[] getParams() {
        return new String[]{this.senderId, this.packageName};
    }
}
