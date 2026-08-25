package com.akasha.app;

import android.content.Context;
import android.util.Log;

/**
 * Logcat-only logger (release build).
 * Every call is mirrored to logcat with the given tag; nothing is written
 * to disk in release builds.
 */
public class CpLog {
    private CpLog() {
    }

    /** Kept for API compatibility; no-op in release builds. */
    public static void init(Context ctx) {
    }

    /** No log file in release builds. */
    public static String path() {
        return null;
    }

    public static void i(String tag, String msg) {
        Log.i(tag, msg);
    }

    public static void w(String tag, String msg) {
        Log.w(tag, msg);
    }

    public static void e(String tag, String msg) {
        Log.e(tag, msg);
    }

    public static void d(String tag, String msg) {
        Log.d(tag, msg);
    }
}
