package io.github.jnesew.comicviewer.render;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class RenderSchedulerTest {
    @Test public void queuedVisibleWorkPrecedesOptionalWorkAndCancellationRemovesDemand() throws Exception {
        RenderScheduler scheduler = new RenderScheduler(1);
        Object owner = new Object();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(3);
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        scheduler.execute(owner, work(2, () -> { entered.countDown(); await(release); }));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        try {
            scheduler.execute(new Object(), work(3, () -> { order.add("optional"); finished.countDown(); }));
            scheduler.execute(new Object(), work(2, () -> { order.add("visible"); finished.countDown(); }));
            scheduler.execute(owner, work(0, () -> { order.add("cleanup"); finished.countDown(); }));
            RenderScheduler.Work cancelled = work(1, () -> order.add("cancelled"));
            scheduler.execute(new Object(), cancelled);
            assertTrue(scheduler.remove(cancelled));
        } finally { release.countDown(); }
        assertTrue(finished.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("cleanup", "visible", "optional"), order);
    }

    @Test public void documentsAreSerializedAndRetiringWorkKeepsItsSlot() throws Exception {
        RenderScheduler scheduler = new RenderScheduler(2);
        Object owner = new Object();
        CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1);
        CountDownLatch sameOwner = new CountDownLatch(1), replacement = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger();
        Runnable blocking = () -> {
            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            entered.countDown(); await(release); active.decrementAndGet();
        };
        scheduler.execute(owner, work(2, blocking));
        scheduler.execute(owner, work(2, sameOwner::countDown));
        scheduler.execute(new Object(), work(2, blocking));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        try {
            scheduler.execute(new Object(), work(2, replacement::countDown));
            assertFalse(sameOwner.await(100, TimeUnit.MILLISECONDS));
            assertFalse(replacement.await(100, TimeUnit.MILLISECONDS));
        } finally { release.countDown(); }
        assertTrue(sameOwner.await(5, TimeUnit.SECONDS));
        assertTrue(replacement.await(5, TimeUnit.SECONDS));
        assertEquals(2, peak.get());
    }

    private static RenderScheduler.Work work(int priority, Runnable action) {
        return new RenderScheduler.Work(priority) { @Override public void run() { action.run(); } };
    }

    private static void await(CountDownLatch latch) {
        boolean interrupted = false;
        while (latch.getCount() != 0) {
            try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Timed out"); }
            catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
