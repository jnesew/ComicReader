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

    public static final class Region {
        public final int left, top, right, bottom, width, height;
        public final float displayScale;

        public Region(int left, int top, int right, int bottom,
                int width, int height, float displayScale) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.width = width;
            this.height = height;
            this.displayScale = displayScale;
        }
    }

    /** Budget all visible pages together, leaving cache headroom for in-flight/fallback tiles. */
    public static float[] frameScales(java.util.List<Region> regions, long budgetBytes) {
        float[] scales = new float[regions.size()];
        for (int i = 0; i < scales.length; i++) {
            scales[i] = chooseRenderScale(regions.get(i).displayScale);
        }
        while (true) {
            long total = 0L;
            long largest = -1L;
            int reduce = -1;
            for (int i = 0; i < scales.length; i++) {
                long bytes = tileBytes(regions.get(i), scales[i]);
                total += bytes;
                if (scales[i] > MIN_RENDER_SCALE && bytes > largest) {
                    largest = bytes;
                    reduce = i;
                }
            }
            if (total <= budgetBytes || reduce < 0) return scales;
            scales[reduce] = nextCoarserScale(scales[reduce]);
        }
    }

    public static long tileBytes(Region region, float scale) {
        int edge = sourceTileSize(scale);
        int left = region.left / edge * edge;
        int top = region.top / edge * edge;
        long right = Math.min(region.width, ((long) region.right + edge - 1) / edge * edge);
        long bottom = Math.min(region.height, ((long) region.bottom + edge - 1) / edge * edge);
        return 4L * (long) Math.ceil((right - left) * (double) scale)
                * (long) Math.ceil((bottom - top) * (double) scale);
    }

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
