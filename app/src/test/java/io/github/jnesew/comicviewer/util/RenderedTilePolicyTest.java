package io.github.jnesew.comicviewer.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RenderedTilePolicyTest {
    @Test
    public void tallCbzPagesFitTogetherAcrossReportedZoomRange() {
        long budget = 18L * 1024 * 1024;
        boolean reduced = false;
        for (float zoom : new float[]{0.6f, 0.96f, 1f, 1.2f, 1.65f, 2f, 4f}) {
            float displayScale = 1080f / 1900f * zoom;
            for (int offset : new int[]{0, 500, 1600, 2700}) {
                java.util.List<RenderedTilePolicy.Region> regions = java.util.Arrays.asList(
                        new RenderedTilePolicy.Region(0, offset, 1900, 3900,
                                1900, 3900, displayScale),
                        new RenderedTilePolicy.Region(0, 0, 1900, 1800,
                                1900, 3900, displayScale));
                float[] scales = RenderedTilePolicy.rasterFrameScales(regions, budget);
                long bytes = 0L;
                for (int i = 0; i < scales.length; i++) {
                    bytes += RenderedTilePolicy.rasterTileBytes(regions.get(i), scales[i]);
                    int sample = Math.round(1f / scales[i]);
                    assertTrue(sample >= 1 && sample <= 64 && (sample & (sample - 1)) == 0);
                    reduced |= displayScale > 0.5f && sample > 1;
                }
                assertTrue("Raster frame exceeds cache at " + zoom, bytes <= budget);
            }
        }
        assertTrue(reduced);
    }

    @Test
    public void smallRasterFramePreservesOriginalSampling() {
        java.util.List<RenderedTilePolicy.Region> regions = java.util.List.of(
                new RenderedTilePolicy.Region(0, 0, 800, 800, 1900, 3900, 0.6f));
        assertEquals(1f, RenderedTilePolicy.rasterFrameScales(regions, 18L * 1024 * 1024)[0], 0f);
    }

    @Test
    public void splitViewportWorkingSetFitsCacheAcrossZoomLevels() {
        long budget = 18L * 1024 * 1024;
        boolean reduced = false;
        for (float zoom : new float[]{0.6f, 1f, 1.2f, 1.8f, 2f, 4f, 8f}) {
            java.util.List<RenderedTilePolicy.Region> regions = java.util.Arrays.asList(
                    new RenderedTilePolicy.Region(31, 97, 1800, 2600, 4000, 6000, zoom),
                    new RenderedTilePolicy.Region(13, 53, 1800, 2600, 4000, 6000, zoom));
            float[] scales = RenderedTilePolicy.frameScales(regions, budget);
            long bytes = 0L;
            for (int i = 0; i < scales.length; i++) {
                bytes += RenderedTilePolicy.tileBytes(regions.get(i), scales[i]);
                reduced |= scales[i] < RenderedTilePolicy.chooseRenderScale(zoom);
            }
            assertTrue("Frame exceeds cache at " + zoom, bytes <= budget);
        }
        assertTrue("Must exercise resolution reduction", reduced);
    }

    @Test
    public void fittingFrameRetainsNormalResolution() {
        java.util.List<RenderedTilePolicy.Region> regions = java.util.Arrays.asList(
                new RenderedTilePolicy.Region(0, 0, 500, 800, 1000, 1500, 2f),
                new RenderedTilePolicy.Region(0, 0, 500, 800, 1000, 1500, 2f));
        float[] scales = RenderedTilePolicy.frameScales(regions, 18L * 1024 * 1024);
        assertEquals(2f, scales[0], 0f);
        assertEquals(2f, scales[1], 0f);
    }

    @Test
    public void renderLevelDoesNotJumpAheadAtPowerOfTwoBoundaries() {
        assertEquals(1f, RenderedTilePolicy.chooseRenderScale(1.99f), 0f);
        assertEquals(2f, RenderedTilePolicy.chooseRenderScale(2f), 0f);
        assertEquals(2f, RenderedTilePolicy.chooseRenderScale(2.01f), 0f);
        assertEquals(2f, RenderedTilePolicy.chooseRenderScale(3.99f), 0f);
        assertEquals(4f, RenderedTilePolicy.chooseRenderScale(4f), 0f);
    }

    @Test
    public void visibleTileFootprintStaysBoundedAcrossSupportedZooms() {
        for (float displayScale = RenderedTilePolicy.MIN_RENDER_SCALE;
                displayScale <= 128f;
                displayScale *= 1.03125f) {
            float renderScale = RenderedTilePolicy.chooseRenderScale(displayScale);
            float displayedTileEdge =
                    RenderedTilePolicy.sourceTileSize(renderScale) * displayScale;
            assertTrue(
                    "Tile edge was only " + displayedTileEdge + " at scale " + displayScale,
                    displayedTileEdge >= RenderedTilePolicy.TARGET_BITMAP_SIZE);
        }
    }

    @Test
    public void extremeScalesRemainWithinPdfTileLimits() {
        assertEquals(
                RenderedTilePolicy.MIN_RENDER_SCALE,
                RenderedTilePolicy.chooseRenderScale(0f),
                0f);
        assertEquals(
                RenderedTilePolicy.MIN_RENDER_SCALE,
                RenderedTilePolicy.chooseRenderScale(Float.NaN),
                0f);
        assertEquals(
                RenderedTilePolicy.MAX_RENDER_SCALE,
                RenderedTilePolicy.chooseRenderScale(Float.POSITIVE_INFINITY),
                0f);
        assertEquals(
                262_144,
                RenderedTilePolicy.sourceTileSize(RenderedTilePolicy.MIN_RENDER_SCALE));
        assertEquals(32, RenderedTilePolicy.sourceTileSize(RenderedTilePolicy.MAX_RENDER_SCALE));
    }

    @Test
    public void coarserLevelsReachMinimumThenStop() {
        assertEquals(1f, RenderedTilePolicy.nextCoarserScale(2f), 0f);
        assertEquals(
                RenderedTilePolicy.MIN_RENDER_SCALE,
                RenderedTilePolicy.nextCoarserScale(RenderedTilePolicy.MIN_RENDER_SCALE * 2f),
                0f);
        assertEquals(
                0f,
                RenderedTilePolicy.nextCoarserScale(RenderedTilePolicy.MIN_RENDER_SCALE),
                0f);
    }
}
