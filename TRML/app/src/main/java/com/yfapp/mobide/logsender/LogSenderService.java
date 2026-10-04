package com.yfapp.mobide.logsender;

import android.app.Notification;
import android.app.Notification.BigTextStyle;
import android.app.Notification.Builder;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Build;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.IBinder;
import android.widget.Toast;

import com.yfapp.mobide.logsender.utils.Logger;

/**
 * 后台服务：由 {@code LogSenderInstaller}（ContentProvider）在 App 启动时自动拉起，
 * 再通过 LogSender 把本进程 logcat 转发给 MobIDE。
 *
 * <p><b>必须在 onCreate() 里调用 startForeground()</b>：Android 8+ 上若用
 * startForegroundService() 启动却在 5 秒内不展示通知，系统会抛
 * ForegroundServiceDidNotStartInTimeException 并杀掉应用。
 */
public class LogSenderService extends Service {

    private final LogSender logSender = new LogSender();
    private static final int NOTIFICATION_ID = 644;
    private static final String NOTIFICATION_CHANNEL_NAME = "LogSender Service";
    private static final String NOTIFICATION_TITLE = "LogSender Service";
    private static final String NOTIFICATION_TEXT = "Connected to MobIDE";
    private static final String NOTIFICATION_CHANNEL_ID = "ide.logsender.service";
    public static final String ACTION_START_SERVICE = "ide.logsender.service.start";
    public static final String ACTION_STOP_SERVICE = "ide.logsender.service.stop";

    /**
     * 通知文案与图标不使用 {@code R.*}：本库以源码形式注入工程，而 AGP 生成的 R 类位于
     * 应用包名下，库代码里的裸 R 引用会指向不存在的库包 R。改为常量 + 具名资源解析，等价且通用。
     */
    private static final String TEXT_BIND_FAILED = "Failed to bind to MobIDE";
    private static final String TEXT_ACTION_EXIT = "Exit";
    private static final String DRAWABLE_ICON = "mobide_ic_logsender";

    @Override
    public void onCreate() {
        Logger.debug("[LogSenderService] onCreate()");
        super.onCreate();
        setupNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
    }

    @Override
    public IBinder onBind(Intent intent) {
        Logger.debug("Unexpected request to bind.", intent);
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Logger.debug("onStartCommand", intent, flags, startId);

        final String action = intent == null ? null : intent.getAction();
        if (ACTION_START_SERVICE.equals(action)) {
            actionStartService();
        } else if (ACTION_STOP_SERVICE.equals(action)) {
            actionStopService();
        } else {
            Logger.error("Unknown service action:", action);
        }

        return START_NOT_STICKY;
    }

    private void actionStartService() {
        Logger.info("Starting log sender service...");

        boolean result = false;
        try {
            result = logSender.bind(getApplicationContext());
            Logger.debug("Bind to MobIDE:", result);
        } catch (Exception err) {
            Logger.error(TEXT_BIND_FAILED, err);
        }

        if (!result) {
            Toast.makeText(this, TEXT_BIND_FAILED, Toast.LENGTH_SHORT).show();
            actionStopService();
        }
    }

    private void actionStopService() {
        Logger.info("Stopping log sender service...");
        stopSelf();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Logger.debug("[LogSenderService] [onTaskRemoved]", rootIntent);

        if (!logSender.isConnected() && !logSender.isBinding()) {
            Logger.debug("Not bound to MobIDE. Ignored.");
            return;
        }

        Logger.warn("Task removed. Destroying log sender...");
        logSender.destroy(getApplicationContext());
        stopSelf();
    }

    @Override
    public void onDestroy() {
        Logger.debug("[LogSenderService] [onDestroy]");
        if (!logSender.isConnected() && !logSender.isBinding()) {
            Logger.debug("Not bound to MobIDE. Ignored.");
            return;
        }

        Logger.warn("Service is being destroyed. Destroying log sender...");
        logSender.destroy(getApplicationContext());
        super.onDestroy();
    }

    private void setupNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
        );

        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        if (notificationManager != null) {
            notificationManager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Resources res = getResources();

        // Set notification priority
        // If holding a wake or wifi lock consider the notification of high priority since it's using power,
        // otherwise use a low priority
        int priority = Notification.PRIORITY_LOW;

        // Build the notification
        final Builder builder = new Builder(this);
        builder.setContentTitle(NOTIFICATION_TITLE);
        builder.setContentText(NOTIFICATION_TEXT);
        builder.setStyle(new BigTextStyle().bigText(NOTIFICATION_TEXT));
        builder.setPriority(priority);

        if (VERSION.SDK_INT >= VERSION_CODES.O) {
            builder.setChannelId(NOTIFICATION_CHANNEL_ID);
        }

        if (VERSION.SDK_INT >= VERSION_CODES.JELLY_BEAN_MR1) {
            builder.setShowWhen(false);
        }

        builder.setSmallIcon(resolveSmallIcon());

        if (VERSION.SDK_INT >= VERSION_CODES.LOLLIPOP) {
            builder.setColor(0xFF607D8B);
        }

        builder.setOngoing(true);

        // Set Exit button action
        Intent exitIntent = new Intent(this, LogSenderService.class).setAction(ACTION_STOP_SERVICE);
        builder.addAction(android.R.drawable.ic_delete, TEXT_ACTION_EXIT,
                PendingIntent.getService(this, 0, exitIntent, PendingIntent.FLAG_IMMUTABLE));

        return builder.build();
    }

    /** 取注入的矢量图标；找不到（资源未被合并）时回退系统图标，保证通知一定能显示。 */
    private int resolveSmallIcon() {
        final int id = getResources().getIdentifier(DRAWABLE_ICON, "drawable", getPackageName());
        return id != 0 ? id : android.R.drawable.ic_dialog_info;
    }
}
