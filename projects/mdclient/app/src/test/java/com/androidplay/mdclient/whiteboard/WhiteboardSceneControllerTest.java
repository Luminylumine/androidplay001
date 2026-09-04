package com.androidplay.mdclient.whiteboard;

import android.graphics.PointF;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public final class WhiteboardSceneControllerTest {
    @Test public void stickersAndTextBoxesAreIndependentObjects() {
        WhiteboardSceneController scene = new WhiteboardSceneController();
        StickerItem first = scene.addSticker("$$a$$", 1, 2);
        StickerItem second = scene.addSticker("$$b$$", 3, 4);
        scene.addTextBox("one", 5, 6); scene.addTextBox("two", 7, 8);
        assertEquals(4, scene.items().size());
        assertNotEquals(first.id, second.id);
        assertEquals("$$a$$", first.markdown);
    }

    @Test public void strokeColorIsFrozenAndStrokeEraserDoesNotAddInk() {
        WhiteboardSceneController scene = new WhiteboardSceneController();
        InkStrokeItem red = scene.addStroke(0xffff0000, 3, InkStrokeItem.ToolKind.PEN, new PointF(0, 0));
        red.points.add(new PointF(100, 0));
        scene.addStroke(0xff0000ff, 3, InkStrokeItem.ToolKind.PEN, new PointF(0, 100));
        assertEquals(2, scene.items().size());
        assertEquals(1, scene.eraseStrokeAt(50, 0, 8));
        assertEquals(1, scene.items().size());
        assertEquals(0xffff0000, red.color);
    }
}
