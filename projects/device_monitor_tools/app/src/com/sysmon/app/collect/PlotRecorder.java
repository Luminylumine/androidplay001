package com.sysmon.app.collect;

import android.content.Context;

import com.sysmon.app.Prefs;
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
    private final Map<String, PlotStore> stores = new HashMap<>();
    private final long[] lastSampleTs;
    private volatile boolean running = false;
    private Thread thread;
    private long lastSaveMs = 0;

    private PlotRecorder(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.prefs = new Prefs(ctx);
        this.sampler = new Sampler(ctx);
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
        return stores.get(key);
    }

    public synchronized void start() {
        if (running) return;
        for (PlotStore s : stores.values()) s.load();
        running = true;
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
            try {
                long now = System.currentTimeMillis();
                // 是否有子选项到期
                boolean anyDue = false;
                for (int i = 0; i < PlotItems.ALL.length; i++) {
                    PlotItems.Item it = PlotItems.ALL[i];
                    if (!prefs.plotBool(it.key, Prefs.P_ENABLED)) continue;
                    int freq = prefs.plotInt(it.key, Prefs.P_FREQ_MS);
                    if (freq < 100) freq = 100;
                    if (now - lastSampleTs[i] >= freq) {
                        anyDue = true;
                        break;
                    }
                }
                if (anyDue) {
                    SysData d = sampler.sample();
                    for (int i = 0; i < PlotItems.ALL.length; i++) {
                        PlotItems.Item it = PlotItems.ALL[i];
                        if (!prefs.plotBool(it.key, Prefs.P_ENABLED)) continue;
                        int freq = prefs.plotInt(it.key, Prefs.P_FREQ_MS);
                        if (freq < 100) freq = 100;
                        if (now - lastSampleTs[i] < freq) continue;
                        float v = valueOf(it, d);
                        if (!Float.isNaN(v)) {
                            PlotStore st = stores.get(it.key);
                            st.append(now, v);
                            st.trim(now, prefs.plotInt(it.key, Prefs.P_RETENTION_SEC),
                                    prefs.plotInt(it.key, Prefs.P_MAX_POINTS));
                            lastSampleTs[i] = now;
                        }
                        // 数据非法：不写入、不推进 lastSampleTs（下次仍会尝试）
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
                Thread.sleep(200);
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
            default:           return Float.NaN;
        }
    }
}
