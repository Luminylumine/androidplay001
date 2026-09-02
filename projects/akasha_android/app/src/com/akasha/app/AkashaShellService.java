package com.akasha.app;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import com.akasha.aidl.IAkashaShell;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Bound by Shizuku / Dhizuku and executed inside their privileged process,
 * so shell commands here run as uid 2000 (shell) / system.
 * Keep the class public with a no-arg constructor (loaded by reflection).
 *
 * NOTE: this runs in a foreign process (shell uid) and CANNOT write to the
 * app's external files dir, so it logs to /sdcard/akasha_shell.log instead.
 */
public class AkashaShellService extends Service {

    private static final String TAG = "Akasha";
    private static final File LOG = new File("/sdcard/akasha_shell.log");
    private static final SimpleDateFormat FMT = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private final IAkashaShell.Stub binder = new IAkashaShell.Stub() {
        @Override
        public String exec(String cmd) {
            return AkashaShellService.this.execCmd(cmd);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        slog("onCreate uid=" + android.os.Process.myUid() + " pid=" + android.os.Process.myPid()
                + " proc=" + procName());
    }

    @Override
    public IBinder onBind(Intent intent) {
        slog("onBind uid=" + android.os.Process.myUid() + " action=" + (intent == null ? "null" : intent.getAction()));
        return binder;
    }

    private String procName() {
        try {
            return java.lang.management.ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
        } catch (Throwable t) {
            return "?";
        }
    }

    /** Write a line to /sdcard/akasha_shell.log (world-writable) + logcat. */
    private static void slog(String msg) {
        Log.i(TAG, "[shell] " + msg);
        try {
            String line = FMT.format(new Date()) + " " + msg + "\n";
            FileOutputStream out = new FileOutputStream(LOG, true);
            try {
                out.write(line.getBytes("UTF-8"));
            } finally {
                out.close();
            }
            if (LOG.length() > 256 * 1024) {
                // trim to newest 128KB
                byte[] all = new byte[(int) LOG.length()];
                java.io.FileInputStream in = new java.io.FileInputStream(LOG);
                try {
                    int n = 0;
                    while (n < all.length) {
                        int r = in.read(all, n, all.length - n);
                        if (r < 0) break;
                        n += r;
                    }
                } finally {
                    in.close();
                }
                int keep = Math.min(128 * 1024, all.length);
                FileOutputStream out2 = new FileOutputStream(LOG);
                try {
                    out2.write(all, all.length - keep, keep);
                } finally {
                    out2.close();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private String execCmd(String cmd) {
        if (cmd == null) return "rc=-1\nnull";
        slog("exec: " + cmd.replace('\n', '\\'));
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", cmd});
            StringBuilder out = new StringBuilder();
            StringBuilder err = new StringBuilder();
            Thread outReader = new Thread(() -> drain(p.getInputStream(), out, false), "shell-stdout");
            Thread errReader = new Thread(() -> drain(p.getErrorStream(), err, true), "shell-stderr");
            outReader.start();
            errReader.start();
            boolean finished = p.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                p.destroy();
                if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly();
                outReader.interrupt();
                errReader.interrupt();
                return "rc=124\n命令执行超时(30秒)";
            }
            outReader.join(1000);
            errReader.join(1000);
            String s = out.toString() + err.toString();
            int rc = p.exitValue();
            if (s.length() > 30000) s = s.substring(0, 30000) + "\n…(truncated)";
            slog("exec rc=" + rc + " outlen=" + s.length());
            return "rc=" + rc + "\n" + s;
        } catch (Exception ex) {
            slog("exec threw: " + ex);
            return "rc=-1\nexec 异常: " + ex;
        }
    }

    private static void drain(java.io.InputStream stream, StringBuilder target, boolean error) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"));
            String line;
            int lines = 0;
            while (!Thread.currentThread().isInterrupted()
                    && (line = reader.readLine()) != null && lines++ < 400) {
                synchronized (target) {
                    if (target.length() < 30000) {
                        if (error) target.append("[e] ");
                        target.append(line).append('\n');
                    }
                }
            }
            reader.close();
        } catch (Exception ignored) {}
    }
}
