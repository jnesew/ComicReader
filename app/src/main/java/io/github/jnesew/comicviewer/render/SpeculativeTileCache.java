package io.github.jnesew.comicviewer.render;

import android.graphics.Bitmap;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/** One byte ceiling shared by all renderers in a reader session. Entries are evicted before visible tiles. */
public final class SpeculativeTileCache {
    private final LinkedHashMap<Key, Bitmap> entries = new LinkedHashMap<>(16, .75f, true);
    private final long heapBytes;
    private final LongSupplier availableHeapBytes;
    private long limitBytes;
    private long usedBytes;
    private final Semaphore decodeSlot = new Semaphore(1);
    private final AtomicInteger visibleWaiting = new AtomicInteger();

    public SpeculativeTileCache(long heapBytes, String level) {
        this(heapBytes, level, () -> {
            Runtime runtime = Runtime.getRuntime();
            return runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory());
        });
    }

    public SpeculativeTileCache(long heapBytes, String level, LongSupplier availableHeapBytes) {
        this.heapBytes = heapBytes;
        this.availableHeapBytes = availableHeapBytes;
        setLevel(level);
    }

    public synchronized void setLevel(String level) {
        limitBytes = BufferingPolicy.speculativeBytes(level, heapBytes);
        evictToLimit();
    }

    public synchronized boolean enabled() { return limitBytes > 0L; }
    public synchronized long usedBytes() { return usedBytes; }
    public synchronized long limitBytes() { return limitBytes; }

    public void visibleQueued() { visibleWaiting.incrementAndGet(); }
    public void visibleFinished() { visibleWaiting.decrementAndGet(); }
    public boolean beginSpeculativeDecode() {
        if (!enabled() || !hasHeapRoom() || visibleWaiting.get() != 0 ||
                !decodeSlot.tryAcquire()) return false;
        if (visibleWaiting.get() == 0 && hasHeapRoom()) return true;
        decodeSlot.release();
        return false;
    }
    public void endSpeculativeDecode() { decodeSlot.release(); }

    public synchronized boolean contains(Object owner, String key) {
        return entries.containsKey(new Key(owner, key));
    }

    public synchronized Bitmap take(Object owner, String key) {
        Bitmap bitmap = entries.remove(new Key(owner, key));
        if (bitmap != null) usedBytes -= bitmap.getAllocationByteCount();
        return bitmap;
    }

    public synchronized void put(Object owner, String key, Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled() || limitBytes == 0L) return;
        if (!hasHeapRoom()) {
            clear();
            return;
        }
        long size = bitmap.getAllocationByteCount();
        if (size > limitBytes) return;
        Key id = new Key(owner, key);
        Bitmap previous = entries.put(id, bitmap);
        if (previous != null) usedBytes -= previous.getAllocationByteCount();
        usedBytes += size;
        evictToLimit();
    }

    public synchronized void removeOwner(Object owner) {
        Iterator<Map.Entry<Key, Bitmap>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Key, Bitmap> entry = iterator.next();
            if (entry.getKey().owner == owner) {
                usedBytes -= entry.getValue().getAllocationByteCount();
                iterator.remove();
            }
        }
    }

    public synchronized void clear() {
        entries.clear();
        usedBytes = 0L;
    }

    private void evictToLimit() {
        Iterator<Map.Entry<Key, Bitmap>> iterator = entries.entrySet().iterator();
        while (usedBytes > limitBytes && iterator.hasNext()) {
            Map.Entry<Key, Bitmap> entry = iterator.next();
            usedBytes -= entry.getValue().getAllocationByteCount();
            iterator.remove();
        }
    }

    private boolean hasHeapRoom() {
        return availableHeapBytes.getAsLong() >=
                Math.max(16L * 1024L * 1024L, heapBytes / 10L);
    }

    private record Key(Object owner, String tile) { }
}
