package com.sysmon.app.collect;

/** 绘图子选项定义：key 用于 Prefs/存储，title/unit 用于 UI 与图名。 */
public final class PlotItems {

    public static final class Item {
        public final String key;
        public final String title;
        public final String unit;

        Item(String key, String title, String unit) {
            this.key = key;
            this.title = title;
            this.unit = unit;
        }
    }

    public static final Item OUT_POWER  = new Item("out_power",  "输出功率", "mW");
    public static final Item IN_POWER   = new Item("in_power",   "输入功率", "mW");
    public static final Item BATT_LEVEL = new Item("batt_level", "电池电量", "%");

    public static final Item[] ALL = { OUT_POWER, IN_POWER, BATT_LEVEL };

    public static Item byKey(String key) {
        for (Item it : ALL) {
            if (it.key.equals(key)) return it;
        }
        return null;
    }

    private PlotItems() {}
}
