package com.androidplay.mdclient.ui;

/** Clamps user-adjustable pane ratios without imposing a fixed visual layout. */
public final class WorkspaceRatioPolicy {
    private WorkspaceRatioPolicy() { }
    public static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
    public static float top(float value) { return clamp(value, .30f, .70f); }
    public static float pdf(float value) { return clamp(value, .30f, .75f); }
    public static float logic(float value) { return clamp(value, .12f, .51f); }
    public static float agent(float value) { return clamp(value, .14f, .65f); }
}
