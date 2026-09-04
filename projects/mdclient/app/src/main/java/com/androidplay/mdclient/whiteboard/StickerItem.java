package com.androidplay.mdclient.whiteboard;

public final class StickerItem extends WhiteboardItem {
    public final String markdown;

    public StickerItem(float x, float y, float width, float height, String markdown) {
        super(x, y, width, height);
        this.markdown = markdown == null ? "" : markdown;
    }
}
