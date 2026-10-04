package com.termi.app.terminal;

import android.util.Log;

public final class Logger {
    private static final String TAG = "TermiEmu";

    public static void logError(String tag, String msg) {
        Log.e(TAG, "[" + tag + "] " + msg);
    }

    public static void logWarn(String tag, String msg) {
        Log.w(TAG, "[" + tag + "] " + msg);
    }

    public static void logInfo(String tag, String msg) {
        Log.i(TAG, "[" + tag + "] " + msg);
    }
}
