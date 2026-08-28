package com.localtools.comicviewer.model;

public final class PageInfo {
    public final String name;
    public final int width;
    public final int height;

    public PageInfo(String name, int width, int height) {
        this.name = name;
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
    }

    public float aspectHeight() {
        return (float) height / (float) width;
    }
}
