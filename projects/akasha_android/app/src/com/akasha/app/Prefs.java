package com.akasha.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class Prefs {
    private final SharedPreferences sp;

    public static final String DEF_BASE_URL = "https://api.llm.ustc.edu.cn/v1";
    public static final String DEF_MODEL = "qwen3.8-chat";

    public Prefs(Context ctx) {
        sp = ctx.getSharedPreferences("akasha", Context.MODE_PRIVATE);
    }

    /** Raw editor access for SessionStore / BackgroundHelper (session & bg specs). */
    public SharedPreferences raw() {
        return sp;
    }

    public String baseUrl() { return sp.getString("baseUrl", DEF_BASE_URL); }
    public void baseUrl(String v) { sp.edit().putString("baseUrl", v).apply(); }

    public String apiKey() { return sp.getString("apiKey", ""); }
    public void apiKey(String v) { sp.edit().putString("apiKey", v).apply(); }

    public String model() { return sp.getString("model", DEF_MODEL); }
    public void model(String v) { sp.edit().putString("model", v).apply(); }

    public String goal() { return sp.getString("goal", ""); }
    public void goal(String v) { sp.edit().putString("goal", v).apply(); }

    public int intervalMs() { return sp.getInt("intervalMs", 3000); }
    public void intervalMs(int v) { sp.edit().putInt("intervalMs", v).apply(); }

    public int maxTokens() { return sp.getInt("maxTokens", 4096); }
    public void maxTokens(int v) { sp.edit().putInt("maxTokens", v).apply(); }

    public int historyRounds() { return sp.getInt("historyRounds", 6); }
    public void historyRounds(int v) { sp.edit().putInt("historyRounds", v).apply(); }

    public boolean autoStart() { return sp.getBoolean("autoStart", false); }
    public void autoStart(boolean v) { sp.edit().putBoolean("autoStart", v).apply(); }

    /** 经验池保留时间（天，0=不按时间清理）。 */
    public int expRetainDays() { return sp.getInt("expRetainDays", 0); }
    public void expRetainDays(int v) { sp.edit().putInt("expRetainDays", v).apply(); }

    /** 经验池保留大小（条，0=不按数量清理）。 */
    public int expRetainMax() { return sp.getInt("expRetainMax", 500); }
    public void expRetainMax(int v) { sp.edit().putInt("expRetainMax", v).apply(); }

    public List<ModelInfo> models() {
        List<ModelInfo> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(sp.getString("models", ""));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                ModelInfo m = new ModelInfo();
                m.id = o.optString("id");
                m.name = o.optString("name", m.id);
                m.vision = o.optBoolean("vision", false);
                m.ctxIn = o.optInt("ctxIn", 128000);
                m.maxOut = o.optInt("maxOut", 65536);
                m.permShell = o.optBoolean("permShell", true);
                m.permA11y = o.optBoolean("permA11y", true);
                m.permFile = o.optBoolean("permFile", true);
                m.permMedia = o.optBoolean("permMedia", true);
                m.permPhoto = o.optBoolean("permPhoto", true);
                m.permMusic = o.optBoolean("permMusic", true);
                m.permExpRead = o.optBoolean("permExpRead", true);
                m.permExpWrite = o.optBoolean("permExpWrite", true);
                m.pinned = o.optBoolean("pinned", false);
                String bu = o.optString("baseUrl", "");
                m.baseUrl = bu.isEmpty() ? null : bu;
                String ak = o.optString("apiKey", "");
                m.apiKey = ak.isEmpty() ? null : ak;
                m.autoStart = o.optBoolean("autoStart", false);
                m.customPrompt = o.optString("customPrompt", "");
                if (m.customPrompt.isEmpty()) m.customPrompt = null;
                if (m.id != null && !m.id.isEmpty()) list.add(m);
            }
        } catch (Exception ignored) {}
        if (list.isEmpty()) {
            list.add(new ModelInfo("qwen3.8-chat", "qwen3.8-chat", true, 128000, 65536));
            list.add(new ModelInfo("qwen3.8-reasoner", "qwen3.8-reasoner", true, 128000, 65536));
            list.add(new ModelInfo("qwen-chat", "qwen-chat", true, 128000, 65536));
            list.add(new ModelInfo("deepseek-v4-flash-ascend", "deepseek-v4-flash-ascend", false, 128000, 65536));
            list.add(new ModelInfo("deepseek-v4-flash-ascend1", "deepseek-v4-flash-ascend1", false, 128000, 65536));
            saveModels(list);
        }
        boolean hasTest = false;
        for (ModelInfo m : list) {
            if ("__test__".equals(m.id)) { hasTest = true; break; }
        }
        if (!hasTest) {
            list.add(new ModelInfo("__test__", "测试会话", false, 128000, 65536));
            saveModels(list);
        }
        return list;
    }

    /** 生成不与现有 Agent 展示名重复的名称: base / base(1) / base(2) ... (excludeId 可传当前正编辑的条目 id 避免与自身冲突) */
    public String uniqueAgentName(String base, String excludeId) {
        if (base == null) base = "";
        java.util.Set<String> used = new java.util.HashSet<>();
        for (ModelInfo m : models()) {
            if (excludeId != null && excludeId.equals(m.id)) continue;
            if (m.name != null) used.add(m.name);
        }
        if (!used.contains(base)) return base;
        int i = 1;
        while (used.contains(base + "(" + i + ")")) i++;
        return base + "(" + i + ")";
    }

    public void saveModels(List<ModelInfo> list) {
        JSONArray arr = new JSONArray();
        for (ModelInfo m : list) {
            try {
                JSONObject o = new JSONObject();
                o.put("id", m.id);
                o.put("name", m.name);
                o.put("vision", m.vision);
                o.put("ctxIn", m.ctxIn);
                o.put("maxOut", m.maxOut);
                o.put("permShell", m.permShell);
                o.put("permA11y", m.permA11y);
                o.put("permFile", m.permFile);
                o.put("permMedia", m.permMedia);
                o.put("permPhoto", m.permPhoto);
                o.put("permMusic", m.permMusic);
                o.put("permExpRead", m.permExpRead);
                o.put("permExpWrite", m.permExpWrite);
                o.put("pinned", m.pinned);
                if (m.baseUrl != null) o.put("baseUrl", m.baseUrl);
                if (m.apiKey != null) o.put("apiKey", m.apiKey);
                o.put("autoStart", m.autoStart);
                if (m.customPrompt != null) o.put("customPrompt", m.customPrompt);
                arr.put(o);
            } catch (Exception ignored) {}
        }
        sp.edit().putString("models", arr.toString()).apply();
    }

    /**
     * Effective model for an agent (next phase: model settings vs global
     * settings relationship). Returns the agent's own profile, or the global
     * default model when the agent id is unknown/absent.
     */
    public ModelInfo effectiveModel(String agentId) {
        if (agentId != null) {
            for (ModelInfo m : models()) {
                if (m.id.equals(agentId)) return m;
            }
        }
        String def = model();
        for (ModelInfo m : models()) {
            if (m.id.equals(def)) return m;
        }
        return models().isEmpty() ? null : models().get(0);
    }

    /** Agent's effective Base URL: own override first, else global default. */
    public String agentBaseUrl(String agentId) {
        ModelInfo m = effectiveModel(agentId);
        if (m != null && m.baseUrl != null && !m.baseUrl.isEmpty()) return m.baseUrl;
        return baseUrl();
    }

    /** Agent's effective API Key: own override first, else global default. */
    public String agentApiKey(String agentId) {
        ModelInfo m = effectiveModel(agentId);
        if (m != null && m.apiKey != null && !m.apiKey.isEmpty()) return m.apiKey;
        return apiKey();
    }

    /** 定时器与唤醒配置 (per-agent JSON; 空 = 全默认)。 */
    public TimerConfig timer(String agentId) {
        if (agentId == null) return TimerConfig.def();
        return TimerConfig.fromJson(sp.getString("timer_" + agentId, ""));
    }

    public void saveTimer(String agentId, TimerConfig c) {
        if (agentId == null) return;
        sp.edit().putString("timer_" + agentId, c == null ? "" : c.toJson()).apply();
    }

    public void clearTimer(String agentId) {
        if (agentId == null) return;
        sp.edit().remove("timer_" + agentId).apply();
    }

    /** 会话级定时器配置 (per-session JSON). 与 agent 级完全独立。 */
    public TimerConfig sessionTimer(String sessionId) {
        if (sessionId == null) return TimerConfig.def();
        return TimerConfig.fromJson(sp.getString("timer_session_" + sessionId, ""));
    }

    public void saveSessionTimer(String sessionId, TimerConfig c) {
        if (sessionId == null) return;
        sp.edit().putString("timer_session_" + sessionId, c == null ? "" : c.toJson()).apply();
    }

    public void clearSessionTimer(String sessionId) {
        if (sessionId == null) return;
        sp.edit().remove("timer_session_" + sessionId).apply();
    }
}
