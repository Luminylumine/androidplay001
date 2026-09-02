import android.os.IBinder;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.lang.reflect.Method;

/**
 * 迷你显示电源控制工具（以 shell 身份经 app_process 运行，等价于 Shizuku 宿主进程）。
 *
 * 用途：在无 root、无 Extinguish、无 Shizuku 的前提下，通过纯 ADB 对主显示执行
 * "完全断电 / 恢复供电"（SurfaceControl.setDisplayPowerMode，隐藏 API 反射调用）。
 *
 * 背景：MIUI 的安全机制会在系统点亮屏幕时强制把面板拉回 ON，一次性断电无法持久。
 * 因此主工作模式是 15 秒周期巡检守护进程：检测到面板不是 OFF 就重新断电。
 *
 * 运行方式（设备端）：
 *   CLASSPATH=/data/local/tmp/mini_display.dex app_process /data/local/tmp MiniDisplay <cmd>
 *
 * 命令：
 *   start → 启动 15s 周期巡检守护进程（先断电一次，之后每 15s 检测面板状态，非 OFF 则重新断电；
 *           进程常驻直到被 stop 杀掉。PC 端应以 "start >/data/local/tmp/mini_display.log 2>&1 </dev/null &"
 *           后台方式启动，避免 adb shell 会话被占住）
 *   stop  → 杀掉巡检守护进程并把面板恢复为正常供电（守护进程不存在时也只恢复面板，可安全重复调用）
 *   off   → 一次性面板完全断电（POWER_MODE_OFF=0），立即退出（用于 Doze 恢复等补救场景）
 *   on    → 一次性面板恢复供电（POWER_MODE_NORMAL=2），立即退出
 *
 * display token 获取按 Android 版本分派（与 Extinguish 的实现一致）：
 *   A14+      : services.jar → DisplayControl.getPhysicalDisplayIds()[0] → getPhysicalDisplayToken(id)
 *               （必须先 Runtime.loadLibrary0 加载 android_servers 原生库）
 *   A10~A13   : SurfaceControl.getInternalDisplayToken()
 *   < A10     : SurfaceControl.getBuiltInDisplay(0)
 *
 * 输出：首行 "OK ..."（退出码 0）或 "FAIL ..."（退出码 1），便于 PC 端解析。
 * 注意：进程退出时可能 segfault，退出码不可靠，以输出文本为准。
 * 守护进程存活标记：pid 写入 /data/local/tmp/.mini_display.pid（stop 据此杀进程，
 * 并校验 /proc/<pid>/cmdline 含 MiniDisplay 以防 pid 被复用误杀）。
 */
public class MiniDisplay {
    private static final int POWER_MODE_OFF = 0;
    private static final int POWER_MODE_NORMAL = 2;
    private static final long INTERVAL_MS = 15 * 1000L;
    private static final String PID_FILE = "/data/local/tmp/.mini_display.pid";

    public static void main(String[] args) {
        try {
            if (args.length < 1) {
                System.out.println("FAIL usage: MiniDisplay start|stop|off|on");
                System.exit(1);
                return;
            }
            String cmd = args[0].toLowerCase();
            if (!cmd.equals("start") && !cmd.equals("stop")
                    && !cmd.equals("off") && !cmd.equals("on")) {
                System.out.println("FAIL usage: MiniDisplay start|stop|off|on");
                System.exit(1);
                return;
            }

            int sdk = android.os.Build.VERSION.SDK_INT;
            Class<?> sc = Class.forName("android.view.SurfaceControl");
            Method setMode = sc.getMethod("setDisplayPowerMode", IBinder.class, int.class);
            Method getMode = findGetMode(sc);

            IBinder token = resolveToken(sdk, sc);
            String how = describeHow(sdk);

            if ("start".equals(cmd)) {
                int existing = readPidFile();
                if (isDaemonPid(existing)) {
                    System.out.println("OK daemon already running pid=" + existing + " " + how);
                    return;
                }
                int myPid = android.os.Process.myPid();
                writePidFile(myPid);
                setMode.invoke(null, token, POWER_MODE_OFF);
                System.out.println("OK daemon started pid=" + myPid + " " + how
                        + " interval=" + INTERVAL_MS + "ms");
                // 巡检循环：每 15s 检查面板电源状态，非 OFF 则重新断电
                //（对抗 MIUI 在系统点亮屏幕时强制把面板拉回 ON 的安全机制）
                while (true) {
                    Thread.sleep(INTERVAL_MS);
                    boolean needReoff;
                    if (getMode == null) {
                        // 该版本没有 getDisplayPowerMode 时降级为无条件重设 OFF
                        //（面板已 OFF 时重复设置是空操作，安全）
                        needReoff = true;
                    } else {
                        Object v = getMode.invoke(null, token);
                        needReoff = !(v instanceof Integer) || ((Integer) v) != POWER_MODE_OFF;
                    }
                    if (needReoff) {
                        setMode.invoke(null, token, POWER_MODE_OFF);
                        System.out.println("WATCH re-off @" + System.currentTimeMillis());
                    }
                }
            } else if ("stop".equals(cmd)) {
                int pid = readPidFile();
                if (isDaemonPid(pid)) {
                    Runtime.getRuntime().exec(new String[] { "kill", Integer.toString(pid) });
                    boolean gone = false;
                    for (int i = 0; i < 30; i++) {
                        if (!isDaemonPid(pid)) { gone = true; break; }
                        Thread.sleep(100);
                    }
                    if (!gone) {
                        throw new IllegalStateException("daemon pid=" + pid + " still alive after kill");
                    }
                }
                new File(PID_FILE).delete();
                setMode.invoke(null, token, POWER_MODE_NORMAL);
                System.out.println("OK daemon stopped, panel restored to NORMAL " + how);
            } else {
                boolean off = "off".equals(cmd);
                setMode.invoke(null, token, off ? POWER_MODE_OFF : POWER_MODE_NORMAL);
                System.out.println("OK " + how + " sdk=" + sdk + " mode=" + (off ? "OFF" : "NORMAL"));
            }
        } catch (Throwable t) {
            System.out.println("FAIL " + t);
            t.printStackTrace(System.out);
            System.exit(1);
        }
    }

    /** 反射取 getDisplayPowerMode(token)（当前面板电源模式）；个别版本无此方法时返回 null。 */
    private static Method findGetMode(Class<?> sc) {
        try {
            return sc.getMethod("getDisplayPowerMode", IBinder.class);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /** 按 Android 版本分派获取主显示 token。 */
    private static IBinder resolveToken(int sdk, Class<?> sc) throws Exception {
        IBinder token;
        if (sdk >= 34) {
            token = tokenViaServicesJar();
        } else if (sdk >= 29) {
            token = (IBinder) sc.getMethod("getInternalDisplayToken").invoke(null);
        } else {
            token = (IBinder) sc.getMethod("getBuiltInDisplay", int.class).invoke(null, 0);
        }
        if (token == null) {
            throw new IllegalStateException("display token is null via " + describeHow(sdk));
        }
        return token;
    }

    private static String describeHow(int sdk) {
        if (sdk >= 34) return "DisplayControl(A14+)";
        if (sdk >= 29) return "getInternalDisplayToken(A10-13)";
        return "getBuiltInDisplay(<A10)";
    }

    /** pid 存活且是 MiniDisplay 守护进程（防 pid 复用误杀其他进程）。 */
    private static boolean isDaemonPid(int pid) {
        if (pid <= 0) return false;
        File f = new File("/proc/" + pid + "/cmdline");
        if (!f.exists()) return false;
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] buf = new byte[512];
            int n = in.read(buf);
            if (n <= 0) return false;
            String cmdline = new String(buf, 0, n, "UTF-8");
            return cmdline.indexOf("MiniDisplay") >= 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (in != null) { try { in.close(); } catch (Exception ignored) { } }
        }
    }

    private static int readPidFile() {
        File f = new File(PID_FILE);
        if (!f.exists()) return -1;
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] buf = new byte[32];
            int n = in.read(buf);
            if (n <= 0) return -1;
            return Integer.parseInt(new String(buf, 0, n, "UTF-8").trim());
        } catch (Exception e) {
            return -1;
        } finally {
            if (in != null) { try { in.close(); } catch (Exception ignored) { } }
        }
    }

    private static void writePidFile(int pid) {
        FileWriter w = null;
        try {
            w = new FileWriter(PID_FILE);
            w.write(Integer.toString(pid));
        } catch (Exception ignored) {
        } finally {
            if (w != null) { try { w.close(); } catch (Exception ignored) { } }
        }
    }

    /** A14+：加载 services.jar 反射 DisplayControl 取主物理显示 token。 */
    private static IBinder tokenViaServicesJar() throws Exception {
        Class<?> factoryCls = Class.forName("com.android.internal.os.ClassLoaderFactory");
        Method create = factoryCls.getDeclaredMethod("createClassLoader",
                String.class, String.class, String.class, ClassLoader.class,
                int.class, boolean.class, String.class);
        ClassLoader cl = (ClassLoader) create.invoke(null,
                "/system/framework/services.jar", null, null,
                ClassLoader.getSystemClassLoader(), 0, true, (String) null);

        Class<?> dc = cl.loadClass("com.android.server.display.DisplayControl");
        // DisplayControl 的 native 方法依赖 android_servers 原生库，必须先加载
        Method load0 = Runtime.class.getDeclaredMethod("loadLibrary0", Class.class, String.class);
        load0.setAccessible(true);
        load0.invoke(Runtime.getRuntime(), dc, "android_servers");

        long[] ids = (long[]) dc.getMethod("getPhysicalDisplayIds").invoke(null);
        if (ids.length == 0) {
            throw new IllegalStateException("no physical display id");
        }
        return (IBinder) dc.getMethod("getPhysicalDisplayToken", long.class).invoke(null, ids[0]);
    }
}
