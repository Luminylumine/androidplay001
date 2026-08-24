package com.akasha.app;

import org.json.JSONObject;

/**
 * Parses the LLM reply into a single Action. The model is instructed to reply
 * with exactly one line of JSON; we are lenient and extract the first {...} block.
 */
public class ActionParser {

    public static class Action {
        public String type = "error";
        public double x, y, x1, y1, x2, y2;
        public long ms = 300;
        public String text, key, pkg, message, question;
        // chat_search / window-ish params
        public String scope, role;
        public Long fromTs, toTs;
        public java.util.List<String> senderAgentIds;
        // shell-ish tools
        public String path, content, pattern, url, query, cmd, title, id;
        public int idx = -1;
        public int shots = -1; // exp_record: 0..9 recent screenshots (-1 = auto)
        public boolean ok = false;
        public String raw = "";
        public String parseErr = ""; // description for LLM_JSON_INVALID
    }

    public static Action parse(String content) {
        Action a = new Action();
        a.raw = content == null ? "" : content;
        if (content == null) { a.parseErr = "null output"; return a; }
        int s = content.indexOf('{');
        int e = content.lastIndexOf('}');
        if (s < 0 || e <= s) { a.parseErr = "no {..} block found"; return a; }
        try {
            JSONObject o = new JSONObject(content.substring(s, e + 1));
            a.type = normAction(o.optString("action", "error"));
            a.x = o.optDouble("x", 0);
            a.y = o.optDouble("y", 0);
            a.x1 = o.optDouble("x1", a.x);
            a.y1 = o.optDouble("y1", a.y);
            a.x2 = o.optDouble("x2", a.x);
            a.y2 = o.optDouble("y2", a.y);
            a.ms = o.optLong("ms", 300);
            a.text = o.optString("text", null);
            a.key = o.optString("key", null);
            a.pkg = o.optString("package", null);
            a.message = o.optString("message", null);
            a.question = o.optString("question", null);
            a.path = o.optString("path", null);
            a.content = o.optString("content", null);
            a.pattern = o.optString("pattern", null);
            a.url = o.optString("url", null);
            a.query = o.optString("query", null);
            a.cmd = o.optString("cmd", null);
            a.title = o.optString("title", null);
            a.id = o.optString("id", null);
            a.shots = o.optInt("shots", -1);
            a.idx = o.optInt("idx", -1);
            a.scope = o.has("scope") ? o.optString("scope", null) : null;
            a.role = o.has("role") ? o.optString("role", null) : null;
            a.fromTs = o.has("from_ts")   ? (Long.valueOf(o.optLong("from_ts", 0L)))   : null;
            a.toTs   = o.has("to_ts")     ? (Long.valueOf(o.optLong("to_ts", 0L)))     : null;
            if (o.has("sender_agent_ids") && !o.isNull("sender_agent_ids")) {
                org.json.JSONArray arr = o.optJSONArray("sender_agent_ids");
                if (arr != null) {
                    a.senderAgentIds = new java.util.ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) a.senderAgentIds.add(arr.optString(i));
                }
            }
            a.ok = true;
        } catch (Exception ex) {
            a.parseErr = ex.getClass().getSimpleName() + ": " + ex.getMessage();
        }
        return a;
    }

    /** LLM 常把收尾动作写成同义词(terminal/end/complete...)，归一化到 done/terminate。 */
    private static String normAction(String t) {
        if (t == null) return "error";
        String s = t.trim().toLowerCase();
        if (s.equals("done") || s.equals("complete") || s.equals("completed")
                || s.equals("finished") || s.equals("success")) return "done";
        if (s.equals("terminate") || s.equals("terminal") || s.equals("end")
                || s.equals("stop") || s.equals("abort") || s.equals("cancel")
                || s.equals("failed") || s.equals("failure")) return "terminate";
        return s;
    }
}
