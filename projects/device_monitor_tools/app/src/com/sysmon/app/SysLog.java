package com.sysmon.app;

import android.util.Log;

/** 轻量日志封装：logcat + 可选文件。 */
public final class SysLog {
    public static final String TAG = "SysMon";

    private SysLog() {}

    public static void v(String m) { Log.v(TAG, m); }
    public static void d(String m) { Log.d(TAG, m); }
    public static void i(String m) { Log.i(TAG, m); }
    public static void w(String m) { Log.w(TAG, m); }
    public static void e(String m) { Log.e(TAG, m); }
    public static void e(String m, Throwable t) { Log.e(TAG, m, t); }
}
