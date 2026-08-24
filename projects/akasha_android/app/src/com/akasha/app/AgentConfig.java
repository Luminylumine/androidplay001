package com.akasha.app;

import android.content.Context;

/**
 * 解析 Agent 的"最终运行配置"。
 * 三层回退（agent override → global API → 内置默认），让 AgentService/LlmClient 不再
 * 直接散落 Prefs.xxx() 调用。
 *
 * 字段：
 *  - baseUrl : LLM 服务基地址（不含 /v1 等模型路径）
 *  - apiKey  : 鉴权密钥
 *  - modelId : LLM 模型名（一般等于 agent.id）
 *  - ctxIn / maxOut : 上下文窗口 & 单次输出上限
 *  - vision  : 是否支持多模态
 *  - prompt  : 系统提示词（空串 = 默认 AgentPrompts.defaultBase()）
 */
public final class AgentConfig {
    public final String modelId;
    public final String baseUrl;
    public final String apiKey;
    public final int    ctxIn;
    public final int    maxOut;
    public final boolean vision;
    public final String prompt;

    private AgentConfig(Builder b) {
        this.modelId = b.modelId;
        this.baseUrl = b.baseUrl;
        this.apiKey  = b.apiKey;
        this.ctxIn   = b.ctxIn;
        this.maxOut  = b.maxOut;
        this.vision  = b.vision;
        this.prompt  = b.prompt;
    }

    /** 是否具备发起 LLM 请求的最小配置。 */
    public boolean isRunnable() {
        if (modelId == null || modelId.isEmpty()) return false;
        if (baseUrl == null || baseUrl.isEmpty()) return false;
        // apiKey 允许为空：部分本地/内网模型不鉴权
        if (ctxIn <= 0 || maxOut <= 0) return false;
        return true;
    }

    /**
     * 三层回退解析：
     *   L1. 若 agent 有 agent.baseUrl/agent.apiKey → 直接用
     *   L2. 否则读取 Prefs 里全局 baseUrl/key (即原 Prefs.baseUrl() / Prefs.apiKey())
     *   L3. 都没有则走内置默认 (DEEPSEEK_URL / "")
     */
    public static AgentConfig resolve(Context ctx, ModelInfo agent) {
        Prefs p = new Prefs(ctx);
        Builder b = new Builder();
        b.modelId = agent.id;
        b.vision  = agent.vision;
        b.ctxIn   = agent.ctxIn  > 0 ? agent.ctxIn  : 128000;
        b.maxOut  = agent.maxOut > 0 ? agent.maxOut : 8192;
        b.prompt  = (agent.customPrompt != null && !agent.customPrompt.isEmpty())
                ? agent.customPrompt : "";
        // L1 agent override
        String url = agent.baseUrl;
        String key = agent.apiKey;
        // L2 fallback to global
        if (url == null || url.isEmpty()) url = p.baseUrl();
        if (key == null || key.isEmpty()) key = p.apiKey();
        // L3 built-in defaults (deepseek)
        if (url == null || url.isEmpty()) url = "https://api.deepseek.com";
        if (key == null) key = "";
        b.baseUrl = url;
        b.apiKey  = key;
        return b.build();
    }

    /** 便捷：直接按 agentId 解析，找不到就抛 IllegalArgumentException。 */
    public static AgentConfig resolve(Context ctx, String agentId) {
        ModelInfo m = findAgent(ctx, agentId);
        if (m == null) throw new IllegalArgumentException("Agent not found: " + agentId);
        return resolve(ctx, m);
    }

    /** 便捷：当前"主模型"配置。若未设置则取第一个可用的（否则抛）。 */
    public static AgentConfig resolveCurrent(Context ctx) {
        Prefs p = new Prefs(ctx);
        String id = p.model();
        if (id == null || id.isEmpty()) {
            for (ModelInfo m : p.models()) {
                id = m.id;
                break;
            }
        }
        if (id == null || id.isEmpty()) {
            throw new IllegalStateException("No Agent configured");
        }
        return resolve(ctx, id);
    }

    private static ModelInfo findAgent(Context ctx, String agentId) {
        for (ModelInfo m : new Prefs(ctx).models()) {
            if (m.id.equals(agentId)) return m;
        }
        return null;
    }

    static final class Builder {
        String modelId;
        String baseUrl;
        String apiKey;
        int    ctxIn;
        int    maxOut;
        boolean vision;
        String prompt;
        AgentConfig build() { return new AgentConfig(this); }
    }
}
