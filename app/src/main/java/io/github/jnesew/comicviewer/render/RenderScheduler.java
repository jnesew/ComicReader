package io.github.jnesew.comicviewer.render;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** One session queue; a document's native work and cleanup always execute serially. */
final class RenderScheduler {
    private static final AtomicLong sequence = new AtomicLong();
    private final PriorityQueue<Entry> queued = new PriorityQueue<>();
    private final Map<Object, Thread> running = new IdentityHashMap<>();
    private final int concurrency;
    private final ThreadPoolExecutor workers;

    RenderScheduler() { this(3); }

    RenderScheduler(int concurrency) {
        this.concurrency = concurrency;
        workers = new ThreadPoolExecutor(concurrency, concurrency, 5, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), runnable -> {
                    Thread thread = new Thread(runnable, "comic-render-worker");
                    thread.setPriority(Thread.NORM_PRIORITY - 1);
                    thread.setDaemon(true);
                    return thread;
                });
        workers.allowCoreThreadTimeOut(true);
    }

    abstract static class Work implements Runnable {
        final int priority;
        final long order = sequence.incrementAndGet();
        Work(int priority) { this.priority = priority; }
    }

    synchronized void execute(Object owner, Work work) {
        queued.add(new Entry(owner, work));
        dispatch();
    }

    synchronized boolean remove(Work work) {
        return queued.removeIf(entry -> entry.work == work);
    }

    synchronized boolean isWorker(Object owner) {
        return running.get(owner) == Thread.currentThread();
    }

    synchronized void interrupt(Object owner) {
        Thread thread = running.get(owner);
        if (thread != null) thread.interrupt();
    }

    private void dispatch() {
        while (running.size() < concurrency) {
            Entry next = null;
            for (Entry entry : queued) {
                if (!running.containsKey(entry.owner) &&
                        (next == null || entry.compareTo(next) < 0)) next = entry;
            }
            if (next == null) return;
            queued.remove(next);
            running.put(next.owner, null);
            Entry selected = next;
            workers.execute(() -> {
                synchronized (this) { running.put(selected.owner, Thread.currentThread()); }
                try { selected.work.run(); }
                finally {
                    // A retiring document must not interrupt its successor on this thread.
                    Thread.interrupted();
                    synchronized (this) {
                        running.remove(selected.owner);
                        dispatch();
                    }
                }
            });
        }
    }

    private record Entry(Object owner, Work work) implements Comparable<Entry> {
        @Override public int compareTo(Entry other) {
            int priority = Integer.compare(work.priority, other.work.priority);
            return priority != 0 ? priority : Long.compare(work.order, other.work.order);
        }
    }
}
