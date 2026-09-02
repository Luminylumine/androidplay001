package com.akasha.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.IBinder;

import com.akasha.aidl.IAkashaShell;
import com.rosan.dhizuku.api.Dhizuku;
import com.rosan.dhizuku.api.DhizukuRequestPermissionListener;
import com.rosan.dhizuku.api.DhizukuUserServiceArgs;
import com.rosan.dhizuku.shared.DhizukuVariables;
import rikka.shizuku.Shizuku;

import java.lang.reflect.Method;

/**
 * Privileged command channel.
 *
 * Priority:
 *   1. Shizuku  - the service runs inside shizuku_server (shell uid 2000).
 *                 This is the REAL shell channel.
 *   2. Dhizuku  - Device Owner. Does NOT provide shell: its hosted service
 *                 runs with Dhizuku's app uid (e.g. 10203). Kept only as a
 *                 degraded fallback (app-level commands) and is labelled
 *                 with its uid in the UI.
 *
 * A bind attempt that does not complete within {@link #BIND_TIMEOUT_MS} is
 * abandoned so the next source can be tried (a hung Dhizuku bind must never
 * block the Shizuku fallback).
 */
public class ShellChannel {

    private static final String TAG = "Akasha";
    private static final ComponentName SVC =
            new ComponentName("com.akasha.app", "com.akasha.app.AkashaShellService");
    private static final long BIND_TIMEOUT_MS = 8000;

    private static IAkashaShell svc = null;
    private static String source = null;   // established: "shizuku" | "dhizuku"
    private static String pending = null;  // in-flight bind source
    private static long pendingAt = 0;
    private static String svcUid = null;   // uid reported by the "id -u" probe
    private static Context appCtx = null;
    private static int attempts = 0;

    private static final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            String src = pending;
            CpLog.i(TAG, "conn.onServiceConnected name=" + name
                    + " established=" + source + " pending=" + src);
            if (src == null) {
                CpLog.w(TAG, "onServiceConnected ignored (no in-flight bind)");
                return;
            }
            IAkashaShell s = IAkashaShell.Stub.asInterface(service);
            String probe = null;
            try {
                probe = s.exec("id -u");
            } catch (Exception ex) {
                CpLog.e(TAG, "probe exec threw: " + ex);
            }
            String probeHead = probe == null ? "null" : probe.replace('\n', '|');
            if (probeHead.length() > 200) probeHead = probeHead.substring(0, 200);
            if (probe != null && probe.startsWith("rc=0")) {
                int nl = probe.indexOf('\n');
                String uid = (nl >= 0) ? probe.substring(nl + 1).trim() : "?";
                svc = s;
                source = src;
                svcUid = uid;
                pending = null;
                CpLog.i(TAG, "shell channel up via " + src + " uid=" + uid + " probe=" + probeHead);
            } else {
                pending = null;
                CpLog.w(TAG, "shell probe FAILED via " + src + " probe=" + probeHead);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            CpLog.w(TAG, "conn.onServiceDisconnected established=" + source + " pending=" + pending);
            svc = null;
            source = null;
            svcUid = null;
            pending = null;
        }
    };

    public static void init(Context ctx) {
        // Shizuku delivers its binder through the declared ShizukuProvider;
        // event-driven re-try keeps the channel alive without polling.
        appCtx = ctx.getApplicationContext();
        try {
            Shizuku.addBinderReceivedListenerSticky(new Shizuku.OnBinderReceivedListener() {
                @Override
                public void onBinderReceived() {
                    CpLog.i(TAG, "shizuku binder received");
                    attempts = 0;
                    ensure();
                }
            });
            Shizuku.addBinderDeadListener(new Shizuku.OnBinderDeadListener() {
                @Override
                public void onBinderDead() {
                    CpLog.w(TAG, "shizuku binder dead (server stopped?)");
                    if ("shizuku".equals(source) || "shizuku".equals(pending)) {
                        svc = null;
                        source = null;
                        svcUid = null;
                        pending = null;
                    }
                    attempts = 0;
                    ensure();
                }
            });
            Shizuku.addRequestPermissionResultListener(new Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    CpLog.i(TAG, "shizuku permission result req=" + requestCode
                            + " grant=" + grantResult + " (0=granted)");
                    attempts = 0;
                    ensure();
                }
            });
        } catch (Throwable t) {
            CpLog.w(TAG, "shizuku listeners: " + t);
        }
    }

    /** Try to (re)establish the channel. Returns the (in-flight or established) source or null. */
    public static String ensure() {
        if (svc != null) return source;
        if (pending != null) {
            long elapsed = System.currentTimeMillis() - pendingAt;
            if (elapsed > BIND_TIMEOUT_MS) {
                CpLog.w(TAG, "bind via " + pending + " timed out after " + elapsed + "ms, clearing");
                pending = null;
            } else {
                return null; // still in flight
            }
        }
        if (attempts > 6) {
            CpLog.w(TAG, "ensure: attempts exhausted (" + attempts + ")");
            return null; // don't hammer forever
        }
        attempts++;

        if (tryShizuku()) {
            pending = "shizuku";
            pendingAt = System.currentTimeMillis();
            CpLog.i(TAG, "ensure: shizuku bind in flight (attempt #" + attempts + ")");
            return pending;
        }
        if (tryDhizuku()) {
            pending = "dhizuku";
            pendingAt = System.currentTimeMillis();
            CpLog.i(TAG, "ensure: dhizuku bind in flight (attempt #" + attempts + ")");
            return pending;
        }
        CpLog.w(TAG, "ensure: no source available after attempt #" + attempts);
        return null;
    }

    private static boolean tryShizuku() {
        try {
            boolean ping = Shizuku.pingBinder();
            int perm = ping ? Shizuku.checkSelfPermission() : -1;
            int uid = ping ? Shizuku.getUid() : -1;
            CpLog.i(TAG, "tryShizuku ping=" + ping + " checkSelfPermission=" + perm + " serverUid=" + uid);
            if (!ping || perm != android.content.pm.PackageManager.PERMISSION_GRANTED) return false;
            Shizuku.bindUserService(shizukuArgs(), conn);
            CpLog.i(TAG, "shizuku bindUserService invoked");
            return true;
        } catch (Throwable t) {
            CpLog.w(TAG, "shizuku bind: " + t);
            return false;
        }
    }

    /**
     * NOTE: processNameSuffix is REQUIRED - the Shizuku server throws
     * NPE("process name suffix must not be null") without it.
     */
    private static Shizuku.UserServiceArgs shizukuArgs() {
        int vc = 1;
        try {
            vc = appCtx.getPackageManager().getPackageInfo("com.akasha.app", 0).versionCode;
        } catch (Throwable ignored) {}
        return new Shizuku.UserServiceArgs(SVC)
                .processNameSuffix("clawshell")
                .version(vc);
    }

    /**
     * Degraded fallback: Dhizuku (Device Owner) hosts the service with its
     * own app uid - NOT shell. Commands still work at app level (legacy
     * storage etc.), which is better than nothing when Shizuku is down.
     */
    private static boolean tryDhizuku() {
        try {
            boolean inited = Dhizuku.init(appCtx);
            boolean granted = inited && Dhizuku.isPermissionGranted();
            CpLog.i(TAG, "tryDhizuku init=" + inited + " granted=" + granted);
            if (!granted) return false;
            boolean r = Dhizuku.bindUserService(new DhizukuUserServiceArgs(SVC), conn);
            CpLog.i(TAG, "dhizuku bindUserService ret=" + r + " (true=initiated)");
            return r;
        } catch (Throwable t) {
            CpLog.w(TAG, "dhizuku bind: " + t);
            return false;
        }
    }

    public static boolean available() {
        return svc != null;
    }

    public static String source() {
        return source;
    }

    public static String pendingSource() {
        return pending;
    }

    /** uid the channel actually runs as ("2000" = real shell). */
    public static String svcUid() {
        return svcUid;
    }

    /** Only Shizuku's real shell process may change secure accessibility settings. */
    public static boolean hasRealShell() {
        return available() && "shizuku".equals(source) && "2000".equals(svcUid);
    }

    /** Execute a command through the channel. Returns "rc=N\n<output>" or null. */
    public static String exec(String cmd) {
        IAkashaShell s = svc;
        if (s == null) {
            CpLog.w(TAG, "exec called but channel down (source=" + source
                    + " pending=" + pending + "): " + shortCmd(cmd));
            return null;
        }
        try {
            return s.exec(cmd);
        } catch (Exception e) {
            CpLog.w(TAG, "exec threw: " + e + " cmd=" + shortCmd(cmd));
            return null;
        }
    }

    private static String shortCmd(String c) {
        if (c == null) return "null";
        c = c.replace('\n', ' ');
        return c.length() <= 100 ? c : c.substring(0, 100) + "…";
    }

    // ---------- status / guidance for the settings page ----------

    public static boolean dhizukuInstalled() {
        return pkgInstalled(DhizukuVariables.PACKAGE_NAME);
    }

    public static boolean shizukuInstalled() {
        return pkgInstalled("moe.shizuku.privileged.api");
    }

    private static boolean pkgInstalled(String pkg) {
        try {
            appCtx.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 1=ok 0=not granted/not running -1=not installed */
    public static int dhizukuStatus() {
        if (!dhizukuInstalled()) return -1;
        try {
            return Dhizuku.isPermissionGranted() ? 1 : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    public static int shizukuStatus() {
        if (!shizukuInstalled()) return -1;
        try {
            if (!Shizuku.pingBinder()) return 0;
            return Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED ? 1 : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Ask Dhizuku to grant us (opens its UI). */
    public static void requestDhizukuPermission() {
        try {
            Dhizuku.init(appCtx);
            Dhizuku.requestPermission(new DhizukuRequestPermissionListener() {
                @Override
                public void onRequestPermission(int grantResult) {
                    CpLog.i(TAG, "dhizuku permission result: " + grantResult);
                    attempts = 0;
                    ensure();
                }
            });
        } catch (Throwable t) {
            CpLog.w(TAG, "dhizuku requestPermission: " + t);
        }
    }

    /** Ask Shizuku to grant us (shows Shizuku's permission dialog). */
    public static void requestShizukuPermission() {
        try {
            CpLog.i(TAG, "shizuku requestPermission invoked");
            Shizuku.requestPermission(1001);
        } catch (Throwable t) {
            CpLog.w(TAG, "shizuku requestPermission: " + t);
        }
    }

    public static void openDhizuku() {
        openPkg(DhizukuVariables.PACKAGE_NAME);
    }

    public static void openShizuku() {
        openPkg("moe.shizuku.privileged.api");
    }

    private static void openPkg(String pkg) {
        try {
            appCtx.startActivity(appCtx.getPackageManager().getLaunchIntentForPackage(pkg));
        } catch (Throwable t) {
            CpLog.w(TAG, "openPkg: " + t);
        }
    }

    /** Open developer options; on API30+ try the wireless debugging page. */
    public static void openWirelessDebugging() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                try {
                    Class<?> c = Class.forName("android.provider.Settings");
                    Method m = c.getMethod("buildWirelessDebuggingIntent");
                    android.content.Intent i = (android.content.Intent) m.invoke(null);
                    appCtx.startActivity(i);
                    return;
                } catch (Throwable ignored) {}
            }
            android.content.Intent i = new android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
            appCtx.startActivity(i);
        } catch (Throwable t) {
            CpLog.w(TAG, "openWirelessDebugging: " + t);
        }
    }

    /** Reset retry budget (called after user action, e.g. onResume). */
    public static void reset() {
        attempts = 0;
    }

    /** Full re-check: unbind any in-flight/established channel and clear state. */
    public static void hardReset() {
        CpLog.i(TAG, "hardReset: unbinding + clearing state (source=" + source + " pending=" + pending + ")");
        try {
            if ("shizuku".equals(source) || "shizuku".equals(pending)) {
                Shizuku.unbindUserService(shizukuArgs(), conn, false);
            }
            if ("dhizuku".equals(source) || "dhizuku".equals(pending)) {
                Dhizuku.unbindUserService(conn);
            }
        } catch (Throwable t) {
            CpLog.w(TAG, "hardReset unbind: " + t);
        }
        svc = null;
        source = null;
        svcUid = null;
        pending = null;
        attempts = 0;
    }
}
