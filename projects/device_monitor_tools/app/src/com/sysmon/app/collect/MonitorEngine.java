package com.sysmon.app.collect;

import android.content.Context;

import com.sysmon.app.Prefs;
import com.sysmon.app.Privilege;
import com.sysmon.app.SysLog;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 后台采样引擎：单线程按需采样，持有最新不可变快照。
 * - 全量模式（主界面可见）：按 refreshMs 采样，通道可用时用 ShellSampler（更全）
 * - 轻量模式（仅悬浮窗）：按 overlayUpdateMs 采样，只用无权限 Sampler + 屏幕帧率，
 *   非悬浮窗监控量（每核 CPU/GPU/网络等）后台不读取；电池积分始终运行
 * Activity 与悬浮窗只读 latest()。
 */
public class MonitorEngine {

    public interface Listener {
        void onSample(SysData d);
    }

    private static MonitorEngine inst;

    private final Context ctx;
    private final Prefs prefs;
    private final Sampler appSampler;
    private final ShellSampler shellSampler;
    private final ScreenFps screenFps;
    private final GpuSampler gpuSampler;
    private final BatteryIntegrator batteryIntegrator;
    private final AtomicReference<SysData> latest = new AtomicReference<>(new SysData());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean running = false;
    private volatile boolean lightMode = true;
    private Thread thread;

    private MonitorEngine(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.prefs = new Prefs(ctx);
        this.appSampler = new Sampler(ctx);
        this.shellSampler = new ShellSampler();
        this.shellSampler.setContext(ctx);
        this.screenFps = new ScreenFps();
        this.screenFps.setContext(ctx);
        this.gpuSampler = new GpuSampler();
        this.batteryIntegrator = new BatteryIntegrator(prefs);
    }

    public static synchronized MonitorEngine get(Context ctx) {
        if (inst == null) inst = new MonitorEngine(ctx);
        return inst;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "sysmon-sampler");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        batteryIntegrator.flush();
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    /** 设置轻量模式（仅悬浮窗运行时）。 */
    public void setLightMode(boolean light) {
        this.lightMode = light;
        SysLog.w("MonitorEngine lightMode=" + light);
    }

    public boolean isLightMode() {
        return lightMode;
    }

    public BatteryIntegrator batteryIntegrator() {
        return batteryIntegrator;
    }

    public SysData latest() {
        return latest.get();
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void loop() {
        SysLog.w("MonitorEngine started (sampler thread)");
        int tick = 0;
        while (running) {
            try {
                SysData d;
                if (lightMode) {
                    // 轻量：无权限采样 + 屏幕帧率 + GPU + 电池积分。
                    // 部分 ROM 会拒绝普通应用读取 /proc/stat；特权通道可用时只补充 CPU。
                    d = appSampler.sample();
                    Privilege.ensure();
                    if (Privilege.available()) {
                        SysData cpu = shellSampler.sampleCpu();
                        d.cpuTotal = cpu.cpuTotal;
                        d.cpuPer = cpu.cpuPer;
                    }
                    int overlayMask = prefs.overlayShowMask();
                    if ((overlayMask & Prefs.SHOW_FPS) != 0 && prefs.screenFpsEnabled()) {
                        d.screenFps = screenFps.sample();
                    }
                    if ((overlayMask & Prefs.SHOW_GPU) != 0) {
                        gpuSampler.sample(d);
                    }
                } else {
                    Privilege.ensure();
                    if (Privilege.available()) {
                        d = shellSampler.sample();
                    } else {
                        d = appSampler.sample();
                    }
                    d.screenFps = screenFps.sample();
                    gpuSampler.sample(d);
                }
                // 电池积分始终运行（长期监视）
                batteryIntegrator.update(d);
                d.battChargedMah = batteryIntegrator.chargedMah();
                d.battDischargedMah = batteryIntegrator.dischargedMah();
                latest.set(d);
                tick++;
                if (tick % 10 == 0) {
                    SysLog.w("tick#" + tick + " src=" + d.source + " lightMode=" + lightMode
                            + " cpu=" + (d.hasCpu() ? String.format("%.0f%%", d.cpuTotal) : "N/A")
                            + " gpu=" + (d.hasGpu() ? String.format("%.0f%%", d.gpuUtil) : "N/A")
                            + " gpuFreq=" + (d.gpuFreq > 0 ? (d.gpuFreq + "MHz") : "N/A")
                            + " mem=" + (d.hasMem() ? (d.memTotal / 1024 + "MB") : "N/A")
                            + " batt=" + (d.battLevel >= 0 ? (d.battLevel + "%") : "N/A")
                            + " fps=" + (Float.isNaN(d.screenFps) ? "N/A" : String.format("%.0f", d.screenFps))
                            + " battMah=" + (Float.isNaN(d.battChargedMah) ? "N/A" : String.format("%.1f", d.battChargedMah)));
                }
                for (Listener l : listeners) {
                    try {
                        l.onSample(d);
                    } catch (Throwable t) {
                        SysLog.w("listener: " + t);
                    }
                }
            } catch (Throwable t) {
                SysLog.w("sample loop: " + t);
            }
            try {
                Thread.sleep(lightMode ? prefs.overlayUpdateMs() : prefs.refreshMs());
            } catch (InterruptedException e) {
                break;
            }
        }
        SysLog.w("MonitorEngine stopped");
    }
}
