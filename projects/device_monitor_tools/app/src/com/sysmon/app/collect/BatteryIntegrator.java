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

    private final Prefs prefs;
    private long lastTs = 0;
    private float lastCurrent = Float.NaN;

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
    public float chargedMah() {
        return prefs.battIntegratedMah();
    }

    /** 累计放出 mAh（正数）。 */
    public float dischargedMah() {
        return prefs.battIntegratedDischargedMah();
    }

    /** 每次采样调用：用当前电流更新积分。按 intervalMs() 间隔实际积分。 */
    public void update(SysData d) {
        if (!enabled()) return;
        if (!d.hasBattCurrent()) return;

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
                    prefs.setBattIntegratedMah(prefs.battIntegratedMah() + mah);
                } else {
                    prefs.setBattIntegratedDischargedMah(prefs.battIntegratedDischargedMah() - mah);
                }
                lastTs = now;
                lastCurrent = cur;
            }
        } else {
            // 首次记录
            lastTs = now;
            lastCurrent = cur;
        }
    }

    /** 清零累计值。 */
    public void reset() {
        prefs.setBattIntegratedMah(0f);
        prefs.setBattIntegratedDischargedMah(0f);
        lastTs = 0;
        lastCurrent = Float.NaN;
        SysLog.w("BatteryIntegrator reset");
    }
}
