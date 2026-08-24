package com.akasha.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个 Agent/会话的「定时器与唤醒」配置（避免 24h 工作，按需唤醒）。
 *
 * 可独立存储在两个级别:
 *  - Agent 级:   Prefs.timer(agentId)    → 整个模型的全局配置
 *  - 会话级:     Prefs.sessionTimer(sessionId) → 单个会话的独立配置
 *
 * 三类唤醒源:
 *  1) 闹钟式定时唤醒: 24h 绝对时间 + 重复(周一~周日 7 选, 全选=每天; 或每月 N 号, 超过当月天数取最后一天)
 *  2) 事件唤醒:
 *     - 任务完成: 勾选的聊天会话中出现 任务结束(done)/任务终止(terminate) 时触发
 *     - 打开软件: 系统内用户 App 进入前台时触发(可多选包名)
 *  3) 倒计时定时器: H/M/S 倒计时, 到 0 触发(功能预留扩展, 仅 App 存活期间有效)
 *
 * 每个唤醒源可绑定一个 TriggerResult (触发后对 Agent 做什么):
 *  - 发送指定信息(排队 / 打断并发送 / 引导)
 *  - 发送 "继续"
 */
public class TimerConfig {

    // TriggerResult.type
    public static final int TRIG_SEND_MESSAGE = 0;
    public static final int TRIG_SEND_CONTINUE = 1;
    // TriggerResult.sendMode
    public static final int MODE_QUEUE = 0;
    public static final int MODE_INTERRUPT = 1;
    public static final int MODE_GUIDE = 2;

    public static class TriggerResult {
        public int type = TRIG_SEND_CONTINUE;
        public int sendMode = MODE_QUEUE;
        public String message = "";

        public static TriggerResult def() { return new TriggerResult(); }

        public String describe() {
            if (type == TRIG_SEND_CONTINUE) return "发送「继续」";
            String mode = sendMode == MODE_QUEUE ? "排队"
                    : (sendMode == MODE_INTERRUPT ? "打断并发送" : "引导");
            return "发送信息[" + mode + "]: " + (message.isEmpty() ? "(空)" : message);
        }
    }

    // ---- 1) 闹钟式定时唤醒 ----
    public boolean alarmEnabled = false;
    public int alarmHour = -1;   // -1 = 未设置
    public int alarmMinute = 0;  // 0~59
    /** index 0=周一 … 6=周日; 全选 7 个 = 每天 */
    public boolean[] alarmDays = new boolean[7];
    /** 每月 N 号(字符串, 允许 >31, 触发时按当月最大天数钳制); 空 = 不按日 */
    public List<String> alarmMonthDays = new ArrayList<>();
    public TriggerResult alarmResult = TriggerResult.def();

    // ---- 2) 事件唤醒 ----
    public boolean taskDoneEnabled = false;
    /** 触发源: 聊天会话 id 列表 */
    public List<String> taskDoneSessions = new ArrayList<>();
    public TriggerResult taskDoneResult = TriggerResult.def();

    public boolean appOpenEnabled = false;
    /** 触发源: 用户 App 包名列表 */
    public List<String> appOpenPackages = new ArrayList<>();
    public TriggerResult appOpenResult = TriggerResult.def();

    // ---- 3) 倒计时 (预留扩展) ----
    public boolean countdownEnabled = false;
    public int cdHour = 0;
    public int cdMinute = 0;
    public int cdSecond = 0;
    public TriggerResult countdownResult = TriggerResult.def();

    public static TimerConfig def() { return new TimerConfig(); }

    public boolean allDays() {
        for (boolean b : alarmDays) if (!b) return false;
        return true;
    }

    public boolean anyDay() {
        for (boolean b : alarmDays) if (b) return true;
        return false;
    }

    public boolean alarmConfigured() {
        return alarmEnabled && alarmHour >= 0
                && (anyDay() || !alarmMonthDays.isEmpty());
    }

    // ---------------- JSON ----------------

    public String toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("alarmEnabled", alarmEnabled);
            o.put("alarmHour", alarmHour);
            o.put("alarmMinute", alarmMinute);
            JSONArray d = new JSONArray();
            for (boolean b : alarmDays) d.put(b ? 1 : 0);
            o.put("alarmDays", d);
            JSONArray md = new JSONArray();
            for (String s : alarmMonthDays) if (s != null && !s.isEmpty()) md.put(s);
            o.put("alarmMonthDays", md);
            o.put("alarmResult", trigJson(alarmResult));
            o.put("taskDoneEnabled", taskDoneEnabled);
            o.put("taskDoneSessions", new JSONArray(taskDoneSessions));
            o.put("taskDoneResult", trigJson(taskDoneResult));
            o.put("appOpenEnabled", appOpenEnabled);
            o.put("appOpenPackages", new JSONArray(appOpenPackages));
            o.put("appOpenResult", trigJson(appOpenResult));
            o.put("countdownEnabled", countdownEnabled);
            o.put("cdHour", cdHour);
            o.put("cdMinute", cdMinute);
            o.put("cdSecond", cdSecond);
            o.put("countdownResult", trigJson(countdownResult));
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    private static JSONObject trigJson(TriggerResult t) throws Exception {
        JSONObject o = new JSONObject();
        o.put("type", t.type);
        o.put("sendMode", t.sendMode);
        o.put("message", t.message == null ? "" : t.message);
        return o;
    }

    public static TimerConfig fromJson(String s) {
        TimerConfig c = def();
        if (s == null || s.isEmpty()) return c;
        try {
            JSONObject o = new JSONObject(s);
            c.alarmEnabled = o.optBoolean("alarmEnabled", false);
            c.alarmHour = o.optInt("alarmHour", -1);
            c.alarmMinute = Math.max(0, Math.min(59, o.optInt("alarmMinute", 0)));
            JSONArray d = o.optJSONArray("alarmDays");
            if (d != null) {
                for (int i = 0; i < 7 && i < d.length(); i++) c.alarmDays[i] = d.optInt(i, 0) == 1;
            }
            JSONArray md = o.optJSONArray("alarmMonthDays");
            if (md != null) {
                c.alarmMonthDays.clear();
                for (int i = 0; i < md.length(); i++) c.alarmMonthDays.add(md.optString(i));
            }
            c.alarmResult = trigFrom(o.optJSONObject("alarmResult"));
            c.taskDoneEnabled = o.optBoolean("taskDoneEnabled", false);
            c.taskDoneSessions = strList(o.optJSONArray("taskDoneSessions"));
            c.taskDoneResult = trigFrom(o.optJSONObject("taskDoneResult"));
            c.appOpenEnabled = o.optBoolean("appOpenEnabled", false);
            c.appOpenPackages = strList(o.optJSONArray("appOpenPackages"));
            c.appOpenResult = trigFrom(o.optJSONObject("appOpenResult"));
            c.countdownEnabled = o.optBoolean("countdownEnabled", false);
            c.cdHour = Math.max(0, o.optInt("cdHour", 0));
            c.cdMinute = Math.max(0, Math.min(59, o.optInt("cdMinute", 0)));
            c.cdSecond = Math.max(0, Math.min(59, o.optInt("cdSecond", 0)));
            c.countdownResult = trigFrom(o.optJSONObject("countdownResult"));
        } catch (Exception ignored) {}
        return c;
    }

    private static List<String> strList(JSONArray arr) {
        List<String> l = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i);
                if (s != null && !s.isEmpty()) l.add(s);
            }
        }
        return l;
    }

    private static TriggerResult trigFrom(JSONObject o) {
        TriggerResult t = TriggerResult.def();
        if (o != null) {
            t.type = o.optInt("type", TRIG_SEND_CONTINUE);
            t.sendMode = o.optInt("sendMode", MODE_QUEUE);
            t.message = o.optString("message", "");
        }
        return t;
    }
}
