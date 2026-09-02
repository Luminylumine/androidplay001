package com.androidplay.mdclient.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Builds bounded context in priority order. Token budget is a conservative 4 chars/token estimate. */
public final class ContextAssembler {
    private final int maxChars;
    private final int maxTokens;
    private final OutlineProvider outlineProvider;
    private final SearchProvider searchProvider;
    private final ReadAroundProvider readAroundProvider;

    public ContextAssembler(int maxChars, int maxTokens, OutlineProvider outlineProvider,
                            SearchProvider searchProvider, ReadAroundProvider readAroundProvider) {
        if (maxChars < 0 || maxTokens < 0) throw new IllegalArgumentException("negative budget");
        this.maxChars = maxChars; this.maxTokens = maxTokens;
        this.outlineProvider = outlineProvider; this.searchProvider = searchProvider; this.readAroundProvider = readAroundProvider;
    }

    public ContextAssembler(int maxChars, int maxTokens) { this(maxChars, maxTokens, null, null, null); }

    public Result assemble(String system, String outline, String search, String readAround,
                           String transcript, String document) {
        EnumMap<ContextLayer, String> requested = new EnumMap<ContextLayer, String>(ContextLayer.class);
        requested.put(ContextLayer.L0, system); requested.put(ContextLayer.L1, outline);
        requested.put(ContextLayer.L2, search); requested.put(ContextLayer.L3, readAround);
        requested.put(ContextLayer.L4, transcript); requested.put(ContextLayer.L5, document);
        return fit(requested);
    }

    public Result assemble(String system, String query, String blockId, String transcript, String document) {
        String outline = outlineProvider == null ? "" : outlineProvider.outline();
        String search = searchProvider == null || query == null ? "" : searchProvider.search(query);
        String around = readAroundProvider == null || blockId == null ? "" : readAroundProvider.readAround(blockId, 2);
        return assemble(system, outline, search, around, transcript, document);
    }

    private Result fit(Map<ContextLayer, String> requested) {
        EnumMap<ContextLayer, String> included = new EnumMap<ContextLayer, String>(ContextLayer.class);
        StringBuilder text = new StringBuilder(); int chars = 0; int tokens = 0;
        for (ContextLayer layer : ContextLayer.values()) {
            String value = requested.get(layer); if (value == null || value.isEmpty()) continue;
            int room = Math.min(maxChars - chars, (maxTokens - tokens) * 4);
            if (room <= 0) break;
            String part = value.length() <= room ? value : value.substring(0, room);
            included.put(layer, part); if (text.length() > 0) text.append('\n'); text.append(part);
            chars += part.length(); tokens += tokenEstimate(part);
            if (part.length() < value.length()) break;
        }
        return new Result(text.toString(), included, chars, tokens);
    }

    private int tokenEstimate(String value) { return (value.length() + 3) / 4; }

    public static final class Result {
        private final String text; private final Map<ContextLayer, String> layers;
        private final int chars; private final int tokens;
        private Result(String text, Map<ContextLayer, String> layers, int chars, int tokens) {
            this.text = text; this.layers = Collections.unmodifiableMap(new EnumMap<ContextLayer, String>(layers)); this.chars = chars; this.tokens = tokens;
        }
        public String getText() { return text; }
        public Map<ContextLayer, String> getLayers() { return layers; }
        public String getLayer(ContextLayer layer) { return layers.get(layer); }
        public int getChars() { return chars; } public int getTokens() { return tokens; }
    }
}
