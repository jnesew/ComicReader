package io.github.jnesew.comicviewer.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public class ReadingPositionTest {
    @Test public void singleLongPageReportsPositionWithinThePage() {
        ReadingProgress progress = new ReadingProgress();
        progress.pageCount = 1;
        progress.scrollRatio = .42f;
        assertEquals(42, progress.positionPercent());
        progress.scrollRatio = .76f;
        assertEquals(76, progress.positionPercent());
    }

    @Test public void pageAndScrollOffsetAdvanceTogether() {
        ReadingProgress progress = new ReadingProgress();
        progress.pageCount = 4;
        progress.page = 2;
        assertEquals(50, progress.positionPercent());
        progress.scrollRatio = .5f;
        assertEquals(63, progress.positionPercent());
        progress.scrollRatio = 1f;
        assertEquals(75, progress.positionPercent());
        progress.page = 3;
        progress.scrollRatio = 0f;
        assertEquals(75, progress.positionPercent());
    }

    @Test public void reachingTheBottomDoesNotChangeExplicitReadStatus() {
        ReadingProgress progress = new ReadingProgress();
        progress.pageCount = 1;
        progress.scrollRatio = 1f;
        progress.lastOpened = 123;
        assertEquals(100, progress.positionPercent());
        assertFalse(progress.isCompleted());
        assertFalse(progress.isNew());
    }

    @Test public void unknownCountsAndInvalidOffsetsStayBounded() {
        ReadingProgress progress = new ReadingProgress();
        progress.scrollRatio = .5f;
        assertEquals(0, progress.positionPercent());
        progress.pageCount = -5;
        assertEquals(0, progress.positionPercent());
        progress.pageCount = 1;
        progress.page = -100;
        progress.scrollRatio = -5f;
        assertEquals(0, progress.positionPercent());
        progress.page = Integer.MAX_VALUE;
        progress.scrollRatio = 5f;
        assertEquals(100, progress.positionPercent());
        for (float invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            progress.scrollRatio = invalid;
            assertEquals(0, progress.positionPercent());
        }
    }
}
