package io.github.jnesew.comicviewer.render;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

public class SpeculativeTileCacheTest {
    private static final long HEAP = 512L * 1024L * 1024L;

    @Test public void backgroundCannotRestartPrefetchAndPressureCooldownSurvivesResume() {
        AtomicLong now = new AtomicLong(1_000L);
        SpeculativeTileCache cache = new SpeculativeTileCache(
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
        SpeculativeTileCache cache = new SpeculativeTileCache(
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
        SpeculativeTileCache cache = new SpeculativeTileCache(
                HEAP, BufferingPolicy.HIGH,
                () -> { throw new IllegalStateException("Unavailable memory sample"); }, () -> 0L);
        assertFalse(cache.beginSpeculativeDecode());
    }
}
