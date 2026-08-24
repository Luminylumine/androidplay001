package com.sysmon.app.collect;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.TrafficStats;
import android.os.BatteryManager;
import android.os.SystemClock;

import com.sysmon.app.Prefs;
import com.sysmon.app.SysLog;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 无权限模式采样器：普通 untrusted_app 身份直接读 /proc 与 BatteryManager API。
 * CPU 与网络需要两次采样做差分，内部保存上一次快照。
 */
public class Sampler {

    private long[][] prevCpuTotal = null;   // [total, core0, core1, ...] 每项 [user,nice,system,idle,iowait,irq,softirq,steal]
    private long prevCpuSum = -1;
    private long prevNetRx = -1, prevNetTx = -1;
    private long prevNetTs = -1;

    private final Context ctx;
    private final Prefs prefs;

    public Sampler(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.prefs = new Prefs(ctx);
    }

    public SysData sample() {
        SysData d = new SysData();
        d.ts = System.currentTimeMillis();
        readCpu(d);
        readMem(d);
        readLoad(d);
        readNet(d);
        readCpuFreq(d);
        readThermal(d);
        readBattery(d);
        // 诊断日志：记录采样结果摘要
        SysLog.w("sample: cpu=" + (d.hasCpu() ? String.format("%.0f%%", d.cpuTotal) : "N/A")
                + " mem=" + (d.hasMem() ? (d.memTotal / 1024 + "MB") : "N/A")
                + " batt=" + (d.battLevel >= 0 ? (d.battLevel + "%") : "N/A")
                + " netRx=" + (Float.isNaN(d.netRxRate) ? "N/A" : String.format("%.0fK/s", d.netRxRate))
                + " src=app");
        return d;
    }

    // ---------- CPU ----------
    private void readCpu(SysData d) {
        try {
            BufferedReader r = new BufferedReader(new FileReader("/proc/stat"));
            String line;
            List<long[]> cores = new ArrayList<>();
            long[] total = null;
            int idleIdx = 3;  // idle 在 /proc/stat 中的位置（默认第4个）

            while ((line = r.readLine()) != null) {
                if (line.startsWith("cpu")) {
                    String[] p = line.trim().split("\\s+");
                    // 支持 8-10 个字段 (user nice system idle iowait irq softirq steal [guest [guest_nice]])
                    int fieldCount = Math.min(p.length - 1, 10);
                    long[] v = new long[Math.max(fieldCount, 8)];
                    for (int i = 1; i <= fieldCount && i < p.length; i++) {
                        try { v[i - 1] = Long.parseLong(p[i]); } catch (Exception ignored) {}
                    }
                    if (p[0].equals("cpu")) total = v;
                    else cores.add(v);
                } else {
                    // 只有当我们已经读到 cpu 数据时才 break
                    if (total != null) break;
                }
            }
            r.close();
            if (total == null) return;

            int n = cores.size();
            float[] per = new float[n];
            for (int i = 0; i < n; i++) {
                per[i] = deltaPct(prevCpuTotal == null ? null : (i + 1 < prevCpuTotal.length ? prevCpuTotal[i + 1] : null), cores.get(i), idleIdx);
            }
            d.cpuPer = per;
            d.cpuTotal = deltaPct(prevCpuTotal == null ? null : prevCpuTotal[0], total, idleIdx);
            prevCpuTotal = new long[n + 1][];
            prevCpuTotal[0] = total;
            for (int i = 0; i < n; i++) prevCpuTotal[i + 1] = cores.get(i);
        } catch (Throwable t) {
            SysLog.w("readCpu: " + t);
        }
    }

    private float deltaPct(long[] prev, long[] cur, int idleIdx) {
        if (prev == null || cur == null) return Float.NaN;
        int len = Math.min(prev.length, cur.length);
        if (len <= idleIdx) return Float.NaN;

        // idle 包括 iowait（如果存在）
        long idleD = cur[idleIdx] - prev[idleIdx];
        if (idleIdx + 1 < len) idleD += (cur[idleIdx + 1] - prev[idleIdx + 1]);

        long totalD = 0;
        for (int i = 0; i < len; i++) totalD += cur[i] - prev[i];
        if (totalD <= 0) return Float.NaN;
        float busy = (totalD - idleD) * 100f / totalD;
        return busy < 0 ? 0 : (busy > 100 ? 100 : busy);
    }

    // ---------- 内存 ----------
    private void readMem(SysData d) {
        try {
            BufferedReader r = new BufferedReader(new FileReader("/proc/meminfo"));
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.trim().split("\\s+");
                if (p.length < 2) continue;
                long v = -1;
                try { v = Long.parseLong(p[1]); } catch (Exception ignored) {}
                if (p[0].equals("MemTotal:")) d.memTotal = v;
                else if (p[0].equals("MemFree:")) d.memFree = v;
                else if (p[0].equals("MemAvailable:")) d.memAvail = v;
                else if (p[0].equals("SwapTotal:")) d.swapTotal = v;
                else if (p[0].equals("SwapFree:")) d.swapFree = v;
            }
            r.close();
            if (d.memAvail < 0) d.memAvail = d.memFree;
        } catch (Throwable t) {
            SysLog.w("readMem: " + t);
        }
    }

    // ---------- 负载 / 运行时间 ----------
    private void readLoad(SysData d) {
        try {
            BufferedReader r = new BufferedReader(new FileReader("/proc/loadavg"));
            String[] p = r.readLine().trim().split("\\s+");
            r.close();
            if (p.length >= 3) {
                d.load1 = Float.parseFloat(p[0]);
                d.load5 = Float.parseFloat(p[1]);
                d.load15 = Float.parseFloat(p[2]);
            }
        } catch (Throwable t) {
            SysLog.w("readLoad: " + t.getMessage());
        }
        // 运行时间用 SystemClock（比 /proc/uptime 更稳）
        d.uptimeSec = SystemClock.elapsedRealtime() / 1000L;
    }

    // ---------- 网络（无权限用 TrafficStats，/proc/net/dev 仅作 bonus） ----------
    private void readNet(SysData d) {
        try {
            long rx = TrafficStats.getTotalRxBytes();
            long tx = TrafficStats.getTotalTxBytes();
            if (rx >= 0 && tx >= 0) {
                d.netRx = rx;
                d.netTx = tx;
                d.netIface = "total";
                long now = System.currentTimeMillis();
                if (prevNetRx >= 0 && prevNetTs > 0) {
                    long dt = now - prevNetTs;
                    if (dt > 0) {
                        d.netRxRate = Math.max(0, (rx - prevNetRx)) * 1000f / dt / 1024f;
                        d.netTxRate = Math.max(0, (tx - prevNetTx)) * 1000f / dt / 1024f;
                    }
                }
                prevNetRx = rx;
                prevNetTx = tx;
                prevNetTs = now;
                return;
            }
        } catch (Throwable ignored) {}
        // bonus: /proc/net/dev（部分 ROM 允许）
        try {
            BufferedReader r = new BufferedReader(new FileReader("/proc/net/dev"));
            String line;
            String bestIface = null;
            long bestRx = -1, bestTx = -1;
            while ((line = r.readLine()) != null) {
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                String iface = line.substring(0, colon).trim();
                String[] p = line.substring(colon + 1).trim().split("\\s+");
                if (p.length < 9) continue;
                if (iface.equals("lo")) continue;
                long rx = parseLong(p[0]);
                long tx = parseLong(p[8]);
                if (rx > bestRx) {
                    bestRx = rx; bestTx = tx; bestIface = iface;
                }
            }
            r.close();
            if (bestIface != null) {
                d.netIface = bestIface;
                d.netRx = bestRx;
                d.netTx = bestTx;
                long now = System.currentTimeMillis();
                if (prevNetRx >= 0 && prevNetTs > 0) {
                    long dt = now - prevNetTs;
                    if (dt > 0) {
                        d.netRxRate = Math.max(0, (bestRx - prevNetRx)) * 1000f / dt / 1024f;
                        d.netTxRate = Math.max(0, (bestTx - prevNetTx)) * 1000f / dt / 1024f;
                    }
                }
                prevNetRx = bestRx;
                prevNetTx = bestTx;
                prevNetTs = now;
            }
        } catch (Throwable t) {
            SysLog.w("readNet: " + t);
        }
    }

    private long parseLong(String s) {
        try { return Long.parseLong(s); } catch (Exception e) { return 0; }
    }

    // ---------- CPU 频率 ----------
    private void readCpuFreq(SysData d) {
        try {
            File dir = new File("/sys/devices/system/cpu");
            File[] cpus = dir.listFiles();
            if (cpus == null) {
                SysLog.w("readCpuFreq: /sys/devices/system/cpu not accessible (SELinux?)");
                return;
            }
            List<Integer> freqs = new ArrayList<>();
            int max = -1;
            for (File c : cpus) {
                String name = c.getName();
                if (!name.startsWith("cpu")) continue;
                String num = name.substring(3);
                try { Integer.parseInt(num); } catch (Exception e) { continue; }
                File f = new File(c, "cpufreq/scaling_cur_freq");
                String s = readFirstLine(f);
                if (s != null) {
                    try {
                        int khz = Integer.parseInt(s.trim());
                        freqs.add(khz / 1000);
                        if (khz / 1000 > max) max = khz / 1000;
                    } catch (Exception ignored) {}
                }
            }
            if (!freqs.isEmpty()) {
                d.cpuFreq = new int[freqs.size()];
                for (int i = 0; i < freqs.size(); i++) d.cpuFreq[i] = freqs.get(i);
                d.cpuMaxFreq = max;
            }
        } catch (Throwable t) {
            SysLog.w("readCpuFreq: " + t);
        }
    }

    // ---------- 温度 ----------
    private void readThermal(SysData d) {
        try {
            File dir = new File("/sys/class/thermal");
            File[] zones = dir.listFiles();
            if (zones == null) {
                SysLog.w("readThermal: /sys/class/thermal not accessible (SELinux?)");
                return;
            }
            for (File z : zones) {
                if (!z.getName().startsWith("thermal_zone")) continue;
                String type = readFirstLine(new File(z, "type"));
                String temp = readFirstLine(new File(z, "temp"));
                if (type == null || temp == null) continue;
                try {
                    float c = Float.parseFloat(temp.trim()) / 1000f;
                    String t = type.trim().toLowerCase();
                    if (t.contains("cpu") && Float.isNaN(d.cpuTemp)) d.cpuTemp = c;
                    else if (t.contains("batt") && Float.isNaN(d.battTempC)) d.battTempC = c;
                } catch (Exception ignored) {}
            }
        } catch (Throwable t) {
            SysLog.w("readThermal: " + t);
        }
    }

    private String readFirstLine(File f) {
        try {
            BufferedReader r = new BufferedReader(new FileReader(f));
            String s = r.readLine();
            r.close();
            return s;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------- 电池（BatteryManager API） ----------
    private void readBattery(SysData d) {
        try {
            IntentFilter f = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent b = ctx.registerReceiver(null, f);
            if (b == null) return;
            d.battLevel = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            d.battTemp = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
            d.battVolt = b.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
            d.battStatus = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            d.battHealth = b.getIntExtra(BatteryManager.EXTRA_HEALTH, -1);
            d.battPlugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
            d.battTech = b.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY);
            if (d.battTech == null) d.battTech = "";
            if (d.battTemp > 0) d.battTempC = d.battTemp / 10f;

            BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
            if (bm != null) {
                long cc = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
                if (cc != Long.MIN_VALUE) d.battChargeCounter = cc;
                int cur = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
                if (cur != Integer.MIN_VALUE) {
                    d.battCurrent = calibrateBattCurrent(cur);
                }
                int avg = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE);
                if (avg != Integer.MIN_VALUE) {
                    d.battCurrentAvg = calibrateBattCurrent(avg);
                }
                int full = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                if (full > 0) d.battCapacity = full;
            }

            // 日志诊断
            SysLog.w("readBattery: level=" + d.battLevel + " volt=" + d.battVolt
                    + " status=" + d.battStatus + " cur=" + d.battCurrent
                    + " charging=" + d.charging());

            // 功率计算：使用 battStatus 判断方向，电流用于计算大小
            if (d.battVolt > 0 && d.hasBattCurrent()) {
                float powerMag = d.battVolt * Math.abs(d.battCurrent) / 1000f; // mV*mA -> mW
                d.powerNow = powerMag;
                if (d.charging()) {
                    d.powerIn = powerMag;
                    SysLog.w("readBattery: charging powerIn=" + d.powerIn + " mW");
                } else {
                    d.powerOut = powerMag;
                    SysLog.w("readBattery: discharging powerOut=" + d.powerOut + " mW");
                }
            }
            // 整机消耗估算
            if (d.charging()) {
                if (!Float.isNaN(d.powerIn) && !Float.isNaN(d.powerNow)) {
                    d.powerPhone = Math.max(0, d.powerIn - d.powerNow);
                }
            } else if (!Float.isNaN(d.powerOut)) {
                d.powerPhone = d.powerOut;
            }
        } catch (Throwable t) {
            SysLog.w("readBattery: " + t);
        }
    }

    /**
     * 电池电流校准：raw → 统一 mA 约定（正=充电，负=放电）。
     * 优先使用Prefs中保存的校准结果（polarity + scale）。
     * 没有校准时保留原有自适应：|raw|>10000 → µA → /1000转mA。
     */
    private int calibrateBattCurrent(int raw) {
        int pol = prefs.battPolarity();
        float scale = prefs.battCurrentScale();
        if (pol != 0 && !Float.isNaN(scale)) {
            // 有校准结果：先按scale把raw转成mA，再乘极性
            // scale定义：raw × scale = µA → mA = raw × scale / 1000
            // polarity：1=标准(raw正=充电), -1=反号(raw正=放电)
            float uA = raw * scale;
            float mA = uA / 1000f;
            return Math.round(mA * pol);
        }
        // fallback：原有自适应
        return Math.abs(raw) > 10000 ? raw / 1000 : raw;
    }
}
