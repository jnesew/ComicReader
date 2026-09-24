package io.github.jnesew.comicviewer.render;

/** Reject completion from an old viewport or a closed renderer. */
public final class PrefetchGeneration {
    private long generation;
    private long viewport = Long.MIN_VALUE;
    private boolean closed;

    public synchronized boolean begin(long signature) {
        if (closed || viewport == signature) return false;
        generation++;
        viewport = signature;
        return true;
    }

    public synchronized long current() { return generation; }

    public synchronized boolean isCurrent(long candidate) {
        return !closed && generation == candidate;
    }

    public synchronized boolean publishIfCurrent(long candidate, Runnable publish) {
        if (!isCurrent(candidate)) return false;
        publish.run();
        return true;
    }

    public synchronized void cancel() {
        generation++;
        viewport = Long.MIN_VALUE;
    }

    public synchronized void close() {
        cancel();
        closed = true;
    }
}
