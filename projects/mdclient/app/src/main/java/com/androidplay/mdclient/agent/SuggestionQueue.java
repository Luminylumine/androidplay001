package com.androidplay.mdclient.agent;

import java.util.ArrayDeque;
import java.util.Queue;

public final class SuggestionQueue {
    private final Queue<Suggestion> queue = new ArrayDeque<Suggestion>();
    public synchronized void offer(Suggestion suggestion) { if (suggestion != null) queue.offer(suggestion); }
    public synchronized Suggestion poll() { return queue.poll(); }
    public synchronized Suggestion peek() { return queue.peek(); }
    public synchronized int size() { return queue.size(); }
    public synchronized void clear() { queue.clear(); }
}
