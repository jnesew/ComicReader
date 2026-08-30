package io.github.jnesew.comicviewer.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RenderedTilePolicyTest {
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
