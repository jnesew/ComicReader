package io.github.jnesew.comicviewer.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BufferingPolicyTest {
    private static final long MIB = 1024L * 1024L;

    @Test public void standardRetainsCurrentVisibleLimitWithoutOffscreenWork() {
        assertEquals(BufferingPolicy.STANDARD, BufferingPolicy.normalize("unknown"));
        assertEquals(0, BufferingPolicy.lookaheadViewports(BufferingPolicy.STANDARD));
        assertEquals(0, BufferingPolicy.queuedTiles(BufferingPolicy.STANDARD));
        assertEquals(0L, BufferingPolicy.speculativeBytes(BufferingPolicy.STANDARD, 512L * MIB));
        assertEquals(64L * MIB, BufferingPolicy.visibleBytesPerRenderer(512L * MIB));
        assertEquals(24L * MIB, BufferingPolicy.visibleBytesPerRenderer(128L * MIB));
    }

    @Test public void extraTilesFitSharedHeadroomAcrossThreeIssues() {
        assertEquals(0L, BufferingPolicy.speculativeBytes(BufferingPolicy.HIGH, 128L * MIB));
        for (long heap : new long[]{192L * MIB, 256L * MIB, 512L * MIB, 1024L * MIB}) {
            long visible = 3L * BufferingPolicy.visibleBytesPerRenderer(heap);
            long standard = BufferingPolicy.speculativeBytes(BufferingPolicy.STANDARD, heap);
            long increased = BufferingPolicy.speculativeBytes(BufferingPolicy.INCREASED, heap);
            long high = BufferingPolicy.speculativeBytes(BufferingPolicy.HIGH, heap);
            assertTrue(standard <= increased && increased <= high);
            assertTrue(visible + high + Math.max(16L * MIB, heap / 10L)
                    <= Math.max(visible + Math.max(16L * MIB, heap / 10L), heap * 3L / 5L));
        }
    }

    @Test public void visibleQueueAlwaysPrecedesSpeculationWhilePreservingRequestOrder() {
        assertTrue(BufferingPolicy.compareTasks(false, 9, true, 1) < 0);
        assertTrue(BufferingPolicy.compareTasks(true, 1, false, 9) > 0);
        assertTrue(BufferingPolicy.compareTasks(false, 3, false, 4) < 0);
        assertTrue(BufferingPolicy.compareTasks(true, 3, true, 4) < 0);
        assertEquals(1, BufferingPolicy.lookaheadViewports(BufferingPolicy.INCREASED));
        assertEquals(3, BufferingPolicy.lookaheadViewports(BufferingPolicy.HIGH));
    }
}
