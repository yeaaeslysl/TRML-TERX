package com.yfapp.mobide.logsender;

import com.yfapp.mobide.logsender.ILogSender;

/**
 * The LogReceiver interface.
 *
 * <p>实现方运行在 MobIDE 进程；被调试 App 持有其代理并调用 connect()。
 * oneway：调用不阻塞，避免拖慢被调试 App 的启动路径。
 */
oneway interface ILogReceiver {

    void ping();

    void connect(ILogSender sender);

    void disconnect(String packageName, String senderId);
}
