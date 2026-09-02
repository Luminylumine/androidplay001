package com.akasha.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Chat session registry (SharedPreferences, small) + per-session chat history
 * (one JSON file each under files/sessions/, independent contexts per req 2.2).
 */
public class SessionStore {

    private static final Object CONTEXT_IO_LOCK = new Object();
    private static final Object CHAT_IO_LOCK = new Object();

    private final Context ctx;
    private final Prefs prefs;

    public SessionStore(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.prefs = new Prefs(ctx);
    }

    // ---------------- session list ----------------

    public List<ChatSession> list() {
        List<ChatSession> out = new ArrayList<>();
        boolean dirty = false;
        try {
            JSONArray arr = new JSONArray(prefs.raw().getString("sessions", ""));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                ChatSession s = new ChatSession();
                s.id = o.optString("id");
                s.agentId = o.optString("agentId");
                s.title = o.optString("title", s.agentId);
                s.displayName = o.optString("displayName", null);
                s.lastMsg = o.optString("lastMsg");
                s.lastMsgRole = o.optString("lastMsgRole");
                s.lastMsgTime = o.optLong("lastMsgTime");
                s.pinned = o.optBoolean("pinned");
                s.unread = o.optBoolean("unread");
                s.customPrompt = o.optString("customPrompt", "");
                if (s.customPrompt.isEmpty()) s.customPrompt = null;
                s.defaultGoal = o.optString("defaultGoal", "");
                if (s.defaultGoal.isEmpty()) s.defaultGoal = null;
                if (s.id != null && !s.id.isEmpty()) out.add(s);
            }
        } catch (Exception ignored) {}
        // FR-2.5 迁移: 旧 displayName 为空回退到 title; 全局去重(1)(2)并一次性回写
        java.util.Set<String> used = new java.util.HashSet<>();
        for (ChatSession s : out) {
            String fallback;
            if (s.displayName == null || s.displayName.isEmpty()) {
                fallback = (s.title == null || s.title.isEmpty() || s.title.equals(s.agentId))
                        ? agentDefaultName(s.agentId)
                        : s.title;
            } else {
                fallback = s.displayName;
            }
            s.displayName = dedupAgainst(used, fallback);
            used.add(s.displayName);
            s.title = s.displayName;
            dirty = true;
        }
        if (dirty) {
            try { persist(out); } catch (Exception ignored) {}
        }
        return out;
    }

    /** 对 used 集合按 base/base(1)/base(2) 的标准方式判重, 返回最终不冲突名 */
    static String dedupAgainst(java.util.Set<String> used, String base) {
        if (base == null) base = "";
        if (!used.contains(base)) return base;
        int i = 1;
        while (used.contains(base + "(" + i + ")")) i++;
        return base + "(" + i + ")";
    }

    /** 旧会话 displayName 回退时: 如果 title==agentId 则拿 Agent 展示名; 找不到再退回 agentId */
    private String agentDefaultName(String agentId) {
        if (agentId == null) return "";
        try {
            for (ModelInfo m : prefs.models()) {
                if (agentId.equals(m.id)) return m.name == null || m.name.isEmpty() ? agentId : m.name;
            }
        } catch (Exception ignored) {}
        return agentId;
    }

    /** Sorted for the chat tab: pinned first, then most recent activity. */
    public List<ChatSession> listSorted() {
        List<ChatSession> l = list();
        Collections.sort(l, new Comparator<ChatSession>() {
            @Override
            public int compare(ChatSession a, ChatSession b) {
                if (a.pinned != b.pinned) return a.pinned ? -1 : 1;
                return Long.compare(b.lastMsgTime, a.lastMsgTime);
            }
        });
        return l;
    }

    public ChatSession get(String id) {
        if (id == null) return null;
        for (ChatSession s : list()) if (s.id.equals(id)) return s;
        return null;
    }

    public void save(ChatSession s) {
        List<ChatSession> l = list();
        boolean found = false;
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).id.equals(s.id)) {
                l.set(i, s);
                found = true;
                break;
            }
        }
        if (!found) l.add(s);
        persist(l);
    }

    public void remove(String id) {
        List<ChatSession> l = list();
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).id.equals(id)) {
                l.remove(i);
                break;
            }
        }
        persist(l);
        chatFile(id).delete();
        contextFile(id).delete();
    }

    /** 生成不与现有会话重复的对话显示名: base / base(1) / base(2) ... */
    public String uniqueDisplayName(String base) {
        if (base == null) base = "";
        java.util.Set<String> used = new java.util.HashSet<>();
        for (ChatSession s : list()) if (s.displayName != null) used.add(s.displayName);
        return dedupAgainst(used, base);
    }

    /** Legacy helper. Internally delegates to uniqueDisplayName for consistency. */
    @Deprecated
    public String uniqueTitle(String base) {
        return uniqueDisplayName(base);
    }

    /** Create (or reuse) the most recent session of an agent; auto-assigns unique display name. */
    public ChatSession touchAgent(String agentId, String agentName) {
        List<ChatSession> l = list();
        for (ChatSession s : l) if (s.agentId != null && s.agentId.equals(agentId)) return s;
        String name = uniqueDisplayName((agentName == null || agentName.isEmpty()) ? agentId : agentName);
        ChatSession s = ChatSession.create(agentId, name);
        l.add(s);
        persist(l);
        return s;
    }

    private void persist(List<ChatSession> l) {
        JSONArray arr = new JSONArray();
        for (ChatSession s : l) {
            try {
                JSONObject o = new JSONObject();
                o.put("id", s.id);
                o.put("agentId", s.agentId);
                o.put("title", s.title == null ? "" : s.title);
                o.put("displayName", s.displayName == null ? "" : s.displayName);
                o.put("lastMsg", s.lastMsg);
                o.put("lastMsgRole", s.lastMsgRole);
                o.put("lastMsgTime", s.lastMsgTime);
                o.put("pinned", s.pinned);
                o.put("unread", s.unread);
                if (s.customPrompt != null) o.put("customPrompt", s.customPrompt);
                if (s.defaultGoal != null) o.put("defaultGoal", s.defaultGoal);
                arr.put(o);
            } catch (Exception ignored) {}
        }
        prefs.raw().edit().putString("sessions", arr.toString()).apply();
    }

    // ---------------- per-session chat history ----------------

    private File chatFile(String sessionId) {
        return new File(new File(ctx.getFilesDir(), "sessions"), sanitize(sessionId) + ".json");
    }

    private static String sanitize(String s) {
        return s == null ? "x" : s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    public static class Line {
        public String type; // system | user | agent
        public String text;
        public long time;
        public String meta; // optional JSON detail (log detail screen)
    }

    public List<Line> loadChat(String sessionId) {
        List<Line> out = new ArrayList<>();
        File f = chatFile(sessionId);
        if (!f.isFile()) return out;
        try {
            InputStream is = new FileInputStream(f);
            byte[] buf = new byte[8192];
            StringBuilder sb = new StringBuilder();
            int n;
            while ((n = is.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
            is.close();
            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Line ln = new Line();
                ln.type = o.optString("t");
                ln.text = o.optString("x");
                ln.time = o.optLong("ts");
                ln.meta = o.optString("m", "");
                if (ln.meta.isEmpty()) ln.meta = null;
                out.add(ln);
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** Append one line (keeps the newest 300). Called on the main thread. */
    public void appendChat(String sessionId, String type, String text, long time) {
        appendChat(sessionId, type, text, time, null);
    }

    public void appendChat(String sessionId, String type, String text, long time, String meta) {
        if (sessionId == null) return;
        synchronized (CHAT_IO_LOCK) {
            List<Line> l = loadChat(sessionId);
            l.add(new Line());
            l.get(l.size() - 1).type = type;
            l.get(l.size() - 1).text = text;
            l.get(l.size() - 1).time = time;
            l.get(l.size() - 1).meta = meta;
            while (l.size() > 300) l.remove(0);
            writeChat(sessionId, l);

            // refresh the session preview line
            try {
                ChatSession s = get(sessionId);
                if (s != null) {
                    s.lastMsg = text == null ? "" : text.replace('\n', ' ');
                    if (s.lastMsg.length() > 60) s.lastMsg = s.lastMsg.substring(0, 60) + "…";
                    s.lastMsgRole = type;
                    s.lastMsgTime = time;
                    save(s);
                }
            } catch (Exception ignored) {
            }
        }
    }

    private void writeChat(String sessionId, List<Line> l) {
        try {
            JSONArray arr = new JSONArray();
            for (Line ln : l) {
                JSONObject o = new JSONObject()
                        .put("t", ln.type == null ? "system" : ln.type)
                        .put("x", ln.text == null ? "" : ln.text)
                        .put("ts", ln.time);
                if (ln.meta != null) o.put("m", ln.meta);
                arr.put(o);
            }
            File f = chatFile(sessionId);
            f.getParentFile().mkdirs();
            OutputStream os = new FileOutputStream(f);
            os.write(arr.toString().getBytes("UTF-8"));
            os.close();
        } catch (Exception ignored) {}
    }

    // ---------------- pending "tell the model" notes (req 5) ----------------
    // When the user taps 打断并告知模型 while the agent is stopped, the note is
    // queued per-session and injected as guide at the next task start.

    private File noteFile(String sessionId) {
        return new File(new File(ctx.getFilesDir(), "sessions"),
                sanitize(sessionId) + ".note.txt");
    }

    private File contextFile(String sessionId) {
        return new File(new File(ctx.getFilesDir(), "sessions"),
                sanitize(sessionId) + ".context.json");
    }

    /** Durable LLM turns, separate from the user-facing chat transcript. */
    public static class ContextLine {
        public String assistant;
        public String user;
        public long time;
    }

    public List<ContextLine> loadContext(String sessionId) {
        List<ContextLine> out = new ArrayList<>();
        synchronized (CONTEXT_IO_LOCK) {
            File f = contextFile(sessionId);
            if (!f.isFile()) return out;
            try {
                InputStream is = new FileInputStream(f);
                byte[] buf = new byte[8192];
                StringBuilder sb = new StringBuilder();
                int n;
                while ((n = is.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
                is.close();
                JSONArray arr = new JSONArray(sb.toString());
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    ContextLine line = new ContextLine();
                    line.assistant = o.optString("a", "");
                    line.user = o.optString("u", "");
                    line.time = o.optLong("ts", 0);
                    out.add(line);
                }
            } catch (Exception ignored) {}
        }
        return out;
    }

    public void appendContext(String sessionId, String assistant, String user, long time) {
        if (sessionId == null) return;
        synchronized (CONTEXT_IO_LOCK) {
            List<ContextLine> lines = loadContext(sessionId);
            ContextLine line = new ContextLine();
            line.assistant = assistant == null ? "" : assistant;
            line.user = user == null ? "" : user;
            line.time = time;
            lines.add(line);
            while (lines.size() > 100) lines.remove(0);
            try {
                JSONArray arr = new JSONArray();
                for (ContextLine x : lines) {
                    arr.put(new JSONObject().put("a", x.assistant).put("u", x.user).put("ts", x.time));
                }
                File f = contextFile(sessionId);
                f.getParentFile().mkdirs();
                File tmp = new File(f.getPath() + ".tmp");
                OutputStream os = new FileOutputStream(tmp);
                os.write(arr.toString().getBytes("UTF-8"));
                os.close();
                if (!tmp.renameTo(f)) {
                    writeContextDirect(f, arr);
                    tmp.delete();
                }
            } catch (Exception ignored) {}
        }
    }

    private void writeContextDirect(File f, JSONArray arr) throws Exception {
        OutputStream os = new FileOutputStream(f);
        os.write(arr.toString().getBytes("UTF-8"));
        os.close();
    }

    public void appendNote(String sessionId, String text) {
        try {
            File f = noteFile(sessionId);
            f.getParentFile().mkdirs();
            OutputStream os = new FileOutputStream(f, true);
            os.write((text + "\n").getBytes("UTF-8"));
            os.close();
        } catch (Exception ignored) {}
    }

    public List<String> consumeNotes(String sessionId) {
        List<String> out = new ArrayList<>();
        File f = noteFile(sessionId);
        if (!f.isFile()) return out;
        try {
            InputStream is = new FileInputStream(f);
            byte[] buf = new byte[8192];
            StringBuilder sb = new StringBuilder();
            int n;
            while ((n = is.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
            is.close();
            for (String line : sb.toString().split("\n")) {
                if (!line.trim().isEmpty()) out.add(line.trim());
            }
        } catch (Exception ignored) {}
        f.delete();
        return out;
    }
}
