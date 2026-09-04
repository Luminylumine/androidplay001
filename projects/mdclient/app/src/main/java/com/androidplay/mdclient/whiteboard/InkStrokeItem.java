package com.androidplay.mdclient.whiteboard;

import android.graphics.PointF;
import java.util.ArrayList;
import java.util.List;

public final class InkStrokeItem extends WhiteboardItem {
    public final List<PointF> points = new ArrayList<>();
    public final int color;
    public final float strokeWidth;
    public final ToolKind toolKind;

    public InkStrokeItem(float x, float y, int color, float strokeWidth, ToolKind toolKind) {
        super(x, y, 0, 0);
        this.color = color;
        this.strokeWidth = strokeWidth;
        this.toolKind = toolKind;
    }

    public enum ToolKind { PEN, PIXEL_ERASER }
}
