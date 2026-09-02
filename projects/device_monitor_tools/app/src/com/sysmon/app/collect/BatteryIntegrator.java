package com.sysmon.app.collect;

import android.os.SystemClock;

import com.sysmon.app.Prefs;
import com.sysmon.app.SysLog;

/**
 * 电池电流计积分（库仑计）。
 * 按可调帧率采样 battCurrent(mA)，对时间积分得到累计 mAh。
 * 结果持久化到 Prefs，跨重启保留。
 * 充电为正、放电为负，分别累计充入/放出。
 */
public class BatteryIntegrator {

    private static final long PERSIST_INTERVAL_MS = 5 * 60 * 1000L;

    private final Prefs prefs;
    private long lastTs = 0;
    private float lastCurrent = Float.NaN;
    private long lastPersistTs = 0;
    private boolean loaded = false;
    private boolean dirty = false;
    private float charged = 0f;
    private float discharged = 0f;

    public BatteryIntegrator(Prefs prefs) {
        this.prefs = prefs;
    }

    /** 是否启用。 */
    public boolean enabled() {
        return prefs.battIntegrateEnabled();
    }

    /** 积分采样间隔 ms。 */
    public int intervalMs() {
        return prefs.battIntegrateMs();
    }

    /** 累计充入 mAh。 */
    public synchronized float chargedMah() {
        load();
        return charged;
    }

    /** 累计放出 mAh（正数）。 */
    public synchronized float dischargedMah() {
        load();
        return discharged;
    }

    /** 每次采样调用：用当前电流更新积分。按 intervalMs() 间隔实际积分。 */
    public synchronized void update(SysData d) {
        if (!enabled()) return;
        if (!d.hasBattCurrent()) return;
        load();

        long now = SystemClock.elapsedRealtime();
        float cur = d.battCurrent; // mA
        int interval = intervalMs();

        // 仅在达到间隔时才进行积分
        if (lastTs > 0 && !Float.isNaN(lastCurrent)) {
            long dt = now - lastTs;
            if (dt >= interval && dt > 0) {
                // 平均电流 × 时间(h) = mAh
                float avgMa = (lastCurrent + cur) / 2f;
                float mah = avgMa * dt / 3600000f;
                if (mah > 0) {
                    charged += mah;
                } else {
                    discharged -= mah;
                }
                dirty = true;
                lastTs = now;
                lastCurrent = cur;
                if (lastPersistTs == 0 || now - lastPersistTs >= PERSIST_INTERVAL_MS) persist(now);
            }
        } else {
            // 首次记录
            lastTs = now;
            lastCurrent = cur;
        }
    }

    /** 清零累计值。 */
    public synchronized void reset() {
        loaded = true;
        charged = 0f;
        discharged = 0f;
        dirty = true;
        persist(SystemClock.elapsedRealtime());
        lastTs = 0;
        lastCurrent = Float.NaN;
        SysLog.w("BatteryIntegrator reset");
    }

    public synchronized void flush() {
        if (loaded) persist(SystemClock.elapsedRealtime());
    }

    private void load() {
        if (loaded) return;
        charged = prefs.battIntegratedMah();
        discharged = prefs.battIntegratedDischargedMah();
        loaded = true;
    }

    private void persist(long now) {
        if (!dirty) return;
        prefs.setBattIntegratedMah(charged);
        prefs.setBattIntegratedDischargedMah(discharged);
        lastPersistTs = now;
        dirty = false;
    }
}
