package com.sysmon.app.adb;

import android.content.Context;

import com.sysmon.app.Prefs;
import com.sysmon.app.SysLog;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 设备内 ADB 通道：发现本机 adbd 端口并建立连接。
 *
 * 发现顺序（参考调研结论）：
 *   1. 缓存的成功端点
 *   2. 127.0.0.1:5555（legacy adb tcpip）
 *   3. 用户触发的有界并发扫描（无线调试随机端口）
 *
 * 连接成功后提供 exec()/readFiles()，与 Shizuku 通道接口一致。
 */
public final class AdbWireless {

    private static final String TAG = "SysMon";
    private static final int SCAN_START = 30000;
    private static final int SCAN_END = 49999;
    private static final int CONNECT_TIMEOUT_MS = 1500;

    private static Context ctx;
    private static Prefs prefs;
    private static AdbClient client;
    private static String connectedHost;
    private static int connectedPort;
    private static volatile boolean discovering = false;
    private static volatile boolean scanning = false;
    private static volatile String status = "ADB 未连接";
    private static final Object LOCK = new Object();

    private AdbWireless() {}

    public static void init(Context context) {
        ctx = context.getApplicationContext();
        prefs = new Prefs(ctx);
    }

    public static boolean isConnected() {
        synchronized (LOCK) {
            return client != null;
        }
    }

    public static String status() {
        synchronized (LOCK) {
            if (client != null) return "ADB 已连接 " + connectedHost + ":" + connectedPort;
        }
        return status;
    }

    /** 后台自动发现（不阻塞调用线程）。 */
    public static void ensureDiscover() {
        if (isConnected() || discovering) return;
        discovering = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    discover();
                } catch (Throwable t) {
                    SysLog.w("adb discover failed: " + t);
                } finally {
                    discovering = false;
                }
            }
        }, "adb-discover").start();
    }

    /** 用户触发的有界并发扫描。 */
    public static void scan() {
        if (scanning || isConnected()) return;
        scanning = true;
        status = "ADB 扫描中…";
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    doScan();
                } finally {
                    scanning = false;
                }
            }
        }, "adb-scan").start();
    }

    public static boolean isScanning() {
        return scanning;
    }

    private static void discover() {
        // 1. 缓存端点
        String cached = prefs == null ? null : prefs.adbEndpoint();
        if (cached != null) {
            int colon = cached.lastIndexOf(':');
            if (colon > 0) {
                String host = cached.substring(0, colon);
                int port = parseInt(cached.substring(colon + 1), -1);
                if (port > 0 && tryConnect(host, port)) return;
            }
        }
        // 2. 127.0.0.1:5555
        if (tryConnect("127.0.0.1", 5555)) return;
        status = "ADB 未发现（可手动扫描）";
    }

    private static void doScan() {
        // 先试 5555
        if (tryConnect("127.0.0.1", 5555)) return;

        List<Integer> open = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(32);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int p = SCAN_START; p <= SCAN_END; p++) {
            final int port = p;
            futures.add(pool.submit(new java.util.concurrent.Callable<Boolean>() {
                @Override
                public Boolean call() {
                    return tcpOpen("127.0.0.1", port, 60);
                }
            }));
        }
        int idx = SCAN_START;
        for (Future<Boolean> f : futures) {
            try {
                if (f.get(2, TimeUnit.SECONDS)) open.add(idx);
            } catch (Throwable ignored) {}
            idx++;
        }
        pool.shutdownNow();

        SysLog.i("adb scan open ports: " + open);
        for (int port : open) {
            if (tryConnect("127.0.0.1", port)) return;
        }
        status = "ADB 扫描完成，未发现 adbd";
    }

    private static boolean tcpOpen(String host, int port, int timeoutMs) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            try { s.close(); } catch (Throwable ignored) {}
        }
    }

    /** 完整握手 + 探针验证。 */
    private static boolean tryConnect(String host, int port) {
        AdbKey key;
        try {
            key = new AdbKey();
        } catch (Throwable t) {
            SysLog.w("adb key init failed: " + t);
            return false;
        }
        if (!key.available()) {
            SysLog.w("adb key not available, skip connect " + host + ":" + port);
            return false;
        }
        AdbClient c = new AdbClient(host, port, key);
        try {
            SysLog.d("adb connect " + host + ":" + port + " starting, pubkey=" + key.adbPublicKeyString());
            c.connect();
            String out = c.exec("id -u");
            if (out == null || out.trim().isEmpty()) {
                c.close();
                return false;
            }
            String uid = out.trim();
            try {
                Integer.parseInt(uid);
            } catch (Exception e) {
                c.close();
                return false;
            }
            synchronized (LOCK) {
                if (client != null) client.close();
                client = c;
                connectedHost = host;
                connectedPort = port;
                status = "ADB 已连接 " + host + ":" + port + " (uid " + uid + ")";
            }
            if (prefs != null) prefs.setAdbEndpoint(host + ":" + port);
            SysLog.i("adb connected " + host + ":" + port + " uid=" + uid);
            return true;
        } catch (Throwable t) {
            SysLog.d("adb connect " + host + ":" + port + " failed: " + t);
            try { c.close(); } catch (Throwable ignored) {}
            return false;
        }
    }

    private static void drop() {
        synchronized (LOCK) {
            if (client != null) {
                try { client.close(); } catch (Throwable ignored) {}
                client = null;
            }
            connectedHost = null;
            connectedPort = -1;
            status = "ADB 连接断开";
        }
    }

    public static String exec(String cmd) {
        synchronized (LOCK) {
            if (client == null) return null;
            try {
                return client.exec(cmd);
            } catch (Throwable t) {
                SysLog.w("adb exec failed: " + t);
                drop();
                return null;
            }
        }
    }

    public static String readFiles(String[] paths) {
        synchronized (LOCK) {
            if (client == null) return null;
            try {
                return client.readFiles(paths);
            } catch (Throwable t) {
                SysLog.w("adb readFiles failed: " + t);
                drop();
                return null;
            }
        }
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }
}
