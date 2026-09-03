package com.androidplay.mdclient.ui;

/** Session-scoped typed scratchpad projection. */
public final class ScratchpadState {
    public String sessionId = "demo-session";
    public String text = "这里为什么 rank 必须等于 n？\n老师说考试会考";
    public int selectionStart;
    public int selectionEnd;
    public long lastModified;
}
