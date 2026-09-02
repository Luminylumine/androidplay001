package com.akasha.app;

/**
 * One agent = one model profile, plus per-agent capability grants
 * (req 6) and an optional custom system prompt (req 7).
 *
 * Permission flags gate which tools the AgentService exposes to the LLM.
 * They are app-level grants (user's intent for this agent), independent of
 * the OS-level permissions (Shizuku binding, a11y toggle, storage).
 */
public class ModelInfo {
    public String id;
    public String name;
    public boolean vision;
    public int ctxIn = 128000;
    public int maxOut = 65536;

    // --- per-agent capability grants (all default on) ---
    public boolean permShell = true;    // Shizuku shell (shell tool + shell-based fallbacks)
    public boolean permA11y = true;     // accessibility: tap/swipe/type/key/open_app/a11y_text
    public boolean permFile = true;     // generic /sdcard file tools
    public boolean permMedia = true;    // Movies/Video media files
    public boolean permPhoto = true;    // Pictures/DCIM/相册
    public boolean permMusic = true;    // Music 音乐
    public boolean permExpRead = true;  // 全局经验池: exp_search
    public boolean permExpWrite = true; // 全局经验池: exp_record

    /** 通讯录置顶标记 */
    public boolean pinned = false;

    // --- per-agent API override (empty means fall back to global Prefs) ---
    public String baseUrl = null;   // null/empty = use global default
    public String apiKey = null;    // null/empty = use global default

    /** 本 Agent 开机自启使能（仅在全局总门控开启时才生效） */
    public boolean autoStart = false;

    /** Default task goal for sessions of this Agent type; empty falls back to global. */
    public String defaultGoal = null;

    // --- custom prompt (null/empty = built-in default) ---
    public String customPrompt = null;

    public ModelInfo() {}

    public ModelInfo(String id, String name, boolean vision, int ctxIn, int maxOut) {
        this.id = id;
        this.name = name;
        this.vision = vision;
        this.ctxIn = ctxIn;
        this.maxOut = maxOut;
    }

    public String meta() {
        String v = vision ? "多模态" : "文本";
        return v + "  " + (ctxIn / 1000) + "k in / " + (maxOut / 1000) + "k out";
    }
}
