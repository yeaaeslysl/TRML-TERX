package com.yfapp.mobide.logsender.utils;

import static com.yfapp.mobide.logsender.LogSender.PACKAGE_MOBIDE;

import android.app.Activity;
import android.app.Application;
import android.app.BackgroundServiceStartNotAllowedException;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;

import com.yfapp.mobide.logsender.LogSender;
import com.yfapp.mobide.logsender.LogSenderService;

/**
 * ContentProvider 在 Application 创建之前就被加载，因此用来在 App 启动时自动安装 LogSender，
 * 开发者只需引入库，无需写任何初始化代码。
 */
public class LogSenderInstaller extends ContentProvider {

    @Override
    public boolean onCreate() {
        final Application application = ((Application) getContext());
        if (PACKAGE_MOBIDE.equals(application.getPackageName())) {
            // do not send logs to self
            return true;
        }

        startSenderService(application);

        // Android 12+ 禁止从后台启动前台服务：ContentProvider.onCreate 发生在 Application 之前，
        // 系统此时可能仍把进程判定为「后台」，startForegroundService 会抛
        // BackgroundServiceStartNotAllowedException（被 startSenderService 吞掉，服务就起不来）。
        // 补一个生命周期回调：等第一个 Activity 真正进入前台后再启一次。
        // LogSenderService.onStartCommand 是幂等的（LogSender.bind 内部有 isConnected/isBinding 判断）。
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(Activity activity) {
                startSenderService(activity.getApplication());
            }

            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
            }

            @Override
            public void onActivityStarted(Activity activity) {
            }

            @Override
            public void onActivityPaused(Activity activity) {
            }

            @Override
            public void onActivityStopped(Activity activity) {
            }

            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
            }

            @Override
            public void onActivityDestroyed(Activity activity) {
            }
        });

        return true;
    }

    /**
     * 拉起 {@link LogSenderService}（幂等）。
     *
     * <p>只吞掉 Android 12+ 的 BackgroundServiceStartNotAllowedException——那是「时机不对」，
     * 上面还挂了 Activity 回调兜底；其它异常照常抛出，避免隐藏真正的错误。
     */
    private static void startSenderService(Application application) {
        try {
            final Intent intent = new Intent(application, LogSenderService.class);
            intent.setAction(LogSenderService.ACTION_START_SERVICE);

            if (VERSION.SDK_INT >= VERSION_CODES.O) {
                application.startForegroundService(intent);
            } else {
                application.startService(intent);
            }
        } catch (Exception e) {

            // starting a background service is not allowed on Android 12+
            // ignore the BackgroundServiceStartNotAllowedException in such cases
            if (VERSION.SDK_INT < VERSION_CODES.S
                    || !(e instanceof BackgroundServiceStartNotAllowedException)) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
