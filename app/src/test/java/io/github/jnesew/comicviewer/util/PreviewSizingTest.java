package io.github.jnesew.comicviewer.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class PreviewSizingTest {
    @Test
    public void decodeSampleBoundsPortraitAndStripPages() {
        assertEquals(16, PreviewSizing.decodeSample(2400, 3600, 112, 144));
        assertEquals(64, PreviewSizing.decodeSample(10000, 1000, 112, 144));
        assertEquals(64, PreviewSizing.decodeSample(1000, 10000, 112, 144));
        assertEquals(1, PreviewSizing.decodeSample(80, 100, 112, 144));
    }

    @Test
    public void fitInsidePreservesAspectAndDoesNotUpscaleRaster() {
        PreviewSizing.Size portrait = PreviewSizing.fitInside(
                2400, 3600, 112, 144, false);
        assertEquals(96, portrait.width);
        assertEquals(144, portrait.height);

        PreviewSizing.Size small = PreviewSizing.fitInside(80, 100, 112, 144, false);
        assertEquals(80, small.width);
        assertEquals(100, small.height);
    }

    @Test
    public void fitInsideCanFillPreviewForVectorPage() {
        PreviewSizing.Size vector = PreviewSizing.fitInside(50, 100, 112, 144, true);
        assertEquals(72, vector.width);
        assertEquals(144, vector.height);
    }

    @Test
    public void invalidDimensionsAreRejected() {
        try {
            PreviewSizing.decodeSample(0, 100, 112, 144);
            org.junit.Assert.fail("Expected invalid preview dimensions to be rejected.");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
