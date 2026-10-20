package io.github.jnesew.comicviewer.render;

import android.graphics.Bitmap;
import android.content.Context;
import android.os.SystemClock;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.function.BooleanSupplier;

/** One byte ceiling shared by all renderers in a reader session. Entries are evicted before visible tiles. */
public final class SpeculativeTileCache {
    private final LinkedHashMap<Key, Bitmap> entries = new LinkedHashMap<>(16, .75f, true);
    private final long heapBytes;
    private final BooleanSupplier memoryAvailable;
    private final LongSupplier clock;
    private long limitBytes;
    private long usedBytes;
    private boolean foreground = true;
    private long pressureUntil;
    private final Semaphore decodeSlot = new Semaphore(1);
    private final AtomicInteger visibleWaiting = new AtomicInteger();

    public SpeculativeTileCache(Context context, long heapBytes, String level) {
        this(heapBytes, level, new AndroidBufferingMemory(context, heapBytes),
                SystemClock::elapsedRealtime);
    }

    /** Instrumentation seam; production uses the Android system/native monitor above. */
    public SpeculativeTileCache(long heapBytes, String level, LongSupplier availableHeapBytes) {
        this(heapBytes, level,
                () -> availableHeapBytes.getAsLong() >=
                        Math.max(16L * 1024L * 1024L, heapBytes / 10L),
                SystemClock::elapsedRealtime);
    }

    public SpeculativeTileCache(long heapBytes, String level,
            BooleanSupplier memoryAvailable, LongSupplier clock) {
        this.heapBytes = heapBytes;
        this.memoryAvailable = memoryAvailable;
        this.clock = clock;
        setLevel(level);
    }

    public synchronized void setLevel(String level) {
        limitBytes = BufferingPolicy.speculativeBytes(level, heapBytes);
        evictToLimit();
    }

    public synchronized boolean enabled() {
        return limitBytes > 0L && foreground && clock.getAsLong() >= pressureUntil;
    }
    public synchronized long usedBytes() { return usedBytes; }
    public synchronized long limitBytes() { return limitBytes; }

    public synchronized void setForeground(boolean foreground) {
        this.foreground = foreground;
        if (!foreground) clear();
    }

    public synchronized void onMemoryPressure() {
        pressureUntil = clock.getAsLong() + 30_000L;
        clear();
    }

    public void visibleQueued() { visibleWaiting.incrementAndGet(); }
    public void visibleFinished() { visibleWaiting.decrementAndGet(); }
    public boolean beginSpeculativeDecode() {
        if (!hasMemoryRoom() || visibleWaiting.get() != 0 ||
                !decodeSlot.tryAcquire()) return false;
        if (visibleWaiting.get() == 0 && hasMemoryRoom()) return true;
        decodeSlot.release();
        return false;
    }
    public void endSpeculativeDecode() { decodeSlot.release(); }

    public synchronized boolean contains(Object owner, TileKey key) {
        return entries.containsKey(new Key(owner, key));
    }

    public synchronized Bitmap take(Object owner, TileKey key) {
        Bitmap bitmap = entries.remove(new Key(owner, key));
        if (bitmap != null) usedBytes -= bitmap.getAllocationByteCount();
        return bitmap;
    }

    public synchronized void put(Object owner, TileKey key, Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled() || !hasMemoryRoom()) return;
        long size = bitmap.getAllocationByteCount();
        if (size > limitBytes) return;
        Key id = new Key(owner, key);
        Bitmap previous = entries.put(id, bitmap);
        if (previous != null) usedBytes -= previous.getAllocationByteCount();
        usedBytes += size;
        evictToLimit();
    }

    /** Borrowed references for drawing only; fallback use does not enlarge the visible cache. */
    synchronized Map<TileKey, Bitmap> snapshot(Object owner) {
        Map<TileKey, Bitmap> result = new LinkedHashMap<>();
        for (Map.Entry<Key, Bitmap> entry : entries.entrySet()) {
            if (entry.getKey().owner == owner) {
                result.put(entry.getKey().tile, entry.getValue());
            }
        }
        return result;
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

    private synchronized boolean hasMemoryRoom() {
        if (!enabled()) return false;
        try {
            if (memoryAvailable.getAsBoolean()) return true;
        } catch (RuntimeException ignored) {
            // A failed sample cannot authorize speculative allocations.
        }
        clear();
        return false;
    }

    private record Key(Object owner, TileKey tile) { }
}
