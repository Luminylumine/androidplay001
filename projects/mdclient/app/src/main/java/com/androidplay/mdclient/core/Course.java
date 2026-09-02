package com.androidplay.mdclient.core;

public final class Course {
    public final String id;
    public final String title;
    public final String description;
    public final long createdWallMs;
    public final long updatedWallMs;

    public Course(String id, String title, String description, long createdWallMs, long updatedWallMs) {
        this.id = id; this.title = title; this.description = description;
        this.createdWallMs = createdWallMs; this.updatedWallMs = updatedWallMs;
    }
}
