package io.github.jnesew.comicviewer.render;

/** Source-grid address, resolution, and page geometry used to crop these pixels. */
public record TileKey(int page, int sample, float renderScale, int x, int y, int width, int height) { }
