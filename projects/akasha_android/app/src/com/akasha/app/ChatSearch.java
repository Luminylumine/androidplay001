package com.akasha.app;

import android.content.Context;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Agent 内部零成本技能: 搜索本对话或同 Agent 所有对话历史。
 * 对应 FR-4 —— 动作: {"action":"chat_search", ...}
 */
public class ChatSearch {

    /** Upper bound on returned lines (avoids bloating next-round prompt). */
    public static final int MAX_LINES = 40;

    /** Parameter holder for a chat_search call. Null fields mean "don't filter". */
    public static class Q {
        public String query;
        public String scope;            // "this_session" (default) or "same_agent_all"
        public Long fromTs;
        public Long toTs;
        public String role;             // "user" | "agent" | null (any)
        public List<String> senderAgentIds; // non-empty -> restrict type=agent lines whose origin agent id in list
    }

    /** Result holder (renderable multi-line hint + diagnostic counters). */
    public static class R {
        public String hint;
        public int hit;
        public boolean truncated;
    }

    /**
     * Execute the search. All parameters come from the action payload; the
     * caller is responsible for injecting the returned hint into the next LLM
     * observation. Performs case-insensitive substring match over the message
     * text (NOT the action JSON itself).
     */
    public static R query(Context ctx, Q q, String currentAgentId, String currentSessionId) {
        R r = new R();
        r.hint = "";
        if (ctx == null || q == null || q.query == null) return r;
        final SessionStore store = new SessionStore(ctx);
        final Prefs prefs = new Prefs(ctx);
        final String needle = q.query.toLowerCase(Locale.US);

        // 1) resolve candidate session ids
        final List<ChatSession> targets = new ArrayList<>();
        final long bytesCap = 2L * 1024 * 1024; // NFR-4

        if ("same_agent_all".equalsIgnoreCase(q.scope)) {
            long sum = 0;
            List<ChatSession> all = store.listSorted();
            // newest-first iteration, stop after 2MB
            for (ChatSession s : all) {
                if (currentAgentId != null && !currentAgentId.equals(s.agentId)) continue;
                File f = sessionFile(ctx, s.id);
                long sz = f.isFile() ? f.length() : 0;
                if (sum + sz > bytesCap) continue;
                sum += sz;
                targets.add(s);
            }
        } else {
            // this_session
            ChatSession s = store.get(currentSessionId);
            if (s != null) targets.add(s);
        }

        // 2) helper
        final long now = System.currentTimeMillis();
        final long fromTs = q.fromTs == null ? 0L : q.fromTs;
        final long toTs   = q.toTs   == null ? now : q.toTs;
        final boolean wantUser  = q.role == null || !q.role.equalsIgnoreCase("agent");
        final boolean wantAgent = q.role == null || !q.role.equalsIgnoreCase("user");
        final Set<String> senderSet = new HashSet<>();
        if (q.senderAgentIds != null) senderSet.addAll(q.senderAgentIds);
        final boolean restrictSender = !senderSet.isEmpty();

        // cache model-name lookups for the sender header
        final java.util.Map<String, String> agentName = new java.util.HashMap<>();
        for (ModelInfo m : prefs.models()) agentName.put(m.id, m.name == null ? m.id : m.name);

        // 3) scan
        final List<Hit> hits = new ArrayList<>();
        for (ChatSession s : targets) {
            final String displayName = s.displayName == null
                    ? (s.title == null ? "" : s.title) : s.displayName;
            for (SessionStore.Line ln : store.loadChat(s.id)) {
                if (ln == null || ln.text == null) continue;
                if (ln.time != 0 && (ln.time < fromTs || ln.time > toTs)) continue;
                final boolean isUser  = "user".equalsIgnoreCase(ln.type);
                final boolean isAgent = "agent".equalsIgnoreCase(ln.type);
                if (isUser  && !wantUser)  continue;
                if (isAgent && !wantAgent) continue;
                if (restrictSender && isAgent) {
                    // agent lines always bear the current agent's id (action origin)
                    String origin = currentAgentId == null ? "" : currentAgentId;
                    if (!senderSet.contains(origin)) continue;
                }
                if (needle.isEmpty() || ln.text.toLowerCase(Locale.US).contains(needle)) {
                    Hit h = new Hit();
                    h.time = ln.time == 0 ? now : ln.time;
                    h.sessionDisplayName = displayName;
                    h.sender = senderHeader(isUser, isAgent, currentAgentId, agentName);
                    h.text = ln.text;
                    hits.add(h);
                }
            }
        }
        // 4) sort by time ascending
        Collections.sort(hits, new Comparator<Hit>() {
            @Override public int compare(Hit a, Hit b) { return Long.compare(a.time, b.time); }
        });

        // 5) render (cap)
        r.hit = hits.size();
        r.truncated = hits.size() > MAX_LINES;
        final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        StringBuilder sb = new StringBuilder();
        if (r.truncated) sb.append("hit=").append(r.hit).append(" truncated to ").append(MAX_LINES).append(", refine query\n");
        int limit = Math.min(MAX_LINES, hits.size());
        for (int i = 0; i < limit; i++) {
            Hit h = hits.get(i);
            sb.append('[').append(sdf.format(new Date(h.time))).append("] ");
            sb.append("[对话: ").append(h.sessionDisplayName).append("] ");
            sb.append('[').append(h.sender).append("] ");
            if (h.text.length() > 500) sb.append(h.text, 0, 500).append("…");
            else sb.append(h.text);
            sb.append('\n');
        }
        r.hint = sb.toString().trim();
        CpLog.i("chat_search", "q=" + q.query + " scope=" + q.scope + " hit=" + r.hit
                + " truncated=" + r.truncated);
        return r;
    }

    private static String senderHeader(boolean isUser, boolean isAgent, String agentId,
                                       java.util.Map<String, String> agentName) {
        if (isUser) return "user";
        if (isAgent) {
            String name = agentId == null ? "agent" : agentName.containsKey(agentId)
                    ? agentName.get(agentId) : agentId;
            return (name == null || name.isEmpty() ? "agent" : name)
                    + (agentId == null ? "" : "(" + agentId + ")");
        }
        return "system";
    }

    private static File sessionFile(Context ctx, String sessionId) {
        String safe = sessionId == null ? "x" : sessionId.replaceAll("[^A-Za-z0-9._-]", "_");
        return new File(new File(ctx.getFilesDir(), "sessions"), safe + ".json");
    }

    private static class Hit {
        long time;
        String sessionDisplayName;
        String sender;
        String text;
    }
}
