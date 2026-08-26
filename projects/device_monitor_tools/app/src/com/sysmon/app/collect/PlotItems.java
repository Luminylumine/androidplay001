package com.sysmon.app.collect;

/** 绘图子选项定义：key 用于 Prefs/存储，title/unit 用于 UI 与图名。 */
public final class PlotItems {

    public static final class Item {
        public final String key;
        public final String title;
        public final String unit;
        /** 该项默认采样频率 ms；0 = 用"表格默认格式"的全局默认。 */
        public final int defFreqMs;

        Item(String key, String title, String unit) {
            this(key, title, unit, 0);
        }

        Item(String key, String title, String unit, int defFreqMs) {
            this.key = key;
            this.title = title;
            this.unit = unit;
            this.defFreqMs = defFreqMs;
        }
    }

    public static final Item OUT_POWER   = new Item("out_power",   "输出功率", "mW");
    public static final Item IN_POWER    = new Item("in_power",    "输入功率", "mW");
    public static final Item BATT_LEVEL  = new Item("batt_level",  "电池电量", "%");
    public static final Item CPU_USE     = new Item("cpu_use",     "CPU占用率", "%");
    public static final Item SCREEN_FPS  = new Item("screen_fps",  "屏幕帧率", "Hz", 100);
    public static final Item GPU_USE     = new Item("gpu_use",     "GPU占用率", "%");
    public static final Item NET_RATE    = new Item("net_rate",    "网络速率", "KB/s");

    public static final Item[] ALL = { OUT_POWER, IN_POWER, BATT_LEVEL,
            CPU_USE, SCREEN_FPS, GPU_USE, NET_RATE };

    public static Item byKey(String key) {
        for (Item it : ALL) {
            if (it.key.equals(key)) return it;
        }
        return null;
    }

    private PlotItems() {}
}
