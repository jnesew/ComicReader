package io.github.jnesew.comicviewer.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public class BufferingPolicyTest {
    private static final long MIB = 1024L * 1024L;

    @Test public void highDefaultUsesTestedMemoryTierAndPreservesSmallerDeviceFallback() {
        assertEquals(BufferingPolicy.HIGH,
                BufferingPolicy.deviceDefault(7680L * MIB, 256L * MIB, false));
        assertEquals(BufferingPolicy.HIGH,
                BufferingPolicy.deviceDefault(12L * 1024L * MIB, 512L * MIB, false));
        assertEquals(BufferingPolicy.HIGH,
                BufferingPolicy.deviceDefault(6800L * MIB, 256L * MIB, false));
        assertEquals(BufferingPolicy.STANDARD,
                BufferingPolicy.deviceDefault(6799L * MIB, 256L * MIB, false));
        assertEquals(BufferingPolicy.STANDARD,
                BufferingPolicy.deviceDefault(6L * 1024L * MIB, 512L * MIB, false));
        assertEquals(BufferingPolicy.STANDARD,
                BufferingPolicy.deviceDefault(8L * 1024L * MIB, 128L * MIB, false));
        assertEquals(BufferingPolicy.STANDARD,
                BufferingPolicy.deviceDefault(8L * 1024L * MIB, 512L * MIB, true));
        assertEquals(BufferingPolicy.STANDARD, BufferingPolicy.deviceDefault(0, 0, false));
    }

    @Test public void explicitChoicesSurviveDefaultChangesAndAutoCanBeRestored() {
        assertEquals(BufferingPolicy.HIGH,
                BufferingPolicy.resolvePreference(null, BufferingPolicy.HIGH));
        assertEquals(BufferingPolicy.STANDARD,
                BufferingPolicy.resolvePreference(null, BufferingPolicy.STANDARD));
        for (String choice : new String[]{BufferingPolicy.STANDARD, BufferingPolicy.INCREASED,
                BufferingPolicy.HIGH}) {
            assertEquals(choice, BufferingPolicy.resolvePreference(choice, BufferingPolicy.HIGH));
            assertEquals(choice, BufferingPolicy.resolvePreference(choice, BufferingPolicy.STANDARD));
        }
        assertEquals(BufferingPolicy.HIGH, BufferingPolicy.resolvePreference(
                BufferingPolicy.DEVICE_DEFAULT, BufferingPolicy.HIGH));
        assertEquals(BufferingPolicy.DEVICE_DEFAULT, BufferingPolicy.preferenceChoice("invalid"));
    }

    @Test public void nativeAndSystemPressureBlockWorkEvenWithAmpleJavaHeap() {
        assertTrue(memoryRoom(220, 64, 2048, false, false));
        assertFalse(memoryRoom(220, 400, 2048, false, false));
        assertFalse(memoryRoom(220, 64, 500, false, false));
        assertFalse(memoryRoom(220, 64, 2048, true, false));
        assertFalse(memoryRoom(220, 64, 2048, false, true));
        assertFalse(memoryRoom(15, 64, 2048, false, false));
        assertFalse(BufferingPolicy.hasMemoryRoom(256L * MIB, 220L * MIB,
                64L * MIB, 0L, 2048L * MIB, 200L * MIB, false, false));
    }

    private static boolean memoryRoom(long javaFree, long nativeUsed, long systemFree,
            boolean lowMemory, boolean lowRam) {
        return BufferingPolicy.hasMemoryRoom(256L * MIB, javaFree * MIB, nativeUsed * MIB,
                8192L * MIB, systemFree * MIB, 200L * MIB, lowMemory, lowRam);
    }

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
