package com.yfapp.mobide.logsender;

/**
 * 发送端接口：实现方运行在「被调试 App」进程，MobIDE 持有其代理。
 */
interface ILogSender {

    void ping();

    void startReader(int port);

    int getPid();

    String getPackageName();

    String getId();

    void onDisconnect();
}
