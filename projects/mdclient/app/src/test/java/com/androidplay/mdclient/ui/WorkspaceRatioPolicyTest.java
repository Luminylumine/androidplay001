package com.androidplay.mdclient.ui;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class WorkspaceRatioPolicyTest {
    @Test public void clampsRatiosToSketchMinimums() {
        assertEquals(.30f, WorkspaceRatioPolicy.top(-1), .001f);
        assertEquals(.30f, WorkspaceRatioPolicy.pdf(0), .001f);
        assertEquals(.12f, WorkspaceRatioPolicy.logic(0), .001f);
        assertEquals(.14f, WorkspaceRatioPolicy.agent(0), .001f);
        assertEquals(.70f, WorkspaceRatioPolicy.top(2), .001f);
    }
}
