package io.github.jnesew.comicviewer.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class LibraryGridDensityTest {
    @Test
    public void standardIsTheFallbackAndPreservesExistingGeometry() {
        LibraryGridDensity standard = LibraryGridDensity.fromKey("unknown");

        assertEquals(LibraryGridDensity.STANDARD, standard);
        assertEquals(132, standard.minimumCardWidthDp);
        assertEquals(205, standard.coverHeightDp);
        assertEquals(16, standard.titleTextSp);
    }

    @Test
    public void persistedKeysRoundTrip() {
        for (LibraryGridDensity density : LibraryGridDensity.values()) {
            assertEquals(density, LibraryGridDensity.fromKey(density.key));
        }
    }

    @Test
    public void presetsBecomeProgressivelyDenser() {
        LibraryGridDensity[] values = LibraryGridDensity.values();
        for (int index = 1; index < values.length; index++) {
            assertTrue(values[index - 1].minimumCardWidthDp > values[index].minimumCardWidthDp);
            assertTrue(values[index - 1].coverHeightDp > values[index].coverHeightDp);
        }
    }
}
