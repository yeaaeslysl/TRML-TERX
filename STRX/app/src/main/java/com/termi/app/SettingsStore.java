package com.termi.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 统一读写 MCP 配置（host/port/token），供 MainActivity 与 FloatingWindow 共用。
 */
public final class SettingsStore {

    public static final String PREFS_NAME = "mcp";
    public static final String KEY_HOST = "host";
    public static final String KEY_PORT = "port";
    public static final String KEY_TOKEN = "token";

    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 8000;

    private SettingsStore() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static String getHost(Context ctx) {
        return prefs(ctx).getString(KEY_HOST, DEFAULT_HOST);
    }

    public static int getPort(Context ctx) {
        return prefs(ctx).getInt(KEY_PORT, DEFAULT_PORT);
    }

    public static String getToken(Context ctx) {
        return prefs(ctx).getString(KEY_TOKEN, "");
    }

    public static void save(Context ctx, String host, int port, String token) {
        prefs(ctx).edit()
                .putString(KEY_HOST, host)
                .putInt(KEY_PORT, port)
                .putString(KEY_TOKEN, token)
                .apply();
    }

    // ------------------------------------------------------------ 启动动画速度

    private static final String KEY_SPLASH_SPEED = "splash_speed";
    /** 默认速度系数：1.0 = 默认节奏（越大越快）。 */
    public static final float DEFAULT_SPLASH_SPEED = 1.0f;

    /** 启动动画速度系数（彩蛋中可调）。 */
    public static float getSplashSpeed(Context ctx) {
        return prefs(ctx).getFloat(KEY_SPLASH_SPEED, DEFAULT_SPLASH_SPEED);
    }

    public static void setSplashSpeed(Context ctx, float speed) {
        // 限制在合理范围：0.4x ~ 2.5x
        float v = Math.max(0.4f, Math.min(2.5f, speed));
        prefs(ctx).edit().putFloat(KEY_SPLASH_SPEED, v).apply();
    }
}
