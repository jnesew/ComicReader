package io.github.jnesew.comicviewer.util;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class LibraryScanResultTest {
    @Test
    public void unchangedOnlyScanIsSilent() {
        LibraryScanResult result = new LibraryScanResult(0, 0, 35, 0, 0, false);

        assertFalse(result.shouldShow());
        assertFalse(result.shouldPersist());
    }

    @Test
    public void nonZeroWorkGetsATimedNotice() {
        LibraryScanResult result = new LibraryScanResult(2, 0, 30, 1, 0, false);

        assertTrue(result.shouldShow());
        assertFalse(result.shouldPersist());
    }

    @Test
    public void incompleteScanPersistsEvenWithoutChanges() {
        LibraryScanResult result = new LibraryScanResult(0, 0, 0, 0, 0, true);

        assertTrue(result.shouldShow());
        assertTrue(result.shouldPersist());
    }

    @Test
    public void negativeProviderCountsCannotCreateAResult() {
        LibraryScanResult result = new LibraryScanResult(-1, -1, -1, -1, -1, false);

        assertFalse(result.shouldShow());
    }
}
