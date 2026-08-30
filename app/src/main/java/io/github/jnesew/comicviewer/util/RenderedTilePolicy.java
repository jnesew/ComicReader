package io.github.jnesew.comicviewer.util;

/**
 * Selects bounded power-of-two render levels for documents such as PDFs that rasterize on demand.
 *
 * <p>The chosen level never exceeds the display scale, except below the minimum supported level.
 * This keeps a full tile at least {@link #TARGET_BITMAP_SIZE} pixels wide on screen, so crossing a
 * render-level boundary cannot suddenly quadruple the visible bitmap working set.</p>
 */
public final class RenderedTilePolicy {
    public static final int TARGET_BITMAP_SIZE = 512;
    public static final float MIN_RENDER_SCALE = 1f / 512f;
    public static final float MAX_RENDER_SCALE = 16f;

    private RenderedTilePolicy() {}

    public static float chooseRenderScale(float displayScale) {
        if (!(displayScale > 0f)) return MIN_RENDER_SCALE;
        float target = Math.max(
                MIN_RENDER_SCALE,
                Math.min(MAX_RENDER_SCALE, displayScale));
        float level = MIN_RENDER_SCALE;
        while (level < MAX_RENDER_SCALE && level * 2f <= target) level *= 2f;
        return level;
    }

    public static int sourceTileSize(float renderScale) {
        float bounded = renderScale > 0f
                ? Math.max(MIN_RENDER_SCALE, Math.min(MAX_RENDER_SCALE, renderScale))
                : MIN_RENDER_SCALE;
        return Math.max(1, Math.round(TARGET_BITMAP_SIZE / bounded));
    }

    /** Returns zero when no coarser cached level can exist. */
    public static float nextCoarserScale(float renderScale) {
        if (!(renderScale > MIN_RENDER_SCALE)) return 0f;
        return Math.max(MIN_RENDER_SCALE, renderScale / 2f);
    }
}
