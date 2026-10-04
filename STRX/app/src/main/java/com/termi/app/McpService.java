package com.termi.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class McpService extends Service {

    /**
     * MCP 运行状态监听接口。running 为 true 时 host/port 为实际监听地址，
     * false 时 host 为空串、port 为 0。
     */
    public interface StateListener {
        void onStateChanged(boolean running, String host, int port);
    }

    private static final CopyOnWriteArrayList<StateListener> sListeners =
            new CopyOnWriteArrayList<>();

    public static void addStateListener(StateListener l) {
        if (l != null && !sListeners.contains(l)) {
            sListeners.add(l);
            // 注册时立即回调一次当前状态，避免界面初始状态不同步
            l.onStateChanged(running, activeHostStatic, activePortStatic);
        }
    }

    public static void removeStateListener(StateListener l) {
        if (l != null) sListeners.remove(l);
    }

    /** 通知所有监听者状态变化，单个监听器异常不影响其它监听器。 */
    public static void notifyState(boolean running, String host, int port) {
        activeHostStatic = running ? host : "";
        activePortStatic = running ? port : 0;
        for (StateListener l : sListeners) {
            try {
                l.onStateChanged(running, host, port);
            } catch (Exception e) {
                LogStore.getInstance().append("ERROR", "McpService",
                        "notifyState listener error: " + e);
            }
        }
    }

    /** 供注册时读取的静态快照。 */
    private static volatile String activeHostStatic = "";
    private static volatile int activePortStatic = 0;

    /** 应用上下文快照（供 MCP 工具在无 Context 处读取环境配置，如 shell_exec 的 env 参数）。 */
    private static volatile Context sAppContext;

    /** 取应用上下文；未初始化时返回 null（调用方需判空）。 */
    public static Context appContext() {
        return sAppContext;
    }

    private FloatingWindow floatingWindow;

    private String activeHost = "127.0.0.1";
    private int activePort = 8000;
    private String activeToken = null;

    /** 自定义文案：启动/停止 MCP Server。由 MainActivity 读取持久化配置后写入。 */
    private static volatile String sStartText = "启动 MCP Server";
    private static volatile String sStopText = "停止 MCP Server";

    /** 供 MainActivity 应用自定义文案；状态变化时会自然刷新到界面。 */
    public static void setTexts(String startText, String stopText) {
        if (startText != null && !startText.trim().isEmpty()) {
            sStartText = startText.trim();
        }
        if (stopText != null && !stopText.trim().isEmpty()) {
            sStopText = stopText.trim();
        }
    }

    /** 当前应展示的 MCP 按钮文案，依运行状态返回启动或停止。 */
    public static String getToggleText() {
        return running ? sStopText : sStartText;
    }

    public static final String ACTION_START = "com.termi.app.START";
    public static final String ACTION_STOP  = "com.termi.app.STOP";
    public static final String CHANNEL_ID = "mcp_service";
    public static final int NOTIF_ID = 1001;

    public static volatile McpServer currentServer = null;
    public static volatile boolean running = false;

    public static void start(Context ctx, String host, int port, String token) {
        LogStore.getInstance().append("INFO", "McpService",
                "start requested host=" + host + " port=" + port + " token=" + (token != null && !token.isEmpty()));
        // 端口固定：若已有实例在跑，先彻底停掉（含旧线程/旧 Socket），避免 bind 冲突与残留连接
        if (currentServer != null || running) {
            LogStore.getInstance().append("INFO", "McpService",
                    "检测到旧实例，先清理再启动新实例");
            running = false;
            if (currentServer != null) {
                currentServer.stop();
                currentServer = null;
            }
        }
        Intent i = new Intent(ctx, McpService.class);
        i.setAction(ACTION_START);
        i.putExtra("host", host);
        i.putExtra("port", port);
        i.putExtra("token", token);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    public static void stop(Context ctx) {
        Intent i = new Intent(ctx, McpService.class);
        i.setAction(ACTION_STOP);
        ctx.startService(i);
    }

    private PowerManager.WakeLock wakeLock;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 记录应用上下文（供 MCP 工具读取环境配置）
        if (sAppContext == null) {
            sAppContext = getApplicationContext();
        }
        String action = intent != null ? intent.getAction() : ACTION_START;
        if (ACTION_STOP.equals(action)) {
            LogStore.getInstance().append("INFO", "McpService", "stop requested");
            running = false;
            if (currentServer != null) {
                currentServer.stop();
                currentServer = null;
            }
            notifyState(false, "", 0);
            // 悬浮球生命周期仅由 MainActivity 开关控制，停止 MCP 不隐藏悬浮球
            if (floatingWindow != null) {
                floatingWindow.updateStatus(false);
                floatingWindow = null;
            }
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        } else {
            String host = intent != null ? intent.getStringExtra("host") : "127.0.0.1";
            int port = intent != null ? intent.getIntExtra("port", 8000) : 8000;
            String token = intent != null ? intent.getStringExtra("token") : null;
            activeHost = host;
            activePort = port;
            activeToken = token;
            createChannel();
            startForeground(NOTIF_ID, buildNotif("MCP Server @ " + host + ":" + port));
            acquireWakeLock();
            // 端口固定：无论 onStartCommand 被重复触发多少次，都先停掉旧实例再重建，避免端口占用
            if (currentServer != null) {
                LogStore.getInstance().append("INFO", "McpService",
                        "onStartCommand 发现旧实例，先停止");
                currentServer.stop();
                currentServer = null;
            }
            if (currentServer == null) {
                currentServer = new McpServer(host, port, token);
                try {
                    currentServer.start();
                } catch (Exception e) {
                    LogStore.getInstance().append("ERROR", "McpService",
                            "start server failed: " + e);
                    currentServer = null;
                    running = false;
                    stopSelf();
                    return START_NOT_STICKY;
                }
            }
            if (floatingWindow == null) {
                floatingWindow = FloatingWindow.get(this);
            }
            floatingWindow.show();
            floatingWindow.updateStatus(true);
            running = true;
            notifyState(true, host, port);
            return START_STICKY;
        }
    }

    @Override
    public void onDestroy() {
        LogStore.getInstance().append("INFO", "McpService", "onDestroy");
        Intent restart = new Intent(this, McpService.class);
        PendingIntent pi = PendingIntent.getService(
                this, 1001, restart,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (pi != null) {
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            if (am != null) am.cancel(pi);
        }
        running = false;
        if (currentServer != null) {
            currentServer.stop();
            currentServer = null;
        }
        // 悬浮球生命周期仅由 MainActivity 开关控制，onDestroy 不隐藏悬浮球
        if (floatingWindow != null) {
            floatingWindow.updateStatus(false);
            floatingWindow = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        LogStore.getInstance().append("WARN", "McpService", "onTaskRemoved, scheduling restart");
        Intent restart = new Intent(this, McpService.class);
        restart.setAction(ACTION_START);
        restart.putExtra("host", activeHost);
        restart.putExtra("port", activePort);
        restart.putExtra("token", activeToken);
        PendingIntent pi = PendingIntent.getService(
                this, 1001, restart,
                PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (am != null) {
            am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 1000, pi);
            LogStore.getInstance().append("INFO", "McpService", "restart alarm set");
        }
        super.onTaskRemoved(rootIntent);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "MCP Service", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotif(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("ShizukuMCP 运行中")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setOngoing(true)
                .build();
    }

    private void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ShizukuMCP::wake");
            }
        }
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(10 * 60 * 1000L);
        }
    }
}
