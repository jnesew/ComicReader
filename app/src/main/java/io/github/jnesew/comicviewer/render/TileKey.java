package io.github.jnesew.comicviewer.render;

/** Source-grid address, including the resolution at which its pixels were decoded. */
public record TileKey(int page, int sample, float renderScale, int x, int y) { }
