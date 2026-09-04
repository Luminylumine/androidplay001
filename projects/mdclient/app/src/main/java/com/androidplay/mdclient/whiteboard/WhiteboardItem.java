package com.androidplay.mdclient.whiteboard;

import java.util.UUID;

public abstract class WhiteboardItem {
    public final String id = UUID.randomUUID().toString();
    public float x, y, width, height;
    public int zIndex;
    public final long createdAt = System.currentTimeMillis();

    protected WhiteboardItem(float x, float y, float width, float height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }
}
