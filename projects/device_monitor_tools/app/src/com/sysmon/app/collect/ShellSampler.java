package com.sysmon.app.collect;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

import com.sysmon.app.Prefs;
import com.sysmon.app.Privilege;
import com.sysmon.app.SysLog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * shell 通道采样器：经 Privilege（Shizuku/Dhizuku/ADB）批量读取。
 * - /proc 文件用 readFiles（一次 binder 调用，不 spawn 进程）
 * - power_supply / cpufreq / dumpsys battery 合并为一次 exec
 * - thermalservice / gpu 每 5s 一次（慢 Provider 分频）
 * - BatteryManager API 作为电池数据的备用方案
 */
public class ShellSampler {

    private long[][] prevCpuTotal = null;
    private long prevNetRx = -1, prevNetTx = -1, prevNetTs = -1;
    private long lastSlowTs = 0;
    private Context appContext = null;
    private Prefs prefs = null;

    public void setContext(Context ctx) {
        this.appContext = ctx.getApplicationContext();
        this.prefs = new Prefs(ctx);
    }

    public SysData sample() {
        SysData d = new SysData();
        d.ts = System.currentTimeMillis();
        d.source = "shell";

        // 1. /proc 批量读（无进程 spawn）
        String[] paths = {
                "/proc/stat", "/proc/meminfo", "/proc/loadavg", "/proc/net/dev"
        };
        String raw = Privilege.readFiles(paths);
        if (raw != null) {
            parseProc(raw, d);
        }

        // 2. power_supply + cpufreq + dumpsys battery（一次 exec）
        String cmd = "echo '===PS===';"
                + "for d in /sys/class/power_supply/*; do echo \"--- $d\";"
                + " for f in type online status health capacity voltage_now current_now current_avg charge_now charge_full energy_now power_now temp cycle_count technology; do"
                + " printf '%s=' \"$f\"; cat \"$d/$f\" 2>/dev/null || echo X; done; done;"
                + "echo '===CPUFREQ===';"
                + "for d in /sys/devices/system/cpu/cpufreq/policy*; do echo \"--- $d\";"
                + " cat \"$d/related_cpus\" 2>/dev/null; cat \"$d/scaling_cur_freq\" 2>/dev/null; cat \"$d/cpuinfo_max_freq\" 2>/dev/null; done;"
                + "echo '===BATTERY==='; dumpsys battery";
        String out = Privilege.exec(cmd);
        if (out != null) {
            parsePowerAndBattery(out, d);
        }

        // 3. 使用 BatteryManager API 补充电池数据（特别是电流信息）
        //    即使 sysfs 成功获取了部分数据，也可能缺少电流信息
        if (appContext != null) {
            fillBatteryFromApi(d);
        }

        // 4. 慢 Provider：thermalservice + gpu（每 5s）
        long now = System.currentTimeMillis();
        if (now - lastSlowTs > 5000) {
            lastSlowTs = now;
            String slow = Privilege.exec(
                    "echo '===THERMAL==='; dumpsys thermalservice 2>/dev/null;"
                            + "echo '===GPU==='; dumpsys gpu 2>/dev/null;"
                            + "echo '===DEVFREQ===';"
                            + "for d in /sys/class/devfreq/*; do echo \"--- $d\";"
                            + " cat \"$d/name\" 2>/dev/null; cat \"$d/cur_freq\" 2>/dev/null; cat \"$d/load\" 2>/dev/null; done");
            if (slow != null) {
                parseSlow(slow, d);
            }
        } else {
            d.cpuTemp = lastCpuTemp;
            d.gpuUtil = lastGpuUtil;
            d.gpuFreq = lastGpuFreq;
        }

        computePower(d);
        return d;
    }

    /**
     * 轻量后台采样只读取 /proc/stat，避免为 CPU 占用率执行完整的 sysfs/dumpsys 采样。
     */
    public SysData sampleCpu() {
        SysData d = new SysData();
        d.ts = System.currentTimeMillis();
        d.source = "shell";
        String raw = Privilege.readFiles(new String[]{"/proc/stat"});
        if (raw != null) parseProc(raw, d);
        return d;
    }

    /** 通过 BatteryManager API 获取电池数据 */
    private void fillBatteryFromApi(SysData d) {
        try {
            BatteryManager bm = (BatteryManager) appContext.getSystemService(Context.BATTERY_SERVICE);
            if (bm == null) return;

            // 使用 Intent 获取基础电池信息（只在数据缺失时补充）
            Intent intent = appContext.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (intent != null) {
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                int voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
                int temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
                int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
                String technology = intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY);

                if (d.battLevel < 0 && level > 0) {
                    d.battLevel = level * 100 / scale;
                }
                if (d.battVolt <= 0 && voltage > 0) {
                    d.battVolt = voltage;
                }
                if (d.battTempC != d.battTempC && temperature > 0) {
                    d.battTemp = temperature;
                    d.battTempC = temperature / 10f;
                }
                if (d.battStatus < 0 && status >= 0) {
                    d.battStatus = status;
                }
                if (d.battPlugged < 0 && plugged >= 0) {
                    d.battPlugged = plugged;
                }
                if ((d.battTech == null || d.battTech.isEmpty()) && technology != null) {
                    d.battTech = technology;
                }
            }

            // 使用 BatteryManager API 获取电流和电荷计数器（总是尝试获取）
            // 自适应单位检测：如果值很大(>10000)，认为是µA，转换为mA
            long cc = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
            if (cc != Long.MIN_VALUE && cc > 0) d.battChargeCounter = cc;

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

            // 尝试获取功率（某些设备支持）
            try {
                int powerNow = bm.getIntProperty(6); // BATTERY_PROPERTY_POWER_NOW on some devices
                if (powerNow > 0) {
                    d.powerIn = powerNow / 1000f; // µW -> mW
                }
            } catch (Exception ignored) {}

        } catch (Exception e) {
            SysLog.w("fillBatteryFromApi failed: " + e);
        }
    }

    private float lastCpuTemp = Float.NaN;
    private float lastGpuUtil = Float.NaN;
    private int lastGpuFreq = -1;

    // ---------- /proc 解析 ----------
    private void parseProc(String raw, SysData d) {
        Map<String, String> sections = splitSections(raw);
        String stat = sections.get("/proc/stat");
        if (stat != null) parseStat(stat, d);
        String mem = sections.get("/proc/meminfo");
        if (mem != null) parseMem(mem, d);
        String load = sections.get("/proc/loadavg");
        if (load != null) {
            String[] p = load.trim().split("\\s+");
            if (p.length >= 3) {
                try {
                    d.load1 = Float.parseFloat(p[0]);
                    d.load5 = Float.parseFloat(p[1]);
                    d.load15 = Float.parseFloat(p[2]);
                } catch (Exception ignored) {}
            }
        }
        String net = sections.get("/proc/net/dev");
        if (net != null) parseNet(net, d);
    }

    private void parseStat(String stat, SysData d) {
        try {
            String[] lines = stat.split("\n");
            List<long[]> cores = new ArrayList<>();
            long[] total = null;
            int idleIdx = 3;  // idle 在 /proc/stat 中的位置（默认第4个）

            for (String line : lines) {
                if (!line.startsWith("cpu")) {
                    if (total != null) break;
                    continue;
                }
                String[] p = line.trim().split("\\s+");
                int fieldCount = Math.min(p.length - 1, 10);
                long[] v = new long[Math.max(fieldCount, 8)];
                for (int i = 1; i <= fieldCount && i < p.length; i++) {
                    try { v[i - 1] = Long.parseLong(p[i]); } catch (Exception ignored) {}
                }
                if (p[0].equals("cpu")) total = v;
                else cores.add(v);
            }
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
            SysLog.w("parseStat: " + t);
        }
    }

    private float deltaPct(long[] prev, long[] cur, int idleIdx) {
        if (prev == null || cur == null) return Float.NaN;
        int len = Math.min(prev.length, cur.length);
        if (len <= idleIdx) return Float.NaN;

        long idleD = cur[idleIdx] - prev[idleIdx];
        if (idleIdx + 1 < len) idleD += (cur[idleIdx + 1] - prev[idleIdx + 1]);

        long totalD = 0;
        for (int i = 0; i < len; i++) totalD += cur[i] - prev[i];
        if (totalD <= 0) return Float.NaN;
        float busy = (totalD - idleD) * 100f / totalD;
        return busy < 0 ? 0 : (busy > 100 ? 100 : busy);
    }

    private void parseMem(String mem, SysData d) {
        for (String line : mem.split("\n")) {
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
        if (d.memAvail < 0) d.memAvail = d.memFree;
    }

    private void parseNet(String net, SysData d) {
        String bestIface = null;
        long bestRx = -1, bestTx = -1;
        for (String line : net.split("\n")) {
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
    }

    // ---------- power_supply + cpufreq + dumpsys battery ----------
    private void parsePowerAndBattery(String out, SysData d) {
        String ps = section(out, "===PS===", "===CPUFREQ===");
        String cf = section(out, "===CPUFREQ===", "===BATTERY===");
        String batt = section(out, "===BATTERY===", null);
        if (ps != null) parsePowerSupply(ps, d);
        if (cf != null) parseCpufreq(cf, d);
        if (batt != null) parseDumpsysBattery(batt, d);
    }

    private void parsePowerSupply(String ps, SysData d) {
        // 按 "--- path" 分段
        String[] blocks = ps.split("--- ");
        for (String block : blocks) {
            if (block.trim().isEmpty()) continue;
            String[] lines = block.split("\n");
            String path = lines[0].trim();
            Map<String, String> kv = new HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int eq = lines[i].indexOf('=');
                if (eq > 0) {
                    String k = lines[i].substring(0, eq).trim();
                    String v = lines[i].substring(eq + 1).trim();
                    if (!v.equals("X")) kv.put(k, v);
                }
            }
            String type = kv.get("type");
            if (type == null) continue;
            if (type.equals("Battery")) {
                d.battLevel = parseInt(kv.get("capacity"), -1);
                int temp = parseInt(kv.get("temp"), -1);
                if (temp > 0) { d.battTemp = temp; d.battTempC = temp / 10f; }
                long voltUv = parseLong(kv.get("voltage_now"));
                if (voltUv > 0) d.battVolt = (int) (voltUv / 1000);
                long curRaw = parseLong(kv.get("current_now"));
                if (curRaw != 0) {
                    // sysfs current_now 标准单位 µA；经校准层转 mA
                    d.battCurrent = calibrateBattCurrent((int) curRaw);
                }
                long curAvgRaw = parseLong(kv.get("current_avg"));
                if (curAvgRaw != 0) {
                    d.battCurrentAvg = calibrateBattCurrent((int) curAvgRaw);
                }
                long chargeFull = parseLong(kv.get("charge_full"));
                if (chargeFull > 0) d.battCapacity = (int) (chargeFull / 1000);
                long chargeNow = parseLong(kv.get("charge_now"));
                if (chargeNow > 0) d.battChargeCounter = chargeNow;
                d.battTech = kv.get("technology") == null ? "" : kv.get("technology");
                d.battStatus = mapStatus(kv.get("status"));
                d.battHealth = mapHealth(kv.get("health"));
            } else {
                // 充电器：online=1 时取输入功率
                if ("1".equals(kv.get("online"))) {
                    long voltUv = parseLong(kv.get("voltage_now"));
                    long curUa = parseLong(kv.get("current_now"));
                    long powerUw = parseLong(kv.get("power_now"));
                    if (powerUw > 0) {
                        d.powerIn = powerUw / 1000f; // µW -> mW
                    } else if (voltUv > 0 && curUa > 0) {
                        d.powerIn = voltUv * curUa / 1e9f; // µV*µA -> mW
                    }
                }
            }
        }
    }

    private void parseCpufreq(String cf, SysData d) {
        String[] blocks = cf.split("--- ");
        List<Integer> freqs = new ArrayList<>();
        int max = -1;
        for (String block : blocks) {
            if (block.trim().isEmpty()) continue;
            String[] lines = block.split("\n");
            String path = lines[0].trim();
            String related = null, cur = null, maxF = null;
            for (int i = 1; i < lines.length; i++) {
                String v = lines[i].trim();
                if (v.isEmpty()) continue;
                if (related == null) related = v;
                else if (cur == null) cur = v;
                else if (maxF == null) maxF = v;
            }
            int khz = parseInt(cur, -1);
            if (khz > 0) {
                int mhz = khz / 1000;
                freqs.add(mhz);
                if (mhz > max) max = mhz;
            }
            int maxKhz = parseInt(maxF, -1);
            if (maxKhz / 1000 > max) max = maxKhz / 1000;
        }
        if (!freqs.isEmpty()) {
            d.cpuFreq = new int[freqs.size()];
            for (int i = 0; i < freqs.size(); i++) d.cpuFreq[i] = freqs.get(i);
            d.cpuMaxFreq = max;
        }
    }

    private void parseDumpsysBattery(String batt, SysData d) {
        for (String line : batt.split("\n")) {
            String t = line.trim();
            if (t.startsWith("level:")) d.battLevel = parseInt(t.substring(t.indexOf(':') + 1).trim(), -1);
            else if (t.startsWith("voltage:")) d.battVolt = parseInt(t.substring(t.indexOf(':') + 1).trim(), -1);
            else if (t.startsWith("temperature:")) {
                int temp = parseInt(t.substring(t.indexOf(':') + 1).trim(), -1);
                if (temp > 0) { d.battTemp = temp; d.battTempC = temp / 10f; }
            } else if (t.startsWith("Charge counter:")) {
                long cc = parseLong(t.substring(t.indexOf(':') + 1).trim());
                if (cc > 0) d.battChargeCounter = cc;
            } else if (t.startsWith("status:")) d.battStatus = parseInt(t.substring(t.indexOf(':') + 1).trim(), -1);
            else if (t.startsWith("health:")) d.battHealth = parseInt(t.substring(t.indexOf(':') + 1).trim(), -1);
            else if (t.startsWith("technology:")) d.battTech = t.substring(t.indexOf(':') + 1).trim();
            else if (t.startsWith("AC powered:")) { if (t.contains("true")) d.battPlugged = 1; }
            else if (t.startsWith("USB powered:")) { if (t.contains("true")) d.battPlugged = 2; }
            else if (t.startsWith("Wireless powered:")) { if (t.contains("true")) d.battPlugged = 4; }
            // 额外字段
            else if (t.startsWith("Max charging current:")) {
                int maxCur = parseInt(t.substring(t.indexOf(':') + 1).trim(), -1);
                if (maxCur > 0) d.battMaxChargeCurrent = maxCur;
            } else if (t.startsWith("Max charging voltage:")) {
                int maxVolt = parseInt(t.substring(t.indexOf(':') + 1).trim(), -1);
                if (maxVolt > 0) d.battMaxChargeVoltage = maxVolt;
            } else if (t.startsWith("Current:")) {
                int cur = parseInt(t.substring(t.indexOf(':') + 1).trim(), Integer.MIN_VALUE);
                if (cur != Integer.MIN_VALUE) {
                    d.battCurrent = cur;
                    d.battCurrentFromDumpsys = true;
                }
            }
        }
    }

    // ---------- 慢 Provider：thermal + gpu ----------
    private void parseSlow(String slow, SysData d) {
        String thermal = section(slow, "===THERMAL===", "===GPU===");
        String gpu = section(slow, "===GPU===", "===DEVFREQ===");
        String devfreq = section(slow, "===DEVFREQ===", null);
        if (thermal != null) parseThermal(thermal, d);
        if (devfreq != null) parseDevfreq(devfreq, d);
        if (gpu != null) parseDumpsysGpu(gpu, d);
        lastCpuTemp = d.cpuTemp;
        lastGpuUtil = d.gpuUtil;
        lastGpuFreq = d.gpuFreq;
    }

    private void parseThermal(String thermal, SysData d) {
        // dumpsys thermalservice 输出含 "Temperature" 段，形如 "CPU: 45.0" 或 "CPU 45.0"
        boolean inTemp = false;
        for (String line : thermal.split("\n")) {
            String t = line.trim();
            if (t.startsWith("Temperature")) { inTemp = true; continue; }
            if (inTemp && t.isEmpty()) { inTemp = false; continue; }
            if (!inTemp) continue;
            // 形如 "CPU: 45.0" / "CPU 45.0" / "CPU=45.0"
            String[] parts = t.split("[:=]");
            if (parts.length < 2) continue;
            String type = parts[0].trim().toUpperCase();
            float val = Float.NaN;
            try {
                String num = parts[1].trim().replaceAll("[^0-9.\\-]", "");
                val = Float.parseFloat(num);
            } catch (Exception ignored) {}
            if (Float.isNaN(val)) continue;
            if (type.contains("CPU") && Float.isNaN(d.cpuTemp)) d.cpuTemp = val;
            else if (type.contains("BATTERY") && Float.isNaN(d.battTempC)) d.battTempC = val;
        }
    }

    private void parseDevfreq(String devfreq, SysData d) {
        String[] blocks = devfreq.split("--- ");
        for (String block : blocks) {
            if (block.trim().isEmpty()) continue;
            String[] lines = block.split("\n");
            String path = lines[0].trim();
            String name = null, cur = null, load = null;
            for (int i = 1; i < lines.length; i++) {
                String v = lines[i].trim();
                if (v.isEmpty()) continue;
                if (name == null) name = v;
                else if (cur == null) cur = v;
                else if (load == null) load = v;
            }
            boolean isGpu = name != null && (name.toLowerCase().contains("gpu") || name.toLowerCase().contains("mali"));
            if (!isGpu) continue;
            int khz = parseInt(cur, -1);
            if (khz > 0) d.gpuFreq = khz / 1000;
            int l = parseInt(load, -1);
            if (l >= 0) {
                // load 量纲不确定（0-100 或 0-1000），>100 时按 1000 归一
                d.gpuUtil = l > 100 ? l / 10f : l;
                if (d.gpuUtil > 100) d.gpuUtil = 100;
            }
        }
    }

    private void parseDumpsysGpu(String gpu, SysData d) {
        // best-effort：很多 ROM 无此服务，忽略
        if (gpu.trim().isEmpty()) return;
        // 尝试 "GPU utilization = NN%" 之类
        for (String line : gpu.split("\n")) {
            String t = line.trim().toLowerCase();
            if (t.contains("utilization") && t.contains("%")) {
                try {
                    String num = t.replaceAll("[^0-9.]", "");
                    float v = Float.parseFloat(num);
                    if (v >= 0 && v <= 100) { d.gpuUtil = v; break; }
                } catch (Exception ignored) {}
            }
        }
    }

    // ---------- 功率计算 ----------
    private void computePower(SysData d) {
        // 使用 battStatus 判断方向，电流用于计算大小
        // 电池侧功率：电压(mV) × |电流(mA)| / 1000 -> mW
        if (d.battVolt > 0 && d.hasBattCurrent()) {
            float powerMag = d.battVolt * Math.abs(d.battCurrent) / 1000f;
            d.powerNow = powerMag;
            if (d.charging()) {
                d.powerIn = powerMag;
                d.powerOut = Float.NaN;
            } else {
                d.powerOut = powerMag;
                d.powerIn = Float.NaN;
            }
        } else {
        }

        // 如果还没有 powerIn，尝试从 dumpsys 获取的最大充电信息估算
        if (d.charging() && (Float.isNaN(d.powerIn) || d.powerIn < 1f)
                && d.battMaxChargeCurrent > 0 && d.battMaxChargeVoltage > 0) {
            d.powerIn = d.battMaxChargeCurrent * d.battMaxChargeVoltage / 1000f;
        }

        // 整机消耗估算
        if (d.charging()) {
            if (!Float.isNaN(d.powerIn) && !Float.isNaN(d.powerNow)) {
                d.powerPhone = Math.max(0, d.powerIn - d.powerNow);
            }
        } else if (!Float.isNaN(d.powerOut)) {
            d.powerPhone = d.powerOut;
        }
    }

    // ---------- 工具 ----------
    private Map<String, String> splitSections(String raw) {
        Map<String, String> map = new HashMap<>();
        String[] parts = raw.split("=== ");
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            int nl = part.indexOf('\n');
            if (nl < 0) continue;
            String path = part.substring(0, nl).trim();
            // 去除尾随的 "==="（如果存在）
            if (path.endsWith("===")) {
                path = path.substring(0, path.length() - 3).trim();
            }
            String content = part.substring(nl + 1);
            map.put(path, content);
        }
        return map;
    }

    private String section(String raw, String start, String end) {
        int s = raw.indexOf(start);
        if (s < 0) return null;
        s += start.length();
        int e = end == null ? raw.length() : raw.indexOf(end, s);
        if (e < 0) e = raw.length();
        return raw.substring(s, e);
    }

    private int parseInt(String s, int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private long parseLong(String s) {
        if (s == null) return 0;
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0; }
    }

    private int mapStatus(String s) {
        if (s == null) return -1;
        switch (s.trim().toLowerCase()) {
            case "charging": return android.os.BatteryManager.BATTERY_STATUS_CHARGING;
            case "discharging": return android.os.BatteryManager.BATTERY_STATUS_DISCHARGING;
            case "full": return android.os.BatteryManager.BATTERY_STATUS_FULL;
            case "not charging": return android.os.BatteryManager.BATTERY_STATUS_NOT_CHARGING;
            default: return -1;
        }
    }

    private int mapHealth(String s) {
        if (s == null) return -1;
        switch (s.trim().toLowerCase()) {
            case "good": return android.os.BatteryManager.BATTERY_HEALTH_GOOD;
            case "overheat": return android.os.BatteryManager.BATTERY_HEALTH_OVERHEAT;
            case "dead": return android.os.BatteryManager.BATTERY_HEALTH_DEAD;
            case "over voltage": return android.os.BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE;
            case "cold": return android.os.BatteryManager.BATTERY_HEALTH_COLD;
            default: return -1;
        }
    }

    /**
     * 电池电流校准：raw → 统一 mA 约定（正=充电，负=放电）。
     * power_supply 标准 current_now 单位是 µA，Android API CURRENT_NOW 单位也是 µA。
     */
    private int calibrateBattCurrent(int raw) {
        if (prefs == null) {
            return Math.abs(raw) > 10000 ? raw / 1000 : raw;
        }
        int pol = prefs.battPolarity();
        float scale = prefs.battCurrentScale();
        if (pol != 0 && !Float.isNaN(scale)) {
            float uA = raw * scale;
            float mA = uA / 1000f;
            return Math.round(mA * pol);
        }
        return Math.abs(raw) > 10000 ? raw / 1000 : raw;
    }
}
