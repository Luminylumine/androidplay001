package com.androidplay.mdclient.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Machine-readable evidence for the replayed page/transcript/agent/document loop. */
public final class FunctionalLoopTrace {
    public static final class Entry {
        public final String type;
        public final long timeMs;
        public final String detail;
        private Entry(String type, long timeMs, String detail) { this.type = type; this.timeMs = timeMs; this.detail = detail == null ? "" : detail; }
        public String toJson() { return "{\"type\":\"" + escape(type) + "\",\"timeMs\":" + timeMs + ",\"detail\":\"" + escape(detail) + "\"}"; }
    }

    private final List<Entry> entries = new ArrayList<>();
    public FunctionalLoopTrace add(String type, long timeMs, String detail) { entries.add(new Entry(type, timeMs, detail)); return this; }
    public List<Entry> entries() { return Collections.unmodifiableList(entries); }
    public String toJsonl() { StringBuilder out = new StringBuilder(); for (Entry entry : entries) out.append(entry.toJson()).append('\n'); return out.toString(); }
    private static String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n"); }
}
