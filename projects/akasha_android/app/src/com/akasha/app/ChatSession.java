package com.akasha.app;

/**
 * One chat conversation. A session belongs to one agent (model); the same
 * agent may have many sessions. Per FR-2 this class uses two independent
 * "name" concepts and keeps them strictly separated:
 *   - displayName : the end-user visible chat title (shown in chat tab,
 *                   ChatActivity top-bar, timer trigger-source UI). Unique
 *                   globally across all sessions.
 *   - title       : legacy (pre-refactor) field kept for backward-compat
 *                   only. No longer rendered in UI. Code outside
 *                   SessionStore must NOT read this field.
 */
public class ChatSession {
    public String id;
    public String agentId;        // ModelInfo.id
    /** Legacy field: do NOT use outside SessionStore. Use displayName instead. */
    @Deprecated
    public String title;
    /** End-user visible chat title (single source of truth for UI). */
    public String displayName;
    public String lastMsg = "";   // most recent system/agent/user line (preview)
    public String lastMsgRole = ""; // "system" | "agent" | "user"
    public long lastMsgTime = 0;  // epoch millis
    public boolean pinned = false;
    public boolean unread = false;
    /** 会话级提示词 (null/空 = 回退该 Agent 的模型提示词, 再回退系统提示词; 见 AgentPrompts.resolveBase)。 */
    public String customPrompt = null;

    public ChatSession() {}

    /**
     * Create a new session. The caller (SessionStore / caller) is responsible
     * for making displayName unique across all sessions via
     * {@link SessionStore#uniqueDisplayName(String)}. This helper only
     * falls back to agentId if both names are empty.
     */
    public static ChatSession create(String agentId, String displayName) {
        ChatSession s = new ChatSession();
        s.id = "s" + System.currentTimeMillis() + (int) (Math.random() * 900 + 100);
        s.agentId = agentId;
        s.displayName = (displayName == null || displayName.isEmpty()) ? agentId : displayName;
        // keep legacy mirror for old session-json readers / diff tools
        s.title = s.displayName;
        s.lastMsgTime = System.currentTimeMillis();
        return s;
    }
}
