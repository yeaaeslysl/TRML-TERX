package com.yfapp.mobide.logsender.socket;

import java.io.File;

/**
 * 经 socket 在发送端与接收端之间传输的命令：以 {@code /} 开头，参数以 {@link #PARAM_DELIMITER} 分隔。
 */
public interface ISocketCommand {

    String PARAM_DELIMITER = File.pathSeparator;

    String getName();
}
