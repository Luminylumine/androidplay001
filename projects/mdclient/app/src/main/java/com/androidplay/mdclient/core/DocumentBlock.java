package com.androidplay.mdclient.core;

public final class DocumentBlock {
    public final String id;
    public final String courseId;
    public final String sessionId;
    public final int position;
    public final String kind;
    public final String content;
    public final int revision;

    public DocumentBlock(String id, String courseId, String sessionId, int position,
                         String kind, String content, int revision) {
        this.id = id; this.courseId = courseId; this.sessionId = sessionId;
        this.position = position; this.kind = kind; this.content = content; this.revision = revision;
    }
}
