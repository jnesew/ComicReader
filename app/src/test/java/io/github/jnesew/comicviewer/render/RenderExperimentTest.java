package io.github.jnesew.comicviewer.render;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class RenderExperimentTest {
    private static final long MIB = 1024L * 1024L;

    @Test public void fallbackCoverageSeparatesMissingDetailFromUncoveredPixels() {
        RenderMetrics metrics = new RenderMetrics(); metrics.start(1_000_000);
        metrics.beginFrame(1_000_000);
        metrics.area(100, true, 25); metrics.area(100, false, 100); metrics.endFrame();
        assertEquals(.5, metrics.missingFraction(), .00001);
        assertEquals(.125, metrics.uncoveredFraction(), .00001);
        java.util.Map<String, Number> values = metrics.finish(9_000_000);
        assertEquals(4, values.get("exact_missing_area_ms").doubleValue(), .00001);
        assertEquals(1, values.get("uncovered_area_ms").doubleValue(), .00001);
        assertEquals(3, values.get("fallback_area_ms").doubleValue(), .00001);
        assertEquals(1, values.get("uncovered_frames").longValue());
    }

    @Test public void unchangedPlansDoNotWakeOwnersAndCancelledPlansReleaseTheirPriority() {
        AtomicBoolean room = new AtomicBoolean(true);
        SpeculativeTileCache cache = new SpeculativeTileCache(256 * MIB, BufferingPolicy.HIGH,
                room::get, () -> 0);
        Object first = new Object(), second = new Object();
        java.util.concurrent.atomic.AtomicInteger wakes = new java.util.concurrent.atomic.AtomicInteger();
        cache.onWorkAvailable(first, wakes::incrementAndGet);
        TileKey key = new TileKey(1, 1, 1, 0, 0, 512, 512);
        cache.plan(second, java.util.Map.of(key, 0));
        assertEquals(1, wakes.get());
        room.set(false);
        assertFalse(cache.reserve(second, key, MIB));
        cache.plan(second, java.util.Map.of(key, 0));
        assertEquals(1, wakes.get()); // A persistent refusal cannot create a plan-change pump.
        cache.plan(second, java.util.Map.of(key, 0));
        assertEquals(1, wakes.get());
        cache.cancelPlan(second);
        assertEquals(2, wakes.get());
        assertFalse(cache.planned(second, key));
        cache.cancelPlan(second);
        assertEquals(2, wakes.get());
    }

    @Test public void readerKeepsCurrentCeilingAndClipsExplicitExtraRetention() {
        RenderTuning reader = RenderTuning.READER;
        assertTrue(reader.prioritized); assertTrue(reader.refill); assertTrue(reader.boundedAdmission());
        assertFalse(reader.byteLookahead); assertFalse(reader.historyFallback);
        assertEquals(BufferingPolicy.speculativeBytes(BufferingPolicy.HIGH, 256 * MIB),
                reader.cacheBytes(256 * MIB, BufferingPolicy.HIGH));
        assertEquals(64 * MIB, RenderTuning.reader(96).cacheBytes(256 * MIB, BufferingPolicy.HIGH));
        assertEquals(32 * MIB, RenderTuning.reader(64).cacheBytes(128 * MIB, BufferingPolicy.HIGH));
        assertEquals(0, RenderTuning.reader(64).cacheBytes(256 * MIB, BufferingPolicy.STANDARD));
        assertEquals(BufferingPolicy.speculativeBytes(BufferingPolicy.INCREASED, 256 * MIB),
                RenderTuning.reader(64).cacheBytes(256 * MIB, BufferingPolicy.INCREASED));
    }

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

    @Test public void admissionControlDoesNotChangeTheSelectedRangeOrCapacity() {
        RenderTuning tuning = new RenderTuning(false, false, false, false, 0, true);
        assertTrue(tuning.boundedAdmission());
        assertFalse(tuning.byteLookahead); assertFalse(tuning.refill); assertFalse(tuning.predictedFrame);
        assertEquals(RenderTuning.BASELINE.cacheBytes(256 * MIB, BufferingPolicy.HIGH),
                tuning.cacheBytes(256 * MIB, BufferingPolicy.HIGH));
        assertEquals(0, tuning.cacheBytes(256 * MIB, BufferingPolicy.STANDARD));
    }

    @Test public void checkpointsContinueCollectingAndIgnoreOutOfOrderClockSamples() {
        RenderMetrics metrics = new RenderMetrics(); metrics.start(1_000_000);
        metrics.beginFrame(2_000_000); metrics.area(100, true); metrics.endFrame();
        assertEquals(3, metrics.snapshot(4_000_000).get("exact_missing_area_ms").doubleValue(), .00001);
        metrics.snapshot(3_000_000); // A caller sampled before waiting for the metric lock.
        metrics.decode(true, 2_000_000, 3_000_000);
        assertEquals(4, metrics.finish(5_000_000).get("exact_missing_area_ms").doubleValue(), .00001);
        assertEquals(1, metrics.snapshot(9_000_000).get("decoded_tiles").intValue());
    }

    @Test public void combinedPeakUsesSimultaneousRetainedAndPendingBytes() {
        RenderMetrics metrics = new RenderMetrics(); metrics.start(1);
        metrics.memory(0, 0, 24 * MIB, 0); metrics.memory(0, 0, 0, 16 * MIB);
        assertEquals(24 * MIB, metrics.finish(2).get("peak_shared_plus_pending_bytes").longValue());
    }

    @Test public void diagnosticsDistinguishFullCapacityFromMemoryGuardRefusal() {
        AtomicBoolean room = new AtomicBoolean(true); RenderMetrics metrics = new RenderMetrics(); metrics.start(1);
        SpeculativeTileCache cache = new SpeculativeTileCache(256 * MIB, BufferingPolicy.HIGH,
                room::get, () -> 0, new RenderTuning(false, false, false, false, 64), metrics);
        cache.registerOwner(this, 16 * MIB, 32 * MIB); cache.residentBytes(this, 8 * MIB, 4 * MIB);
        assertEquals(36 * MIB, cache.futureGrowthBytes());
        assertTrue(cache.reserve(MIB)); assertFalse(cache.reserve(64 * MIB));
        room.set(false); assertFalse(cache.reserve(MIB));
        java.util.Map<String, Number> result = metrics.finish(2);
        assertEquals(2, result.get("admission_denials").intValue());
        assertEquals(1, result.get("capacity_denials").intValue());
        assertEquals(1, result.get("memory_denials").intValue());
        assertEquals(1, result.get("guard_refusals").intValue());
    }
}
