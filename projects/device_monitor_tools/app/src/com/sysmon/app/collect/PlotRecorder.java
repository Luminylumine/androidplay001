package com.sysmon.app.collect;

import android.content.Context;

import com.sysmon.app.Prefs;
import com.sysmon.app.Privilege;
import com.sysmon.app.SysLog;

import java.util.HashMap;
import java.util.Map;

/**
 * 绘图数据采集器：独立线程，按各子选项配置的采样频率采样。
 * - 只写合法数据：进程异常/数据非法（NaN/-1）时不写入 → 图上表现为断线而非 0
 * - 每 5 秒批量落盘一次
 * - 生命周期 = 应用进程（与 MonitorEngine 一样常驻，供后台长期监视）
 */
public final class PlotRecorder {

    private static PlotRecorder inst;

    private final Context ctx;
    private final Prefs prefs;
    private final Sampler sampler;
    private final ShellSampler shellSampler;
    private final GpuSampler gpuSampler;
    private final ScreenFps screenFps;
    private final Map<String, PlotStore> stores = new HashMap<>();
    private final long[] lastSampleTs;
    private volatile boolean running = false;
    private Thread thread;
    private long lastSaveMs = 0;

    private PlotRecorder(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.prefs = new Prefs(ctx);
        this.sampler = new Sampler(ctx);
        this.shellSampler = new ShellSampler();
        this.shellSampler.setContext(ctx);
        this.gpuSampler = new GpuSampler();
        this.screenFps = new ScreenFps();
        this.screenFps.setContext(ctx);
        this.lastSampleTs = new long[PlotItems.ALL.length];
        for (PlotItems.Item it : PlotItems.ALL) {
            stores.put(it.key, new PlotStore(ctx, it.key));
        }
    }

    public static synchronized PlotRecorder get(Context ctx) {
        if (inst == null) inst = new PlotRecorder(ctx);
        return inst;
    }

    public PlotStore store(String key) {
        PlotStore store = stores.get(key);
        if (store != null) store.load();
        return store;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        lastSaveMs = System.currentTimeMillis();
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "sysmon-plot");
        thread.setDaemon(true);
        thread.start();
        SysLog.w("PlotRecorder started");
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        if (thread != null) thread.interrupt();
        saveAll();
        SysLog.w("PlotRecorder stopped");
    }

    public void saveAll() {
        for (PlotStore s : stores.values()) s.save();
    }

    private void loop() {
        while (running) {
            long sleepMs = 5000;
            try {
                long now = System.currentTimeMillis();
                // 哪些子选项到期；按数据组划分，避免高频项触发全量采样
                boolean anyDue = false, fullDue = false, cpuDue = false;
                boolean gpuDue = false, fpsDue = false;
                boolean[] due = new boolean[PlotItems.ALL.length];
                long nextDueAt = Long.MAX_VALUE;
                for (int i = 0; i < PlotItems.ALL.length; i++) {
                    PlotItems.Item it = PlotItems.ALL[i];
                    if (!prefs.plotBool(it.key, Prefs.P_ENABLED)) continue;
                    long nextAt = lastSampleTs[i] + Math.max(100, prefs.plotInt(it.key, Prefs.P_FREQ_MS));
                    if (nextAt < nextDueAt) nextDueAt = nextAt;
                    int freq = prefs.plotInt(it.key, Prefs.P_FREQ_MS);
                    if (freq < 100) freq = 100;
                    if (now - lastSampleTs[i] >= freq) {
                        due[i] = true;
                        lastSampleTs[i] = now; // 尝试即推进：数据恒非法时避免每轮 busy 采样
                        anyDue = true;
                        if (it.key.equals("cpu_use")) { fullDue = true; cpuDue = true; }
                        else if (it.key.equals("gpu_use")) gpuDue = true;
                        else if (it.key.equals("screen_fps")) fpsDue = true;
                        else fullDue = true; // 功率/电量/网络：app 采样器（CPU 走 shell）
                    }
                }
                if (nextDueAt != Long.MAX_VALUE) {
                    sleepMs = Math.max(50, Math.min(1000, nextDueAt - now));
                }
                if (anyDue) {
                    SysData d = null;
                    if (fullDue) {
                        // CPU 需要 shell 通道（/proc/stat）；其余走 app 采样器
                        if (cpuDue) {
                            Privilege.ensure();
                            d = Privilege.available() ? shellSampler.sample() : sampler.sample();
                        } else {
                            d = sampler.sample();
                        }
                    }
                    if (d == null) d = new SysData();
                    if (gpuDue) gpuSampler.sample(d);
                    if (fpsDue) d.screenFps = screenFps.sample();
                    for (int i = 0; i < PlotItems.ALL.length; i++) {
                        if (!due[i]) continue;
                        PlotItems.Item it = PlotItems.ALL[i];
                        float v = valueOf(it, d);
                        if (!Float.isNaN(v)) {
                            PlotStore st = stores.get(it.key);
                            st.append(now, v);
                            st.trim(now, prefs.plotInt(it.key, Prefs.P_RETENTION_SEC),
                                    prefs.plotInt(it.key, Prefs.P_MAX_POINTS));
                        }
                        // 数据非法：不写入（图上断线），按采样周期在 now+freq 再尝试
                    }
                }
                // 定期落盘
                if (now - lastSaveMs > 5000 && anyDue) {
                    saveAll();
                    lastSaveMs = now;
                }
            } catch (Throwable t) {
                SysLog.w("plot loop: " + t);
            }
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    /** 取子选项对应数值；非法返回 NaN（不写入）。 */
    private float valueOf(PlotItems.Item it, SysData d) {
        switch (it.key) {
            case "out_power":  return d.powerOut;   // 未充电时为 NaN
            case "in_power":   return d.powerIn;    // 未充电时为 NaN
            case "batt_level": return d.battLevel >= 0 ? d.battLevel : Float.NaN;
            case "cpu_use":    return d.cpuTotal;   // 无 shell 通道时 NaN → 断线
            case "screen_fps": return d.screenFps;
            case "gpu_use":    return d.gpuUtil;
            case "net_rate":   return d.netRxRate;  // 下载速率 KB/s
            default:           return Float.NaN;
        }
    }
}
