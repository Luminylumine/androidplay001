package com.androidplay.mdclient.whiteboard;

import android.graphics.PointF;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

public final class WhiteboardSceneController {
    private final List<WhiteboardItem> items = new ArrayList<>();
    private int nextZ;

    public synchronized List<WhiteboardItem> items() {
        List<WhiteboardItem> copy = new ArrayList<>(items);
        Collections.sort(copy, Comparator.comparingInt(item -> item.zIndex));
        return copy;
    }

    public synchronized InkStrokeItem addStroke(int color, float width, InkStrokeItem.ToolKind kind, PointF start) {
        InkStrokeItem item = new InkStrokeItem(start.x, start.y, color, width, kind);
        item.points.add(new PointF(start.x, start.y)); item.zIndex = nextZ++;
        items.add(item); return item;
    }

    public synchronized StickerItem addSticker(String markdown, float x, float y) {
        StickerItem item = new StickerItem(x, y, 300, 92, markdown); item.zIndex = nextZ++; items.add(item); return item;
    }

    public synchronized TextBoxItem addTextBox(String text, float x, float y) {
        TextBoxItem item = new TextBoxItem(x, y, 320, 100, text); item.zIndex = nextZ++; items.add(item); return item;
    }

    public synchronized void moveItem(String id, float x, float y) { WhiteboardItem item = find(id); if (item != null) { item.x = x; item.y = y; bringToFront(id); } }
    public synchronized void bringToFront(String id) { WhiteboardItem item = find(id); if (item != null) item.zIndex = nextZ++; }
    public synchronized void removeItem(String id) { for (Iterator<WhiteboardItem> it = items.iterator(); it.hasNext();) if (it.next().id.equals(id)) { it.remove(); return; } }

    public synchronized int eraseStrokeAt(float x, float y, float radius) {
        int removed = 0;
        for (Iterator<WhiteboardItem> it = items.iterator(); it.hasNext();) { WhiteboardItem item = it.next(); if (!(item instanceof InkStrokeItem)) continue; InkStrokeItem stroke = (InkStrokeItem)item; if (stroke.toolKind == InkStrokeItem.ToolKind.PIXEL_ERASER) continue; if (hits(stroke, x, y, radius)) { it.remove(); removed++; } }
        return removed;
    }

    private boolean hits(InkStrokeItem stroke, float x, float y, float radius) { for (int i=1;i<stroke.points.size();i++) { PointF a=stroke.points.get(i-1), b=stroke.points.get(i); if (distanceToSegment(x,y,a.x,a.y,b.x,b.y) <= radius + stroke.strokeWidth/2f) return true; } return !stroke.points.isEmpty() && Math.hypot(stroke.points.get(0).x-x, stroke.points.get(0).y-y) <= radius + stroke.strokeWidth/2f; }
    private float distanceToSegment(float px,float py,float ax,float ay,float bx,float by){float dx=bx-ax,dy=by-ay;if(dx==0&&dy==0)return (float)Math.hypot(px-ax,py-ay);float t=((px-ax)*dx+(py-ay)*dy)/(dx*dx+dy*dy);t=Math.max(0,Math.min(1,t));return (float)Math.hypot(px-(ax+t*dx),py-(ay+t*dy));}
    private WhiteboardItem find(String id) { for (WhiteboardItem item : items) if (item.id.equals(id)) return item; return null; }
}
