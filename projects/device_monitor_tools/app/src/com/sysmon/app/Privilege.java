package com.sysmon.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.IBinder;

import com.rosan.dhizuku.api.Dhizuku;
import com.rosan.dhizuku.api.DhizukuRequestPermissionListener;
import com.rosan.dhizuku.api.DhizukuUserServiceArgs;
import com.rosan.dhizuku.shared.DhizukuVariables;
import com.sysmon.aidl.ISysShell;
import com.sysmon.app.adb.AdbWireless;

import java.io.BufferedReader;
import java.io.InputStreamReader;

import moe.shizuku.server.IShizukuService;
import moe.shizuku.server.IRemoteProcess;

import rikka.shizuku.Shizuku;

/**
 * 特权命令通道。
 *
 * 优先级：
 *   1. Shizuku  - SysShellService 运行在 shizuku_server（shell uid 2000），真正的 shell 通道。
 *   2. Dhizuku  - Device Owner 托管服务（Dhizuku 自身 uid，非 shell），仅作降级 fallback。
 *   3. ADB      - 设备内自建 adb 客户端连本机 adbd（无线调试/5555），异步发现。
 *
 * 上层采样器只调用 exec()/readFiles()，不关心底层通道。
 */
public final class Privilege {

    private static final String TAG = "SysMon";
    private static final ComponentName SVC =
            new ComponentName("com.sysmon.app", "com.sysmon.app.SysShellService");
    private static final long BIND_TIMEOUT_MS = 15000;
    private static final long RETRY_COOLDOWN_MS = 30000;

    private static ISysShell svc = null;
    private static String source = null;
    private static String pending = null;
    private static long pendingAt = 0;
    private static String svcUid = null;
    private static Context appCtx = null;
    private static int attempts = 0;
    private static long lastAttemptAt = 0;
    private static boolean shizukuRemoteAvailable = false;

    private static final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            String src = pending;
            SysLog.w("conn.onServiceConnected name=" + name + " src=" + src
                    + " binder=" + (service != null) + " binderClass="
                    + (service != null ? service.getClass().getName() : "null"));
            if (src == null) {
                SysLog.w("onServiceConnected ignored (no in-flight bind)");
                return;
            }
            if (service == null) {
                SysLog.e("onServiceConnected: service is null via " + src);
                pending = null;
                return;
            }
            ISysShell s = ISysShell.Stub.asInterface(service);
            if (s == null) {
                SysLog.e("onServiceConnected: asInterface returned null via " + src);
                pending = null;
                return;
            }
            String probe = null;
            try {
                probe = s.exec("id -u");
                SysLog.w("probe result via " + src + ": "
                        + (probe != null ? probe.substring(0, Math.min(probe.length(), 300)) : "null"));
            } catch (Exception ex) {
                SysLog.e("probe exec threw via " + src + ": " + ex, ex);
            }
            if (probe != null && probe.startsWith("rc=0")) {
                int nl = probe.indexOf('\n');
                String uid = (nl >= 0) ? probe.substring(nl + 1).trim() : "?";
                svc = s;
                source = src;
                svcUid = uid;
                pending = null;
                attempts = 0;
                SysLog.w("shell channel up via " + src + " uid=" + uid);
            } else {
                pending = null;
                SysLog.w("shell probe FAILED via " + src + " probe=" + probe);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            SysLog.w("conn.onServiceDisconnected established=" + source + " pending=" + pending);
            svc = null;
            source = null;
            svcUid = null;
            pending = null;
        }
    };

    private Privilege() {}

    public static void init(Context ctx) {
        appCtx = ctx.getApplicationContext();
        AdbWireless.init(appCtx);
        try {
            Shizuku.addBinderReceivedListenerSticky(new Shizuku.OnBinderReceivedListener() {
                @Override
                public void onBinderReceived() {
                    SysLog.i("shizuku binder received");
                    attempts = 0;
                    ensure();
                }
            });
            Shizuku.addBinderDeadListener(new Shizuku.OnBinderDeadListener() {
                @Override
                public void onBinderDead() {
                    SysLog.w("shizuku binder dead");
                    if ("shizuku".equals(source) || "shizuku".equals(pending)) {
                        svc = null; source = null; svcUid = null; pending = null;
                    }
                    attempts = 0;
                    ensure();
                }
            });
            Shizuku.addRequestPermissionResultListener(new Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    SysLog.i("shizuku permission result req=" + requestCode + " grant=" + grantResult);
                    attempts = 0;
                    ensure();
                }
            });
        } catch (Throwable t) {
            SysLog.w("shizuku listeners: " + t);
        }
    }

    /** 尝试建立通道。返回当前（进行中或已建立）来源或 null。 */
    public static String ensure() {
        if (svc != null) return source;
        if (shizukuRemoteAvailable) return "shizuku-remote";
        if (pending != null) {
            if (System.currentTimeMillis() - pendingAt > BIND_TIMEOUT_MS) {
                SysLog.w("bind via " + pending + " timed out (attempt#" + attempts + "), will retry");
                pending = null;
                // 不重置 attempts - 让超时也计入失败计数，防止无限重试
                lastAttemptAt = System.currentTimeMillis();
            } else {
                return null;
            }
        }
        // 冷却期：短时间内多次失败后等待一段时间再重试
        if (attempts >= 6 && System.currentTimeMillis() - lastAttemptAt < RETRY_COOLDOWN_MS) {
            return null;
        }
        attempts++;
        lastAttemptAt = System.currentTimeMillis();

        // 优先尝试 ShizukuRemoteProcess（通过 IShizukuService.newProcess）
        if (tryShizukuRemote()) {
            return "shizuku-remote";
        }
        // 然后尝试绑定用户服务
        if (tryShizuku()) {
            pending = "shizuku";
            pendingAt = System.currentTimeMillis();
            return pending;
        }
        if (tryDhizuku()) {
            pending = "dhizuku";
            pendingAt = System.currentTimeMillis();
            return pending;
        }
        // ADB 异步发现（不阻塞）
        AdbWireless.ensureDiscover();
        return null;
    }

    private static boolean tryShizuku() {
        try {
            boolean ping = Shizuku.pingBinder();
            int perm = ping ? Shizuku.checkSelfPermission() : -1;
            int uid = ping ? Shizuku.getUid() : -1;
            SysLog.w("tryShizuku ping=" + ping + " perm=" + perm + " serverUid=" + uid
                    + " pkg=" + (appCtx != null ? appCtx.getPackageName() : "null"));
            if (!ping || perm != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                SysLog.w("tryShizuku: not ready ping=" + ping + " perm=" + perm);
                return false;
            }
            Shizuku.UserServiceArgs args = shizukuArgs();
            SysLog.w("tryShizuku: binding user service svc=" + SVC + " args=" + args);
            Shizuku.bindUserService(args, conn);
            SysLog.w("tryShizuku: bindUserService invoked (async, waiting onServiceConnected)");
            return true;
        } catch (Throwable t) {
            SysLog.e("shizuku bind: " + t, t);
            return false;
        }
    }

    private static boolean tryShizukuRemote() {
        try {
            boolean ping = Shizuku.pingBinder();
            int perm = ping ? Shizuku.checkSelfPermission() : -1;
            SysLog.w("tryShizukuRemote ping=" + ping + " perm=" + perm);
            if (!ping || perm != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return false;
            }
            // 获取 IShizukuService 并创建远程进程
            android.os.IBinder binder = Shizuku.getBinder();
            if (binder == null) {
                SysLog.w("tryShizukuRemote: binder is null");
                return false;
            }
            IShizukuService service = IShizukuService.Stub.asInterface(binder);
            if (service == null) {
                SysLog.w("tryShizukuRemote: service is null");
                return false;
            }
            // 测试 newProcess 是否可用
            String[] cmd = {"/system/bin/sh", "-c", "id -u"};
            IRemoteProcess remoteProcess = service.newProcess(cmd, null, null);
            if (remoteProcess == null) {
                SysLog.w("tryShizukuRemote: newProcess returned null");
                return false;
            }
            // 读取输出
            android.os.ParcelFileDescriptor pfd = remoteProcess.getInputStream();
            BufferedReader r = new BufferedReader(new InputStreamReader(new android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd)));
            String line = r.readLine();
            r.close();
            int rc = remoteProcess.waitFor();
            SysLog.w("tryShizukuRemote: id -u rc=" + rc + " output=" + line);
            if (rc == 0 && line != null && line.trim().equals("2000")) {
                shizukuRemoteAvailable = true;
                svcUid = "2000";
                SysLog.w("shizuku-remote channel up uid=2000");
                return true;
            }
            SysLog.w("tryShizukuRemote: probe failed rc=" + rc + " line=" + line);
            return false;
        } catch (Throwable t) {
            SysLog.w("shizuku-remote: " + t);
            return false;
        }
    }

    private static Shizuku.UserServiceArgs shizukuArgs() {
        int vc = 1;
        try {
            vc = appCtx.getPackageManager().getPackageInfo("com.sysmon.app", 0).versionCode;
        } catch (Throwable ignored) {}
        return new Shizuku.UserServiceArgs(SVC)
                .processNameSuffix("sysshell")
                .version(vc);
    }

    private static boolean tryDhizuku() {
        try {
            boolean inited = Dhizuku.init(appCtx);
            boolean granted = inited && Dhizuku.isPermissionGranted();
            SysLog.i("tryDhizuku init=" + inited + " granted=" + granted);
            if (!granted) return false;
            return Dhizuku.bindUserService(new DhizukuUserServiceArgs(SVC), conn);
        } catch (Throwable t) {
            SysLog.w("dhizuku bind: " + t);
            return false;
        }
    }

    public static boolean available() {
        return svc != null || shizukuRemoteAvailable || AdbWireless.isConnected();
    }

    /** 当前生效通道名：shizuku | shizuku-remote | dhizuku | adb | null */
    public static String source() {
        if (svc != null) return source;
        if (shizukuRemoteAvailable) return "shizuku-remote";
        if (AdbWireless.isConnected()) return "adb";
        return null;
    }

    public static String svcUid() {
        return svcUid;
    }

    /** 执行命令。返回 "rc=N\n<output>" 或 null。 */
    public static String exec(String cmd) {
        ISysShell s = svc;
        if (s != null) {
            try {
                return s.exec(cmd);
            } catch (Exception e) {
                SysLog.w("exec threw: " + e);
                return null;
            }
        }
        if (shizukuRemoteAvailable) {
            return execViaRemote(cmd);
        }
        if (AdbWireless.isConnected()) {
            return AdbWireless.exec(cmd);
        }
        return null;
    }

    /** 批量读文件（shell 通道内直接 FileInputStream，避免 spawn 进程）。 */
    public static String readFiles(String[] paths) {
        ISysShell s = svc;
        if (s != null) {
            try {
                return s.readFiles(paths);
            } catch (Exception e) {
                SysLog.w("readFiles threw: " + e);
                return null;
            }
        }
        if (shizukuRemoteAvailable) {
            StringBuilder sb = new StringBuilder();
            for (String path : paths) {
                sb.append("=== ").append(path).append(" ===\n");
                String result = execViaRemote("cat " + path);
                if (result != null) {
                    // 解析 rc 和 output
                    int nl = result.indexOf('\n');
                    if (nl >= 0) {
                        String rest = result.substring(nl + 1);
                        sb.append(rest);
                    }
                }
                if (sb.length() > 200000) break;
            }
            return sb.toString();
        }
        if (AdbWireless.isConnected()) {
            return AdbWireless.readFiles(paths);
        }
        return null;
    }

    /** 通过 ShizukuRemoteProcess 执行命令 */
    private static String execViaRemote(String cmd) {
        try {
            android.os.IBinder binder = Shizuku.getBinder();
            if (binder == null) return "rc=-1\nbinder null";
            IShizukuService service = IShizukuService.Stub.asInterface(binder);
            if (service == null) return "rc=-1\nservice null";
            String[] cmdArr = {"/system/bin/sh", "-c", cmd};
            IRemoteProcess remoteProcess = service.newProcess(cmdArr, null, null);
            if (remoteProcess == null) return "rc=-1\nnewProcess null";
            StringBuilder out = new StringBuilder();
            android.os.ParcelFileDescriptor stdoutFd = remoteProcess.getInputStream();
            BufferedReader b = new BufferedReader(new InputStreamReader(new android.os.ParcelFileDescriptor.AutoCloseInputStream(stdoutFd), "UTF-8"));
            String line;
            int lines = 0;
            while ((line = b.readLine()) != null && lines < 600) {
                out.append(line).append('\n');
                lines++;
            }
            b.close();
            android.os.ParcelFileDescriptor stderrFd = remoteProcess.getErrorStream();
            if (stderrFd != null) {
                BufferedReader e = new BufferedReader(new InputStreamReader(new android.os.ParcelFileDescriptor.AutoCloseInputStream(stderrFd), "UTF-8"));
                while ((line = e.readLine()) != null && out.length() < 40000) {
                    out.append("[e] ").append(line).append('\n');
                }
                e.close();
            }
            int rc = remoteProcess.waitFor();
            String s = out.toString();
            if (s.length() > 40000) s = s.substring(0, 40000) + "\n…(truncated)";
            return "rc=" + rc + "\n" + s;
        } catch (Exception ex) {
            SysLog.w("execViaRemote exception: " + ex);
            return "rc=-1\nexec 异常: " + ex;
        }
    }

    // ---------- 状态 / 引导 ----------

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

    /** 1=ok 0=not granted -1=not installed */
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

    public static void requestDhizukuPermission() {
        try {
            Dhizuku.init(appCtx);
            Dhizuku.requestPermission(new DhizukuRequestPermissionListener() {
                @Override
                public void onRequestPermission(int grantResult) {
                    SysLog.i("dhizuku permission result: " + grantResult);
                    attempts = 0;
                    ensure();
                }
            });
        } catch (Throwable t) {
            SysLog.w("dhizuku requestPermission: " + t);
        }
    }

    public static void requestShizukuPermission() {
        try {
            Shizuku.requestPermission(1001);
        } catch (Throwable t) {
            SysLog.w("shizuku requestPermission: " + t);
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
            SysLog.w("openPkg: " + t);
        }
    }

    public static void openWirelessDebugging() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                try {
                    Class<?> c = Class.forName("android.provider.Settings");
                    java.lang.reflect.Method m = c.getMethod("buildWirelessDebuggingIntent");
                    android.content.Intent i = (android.content.Intent) m.invoke(null);
                    appCtx.startActivity(i);
                    return;
                } catch (Throwable ignored) {}
            }
            appCtx.startActivity(new android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        } catch (Throwable t) {
            SysLog.w("openWirelessDebugging: " + t);
        }
    }

    public static void reset() {
        attempts = 0;
    }

    public static void hardReset() {
        SysLog.i("hardReset");
        try {
            if ("shizuku".equals(source) || "shizuku".equals(pending)) {
                Shizuku.unbindUserService(shizukuArgs(), conn, false);
            }
            if ("dhizuku".equals(source) || "dhizuku".equals(pending)) {
                Dhizuku.unbindUserService(conn);
            }
        } catch (Throwable t) {
            SysLog.w("hardReset unbind: " + t);
        }
        svc = null;
        source = null;
        svcUid = null;
        pending = null;
        shizukuRemoteAvailable = false;
        attempts = 0;
    }
}
