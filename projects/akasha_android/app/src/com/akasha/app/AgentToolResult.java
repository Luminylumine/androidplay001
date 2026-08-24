package com.akasha.app;

/**
 * Standard result of a tool action call (per ChatGPT spec v2).
 *
 * Architecture (ref 你的要求):
 *   Tool methods DON'T decide the message (no inline if/else per scenario).
 *   They only decide the stable error_code and optional detail.
 *   Display text is looked up centrally via {@link AgentErrorMessages}.
 *
 * Layout:
 *   success=true  → data is the raw observation text (e.g. file_ls listing)
 *   success=false → error_code + error_detail, llmHint/userHint are lazy-computed
 */
public final class AgentToolResult {

    public final boolean success;
    public final String data;         // observation text when success=true
    public final String errorCode;    // stable code when success=false
    public final String errorDetail;  // optional context: path/pkg/query/raw exception

    private AgentToolResult(boolean ok, String data, String code, String detail) {
        this.success = ok;
        this.data = data;
        this.errorCode = code;
        this.errorDetail = detail;
    }

    public static AgentToolResult ok(String observation) {
        return new AgentToolResult(true, observation, null, null);
    }

    public static AgentToolResult err(String code, String detail) {
        return new AgentToolResult(false, null, code, detail == null ? "" : detail);
    }

    /** Next-step hint (passed back into LLM observation). */
    public String llmHint() {
        if (success) return data;
        return AgentErrorMessages.getForLLM(errorCode, errorDetail);
    }

    /** Friendly text (shown as ⚠ in chat; no-match codes suppressed). */
    public String userHint() {
        if (success) return null;
        if (AgentErrorCodes.isNoMatch(errorCode)) return null;
        return AgentErrorMessages.getForUser(errorCode, errorDetail);
    }

    /** Included in long-press log detail view. */
    public String rawError() {
        if (success) return null;
        return errorCode + (errorDetail.isEmpty() ? "" : " | " + errorDetail);
    }
}
