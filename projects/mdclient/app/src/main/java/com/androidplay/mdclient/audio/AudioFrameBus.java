package com.androidplay.mdclient.audio;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Bounded fan-out bus. The producer never waits for an optional consumer. */
public final class AudioFrameBus {
    public static final class Frame {
        public final short[] samples;
        public final long frameStart;
        public final long frameEnd;
        public final long audioTimeNs;

        public Frame(short[] samples, long frameStart, long frameEnd, long audioTimeNs) {
            this.samples = samples; this.frameStart = frameStart; this.frameEnd = frameEnd; this.audioTimeNs = audioTimeNs;
        }
    }

    public final class Subscription implements AutoCloseable {
        private final ArrayBlockingQueue<Frame> queue;
        private volatile boolean closed;
        private Subscription(int capacity) { queue = new ArrayBlockingQueue<>(capacity); }
        public Frame poll(long timeoutMs) throws InterruptedException { return queue.poll(timeoutMs, TimeUnit.MILLISECONDS); }
        public int depth() { return queue.size(); }
        private boolean offer(Frame frame) { return !closed && queue.offer(frame); }
        @Override public void close() { synchronized (AudioFrameBus.this) { closed = true; subscribers.remove(this); queue.clear(); } }
    }

    private final int capacity;
    private final List<Subscription> subscribers = new ArrayList<>();
    public AudioFrameBus(int capacity) { if (capacity <= 0) throw new IllegalArgumentException("capacity"); this.capacity = capacity; }
    public synchronized Subscription subscribe() { Subscription subscription = new Subscription(capacity); subscribers.add(subscription); return subscription; }
    public synchronized int subscriberCount() { return subscribers.size(); }
    public synchronized int publish(Frame frame) {
        int dropped = 0;
        for (Subscription subscription : new ArrayList<>(subscribers)) if (!subscription.offer(frame)) dropped++;
        return dropped;
    }
}
