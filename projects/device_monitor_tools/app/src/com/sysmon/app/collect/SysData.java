package com.sysmon.app.collect;

/** 一次采样的全部数据。字段不可用时为 NaN / -1。 */
public class SysData {

    public long ts = 0;

    // CPU
    public float cpuTotal = Float.NaN;          // 0-100
    public float[] cpuPer = new float[0];       // 每核 0-100
    public int[] cpuFreq = new int[0];          // MHz, -1 不可用
    public int cpuMaxFreq = -1;                 // MHz

    // GPU
    public float gpuUtil = Float.NaN;           // 0-100
    public int gpuFreq = -1;                    // MHz

    // 内存
    public long memTotal = -1;                  // KB
    public long memAvail = -1;                  // KB
    public long memFree = -1;                   // KB
    public long swapTotal = -1;                 // KB
    public long swapFree = -1;                  // KB

    // 负载 / 运行时间
    public float load1 = Float.NaN, load5 = Float.NaN, load15 = Float.NaN;
    public long uptimeSec = -1;

    // 电池
    public int battLevel = -1;                  // %
    public int battTemp = -1;                   // 0.1°C
    public int battVolt = -1;                   // mV
    public int battCurrent = Integer.MIN_VALUE; // mA（正=充电，负=放电）
    public int battCurrentAvg = Integer.MIN_VALUE;
    public long battChargeCounter = -1;         // µAh
    public int battStatus = -1;                 // BatteryManager.BATTERY_STATUS_*
    public int battHealth = -1;
    public int battPlugged = -1;                // BatteryManager.BATTERY_PLUGGED_*
    public String battTech = "";
    public int battCapacity = -1;               // 设计容量 mAh（charge_full）
    public int battMaxChargeCurrent = -1;       // 最大充电电流 mA
    public int battMaxChargeVoltage = -1;       // 最大充电电压 mV
    public boolean battCurrentFromDumpsys = false; // 电流是否来自 dumpsys

    // 功率（mW）
    public float powerNow = Float.NaN;          // 电池侧 电压×电流
    public float powerIn = Float.NaN;           // 输入（充电器侧）
    public float powerOut = Float.NaN;          // 输出（放电）
    public float powerPhone = Float.NaN;        // 整机消耗估算

    // 网络
    public long netRx = -1, netTx = -1;         // 累计字节
    public float netRxRate = Float.NaN, netTxRate = Float.NaN; // KB/s
    public String netIface = "";

    // 温度（°C）
    public float cpuTemp = Float.NaN;
    public float battTempC = Float.NaN;

    // 屏幕帧率（Hz）
    public float screenFps = Float.NaN;

    // 电池积分（mAh）
    public float battChargedMah = Float.NaN;    // 累计充入
    public float battDischargedMah = Float.NaN; // 累计放出

    // 通道
    public String source = "app";               // app | shell | adb

    public boolean hasCpu() { return !Float.isNaN(cpuTotal); }
    public boolean hasGpu() { return !Float.isNaN(gpuUtil); }
    public boolean hasBattCurrent() { return battCurrent != Integer.MIN_VALUE; }
    public boolean hasMem() { return memTotal > 0; }

    public float memUsedPct() {
        if (memTotal <= 0) return Float.NaN;
        long used = memTotal - memAvail;
        if (used < 0) used = 0;
        return used * 100f / memTotal;
    }

    public long memUsedKB() {
        if (memTotal <= 0) return -1;
        long used = memTotal - memAvail;
        return used < 0 ? 0 : used;
    }

    public boolean charging() {
        return battStatus == android.os.BatteryManager.BATTERY_STATUS_CHARGING
                || battStatus == android.os.BatteryManager.BATTERY_STATUS_FULL;
    }
}
