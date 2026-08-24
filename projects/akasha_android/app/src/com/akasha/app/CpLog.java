package com.akasha.app;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * File-backed logger. Mirrors every call to logcat (tag "Akasha" style)
 * and appends it to a ~1MB rolling file in the app's external files dir
 * (no storage permission needed):
 *   /sdcard/Android/data/com.akasha.app/logs/akasha.log
 * Pull it with:  adb pull /sdcard/Android/data/com.akasha.app/logs/akasha.log
 */
public class CpLog {
    private static final int MAX = 1024 * 1024; // 1MB
    private static final int KEEP = 400 * 1024; // after trim keep the newest 400KB
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final SimpleDateFormat FMT = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private static volatile File file;
    private static volatile boolean ready = false;
    private static final Object LOCK = new Object();

    public static void init(Context ctx) {
        synchronized (LOCK) {
            if (ready) return;
            try {
                // 优先外部目录(adb pull 方便); 受限系统(部分 Huawei)回退到内部存储
                File base = ctx.getExternalFilesDir(null);
                if (base == null) base = ctx.getFilesDir();
                File dir = new File(base, "logs");
                if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("mkdirs failed");
                file = new File(dir, "akasha.log");
                // 写入自检
                FileOutputStream t = new FileOutputStream(file, true);
                t.close();
                ready = true;
            } catch (Exception e) {
                try {
                    File dir = new File(ctx.getFilesDir(), "logs");
                    if (!dir.exists()) dir.mkdirs();
                    file = new File(dir, "akasha.log");
                    FileOutputStream t = new FileOutputStream(file, true);
                    t.close();
                } catch (Exception e2) {
                    file = null;
                }
                ready = true;
            }
        }
        i("CpLog", "log file: " + (file == null ? "unavailable" : file.getAbsolutePath()));
    }

    public static String path() {
        File f = file;
        return f == null ? null : f.getAbsolutePath();
    }

    public static void i(String tag, String msg) { write("I", tag, msg); }
    public static void w(String tag, String msg) { write("W", tag, msg); }
    public static void e(String tag, String msg) { write("E", tag, msg); }
    public static void d(String tag, String msg) { write("D", tag, msg); }

    private static void write(String lvl, String tag, String msg) {
        Log.println(lvl.charAt(0), tag, msg);
        if (!ready) return;
        File f = file;
        if (f == null) return;
        String line;
        synchronized (LOCK) {
            line = FMT.format(new Date()) + " " + lvl + " " + tag + ": " + msg + "\n";
        }
        byte[] b = line.getBytes(UTF8);
        try {
            FileOutputStream out = new FileOutputStream(f, true);
            try {
                out.write(b);
            } finally {
                out.close();
            }
            if (f.length() > MAX) trim(f);
        } catch (Exception ignored) {
        }
    }

    private static void trim(File f) {
        try {
            FileInputStream in = new FileInputStream(f);
            try {
                int len = (int) f.length();
                byte[] all = new byte[len];
                int n = 0;
                while (n < len) {
                    int r = in.read(all, n, len - n);
                    if (r < 0) break;
                    n += r;
                }
                int keep = Math.min(KEEP, n);
                FileOutputStream out = new FileOutputStream(f);
                try {
                    out.write(all, n - keep, keep);
                } finally {
                    out.close();
                }
            } finally {
                in.close();
            }
        } catch (Exception ignored) {
        }
    }
}
