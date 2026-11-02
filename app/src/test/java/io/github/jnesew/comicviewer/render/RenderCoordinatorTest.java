package io.github.jnesew.comicviewer.render;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

public class RenderCoordinatorTest {
    private static final long HEAP = 512L * 1024L * 1024L;

    @Test public void backingImagesAndRunningOutputShareOneAllowanceAcrossDocuments() {
        long mib = 1024L * 1024L;
        RenderCoordinator coordinator = new RenderCoordinator(128 * mib,
                BufferingPolicy.STANDARD, () -> true, () -> 0L);
        Object first = new Object(), second = new Object();
        coordinator.registerOwner(first); coordinator.registerOwner(second);
        assertTrue(coordinator.claimBacking(first, 32 * mib));
        assertTrue(coordinator.reserveDecode(20 * mib));
        assertFalse(coordinator.claimBacking(second, 9 * mib));
        assertTrue(coordinator.claimBacking(second, 8 * mib));
        assertFalse(coordinator.reserveDecode(mib));
        org.junit.Assert.assertEquals(coordinator.pixelLimitBytes(), coordinator.pixelBytes());
        coordinator.releaseDecode(20 * mib);
        assertTrue(coordinator.reserveDecode(20 * mib));
        coordinator.releaseDecode(20 * mib);
        assertTrue(coordinator.claimBacking(first, 0));
        assertTrue(coordinator.claimBacking(second, 0));
        org.junit.Assert.assertEquals(0, coordinator.pixelBytes());
    }

    @Test public void backgroundCannotRestartPrefetchAndPressureCooldownSurvivesResume() {
        AtomicLong now = new AtomicLong(1_000L);
        RenderCoordinator cache = new RenderCoordinator(
                HEAP, BufferingPolicy.HIGH, () -> true, now::get);
        cache.setForeground(false);
        assertFalse(cache.enabled());
        assertFalse(cache.beginSpeculativeDecode());
        cache.onMemoryPressure();
        cache.setForeground(true);
        now.set(30_999L);
        assertFalse(cache.beginSpeculativeDecode());
        now.set(31_000L);
        assertTrue(cache.beginSpeculativeDecode());
        cache.endSpeculativeDecode();
    }

    @Test public void memoryGateAndVisiblePriorityApplyAcrossTheWholeSession() {
        AtomicBoolean memoryRoom = new AtomicBoolean(true);
        RenderCoordinator cache = new RenderCoordinator(
                HEAP, BufferingPolicy.HIGH, memoryRoom::get, () -> 0L);
        cache.visibleQueued();
        assertFalse(cache.beginSpeculativeDecode());
        cache.visibleFinished();
        assertTrue(cache.beginSpeculativeDecode());
        assertFalse(cache.beginSpeculativeDecode());
        cache.endSpeculativeDecode();
        memoryRoom.set(false);
        assertFalse(cache.beginSpeculativeDecode());
        memoryRoom.set(true);
        assertTrue(cache.beginSpeculativeDecode());
        cache.endSpeculativeDecode();
        cache.setLevel(BufferingPolicy.STANDARD);
        assertFalse(cache.beginSpeculativeDecode());
    }

    @Test public void failedMemorySampleDoesNotAuthorizeSpeculation() {
        RenderCoordinator cache = new RenderCoordinator(
                HEAP, BufferingPolicy.HIGH,
                () -> { throw new IllegalStateException("Unavailable memory sample"); }, () -> 0L);
        assertFalse(cache.beginSpeculativeDecode());
    }

    @Test public void memoryCheckOutOfMemoryDoesNotPermanentlyBlockPrefetch() {
        AtomicInteger samples = new AtomicInteger();
        RenderCoordinator cache = new RenderCoordinator(
                HEAP, BufferingPolicy.HIGH, () -> {
                    if (samples.incrementAndGet() == 2) throw new OutOfMemoryError("Memory sample failed");
                    return true;
                }, () -> 0L);

        // Admission checks memory again after taking the shared decode permit.
        assertThrows(OutOfMemoryError.class, cache::beginSpeculativeDecode);
        assertTrue("A failed admission stranded the session's only prefetch permit",
                cache.beginSpeculativeDecode());
        assertFalse("Recovery allowed concurrent speculative decodes", cache.beginSpeculativeDecode());
        cache.endSpeculativeDecode();
        assertTrue(cache.beginSpeculativeDecode());
        cache.endSpeculativeDecode();
    }
}
