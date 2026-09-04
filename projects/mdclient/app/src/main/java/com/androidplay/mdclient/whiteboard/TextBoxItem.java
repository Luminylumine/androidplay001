package com.androidplay.mdclient.whiteboard;

public final class TextBoxItem extends WhiteboardItem {
    public String text;

    public TextBoxItem(float x, float y, float width, float height, String text) {
        super(x, y, width, height);
        this.text = text == null ? "" : text;
    }
}
