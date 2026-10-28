package io.github.jnesew.comicviewer.render;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class RenderExperimentTest {
    private static final long MIB = 1024L * 1024L;

    @Test public void requestedCapacityIsClippedAndStandardCannotEnableExtraWork() {
        RenderTuning tuning = new RenderTuning(true, true, true, true, 96);
        assertEquals(96 * MIB, tuning.cacheBytes(512 * MIB, BufferingPolicy.HIGH));
        assertEquals(64 * MIB, tuning.cacheBytes(128 * MIB, BufferingPolicy.HIGH));
        assertEquals(0, tuning.cacheBytes(512 * MIB, BufferingPolicy.STANDARD));
        assertEquals(BufferingPolicy.speculativeBytes(BufferingPolicy.HIGH, 256 * MIB),
                RenderTuning.BASELINE.cacheBytes(256 * MIB, BufferingPolicy.HIGH));
    }

    @Test public void pendingOutputsShareTheSameCeilingAndReleaseForAnotherOwner() {
        AtomicBoolean room = new AtomicBoolean(true);
        SpeculativeTileCache cache = new SpeculativeTileCache(512 * MIB, BufferingPolicy.HIGH,
                room::get, () -> 0);
        assertTrue(cache.reserve(32 * MIB));
        assertFalse(cache.reserve(17 * MIB));
        assertEquals(32 * MIB, cache.reservedBytes());
        assertTrue(cache.reserve(16 * MIB));
        assertEquals(0, cache.freeBytes());
        cache.release(32 * MIB);
        assertTrue(cache.reserve(2 * MIB));
        room.set(false);
        assertFalse(cache.reserve(MIB));
        cache.release(18 * MIB);
        assertEquals(0, cache.reservedBytes());
        cache.setForeground(false);
        assertFalse(cache.reserve(MIB));
    }

    @Test public void coverageIntegratesIdleTimeAndStopsAtCaseEnd() {
        RenderMetrics metrics = new RenderMetrics();
        metrics.start(1_000_000);
        metrics.beginFrame(2_000_000);
        metrics.area(100, true); metrics.area(100, false); metrics.endFrame();
        metrics.beginFrame(6_000_000);
        metrics.area(100, false); metrics.endFrame();
        java.util.Map<String, Number> values = metrics.finish(10_000_000);
        // First millisecond has no visible frame, followed by four ms with half missing.
        assertEquals(3.0, values.get("exact_missing_area_ms").doubleValue(), .00001);
        metrics.beginFrame(20_000_000); metrics.decode(true, 20, 20);
        assertEquals(2, values.get("draws").intValue());
        assertEquals(0, metrics.finish(30_000_000).get("decoded_tiles").intValue());
    }
}
