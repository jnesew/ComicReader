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
import java.util.function.LongPredicate;

/** One byte ceiling shared by all renderers in a reader session. Entries are evicted before visible tiles. */
public final class SpeculativeTileCache {
    private final LinkedHashMap<Key, Bitmap> entries = new LinkedHashMap<>(16, .75f, true);
    private final long heapBytes;
    private final BooleanSupplier memoryAvailable;
    private LongPredicate memoryAdmission;
    private final LongSupplier clock;
    private RenderTuning tuning = RenderTuning.BASELINE;
    private RenderMetrics metrics;
    private long reservedBytes;
    private final Map<Object, long[]> owners = new LinkedHashMap<>();
    private final Map<Object, Runnable> wakeups = new LinkedHashMap<>();
    private final java.util.Set<Key> history = new java.util.HashSet<>();
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

    public SpeculativeTileCache(Context context, long heapBytes, String level,
            RenderTuning tuning, RenderMetrics metrics) {
        this(context, heapBytes, level);
        this.tuning = tuning;
        this.metrics = metrics;
        AndroidBufferingMemory memory = new AndroidBufferingMemory(context, heapBytes);
        memoryAdmission = memory::hasRoomFor;
        setLevel(level);
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
        this.memoryAdmission = ignored -> memoryAvailable.getAsBoolean();
        this.clock = clock;
        setLevel(level);
    }

    /** Deterministic Android/JVM test seam; not the production pressure monitor. */
    public SpeculativeTileCache(long heapBytes, String level, BooleanSupplier memoryAvailable,
            LongSupplier clock, RenderTuning tuning, RenderMetrics metrics) {
        this(heapBytes, level, memoryAvailable, clock);
        this.tuning = tuning; this.metrics = metrics; setLevel(level);
    }

    public synchronized void setLevel(String level) {
        limitBytes = tuning.cacheBytes(heapBytes, level);
        evictToLimit();
    }

    public synchronized boolean enabled() {
        return limitBytes > 0L && foreground && clock.getAsLong() >= pressureUntil;
    }
    public synchronized long usedBytes() { return usedBytes; }
    public synchronized long limitBytes() { return limitBytes; }
    RenderTuning tuning() { return tuning; }
    RenderMetrics metrics() { return metrics; }

    public synchronized void registerOwner(Object owner, long visibleBudget, long backingBudget) {
        owners.put(owner, new long[]{visibleBudget, backingBudget, 0, 0});
    }
    public synchronized void onWorkAvailable(Object owner, Runnable wakeup) { wakeups.put(owner, wakeup); }
    public void workFinished() {
        java.util.List<Runnable> callbacks;
        synchronized (this) { callbacks = new java.util.ArrayList<>(wakeups.values()); }
        for (Runnable callback : callbacks) callback.run();
    }
    public synchronized void residentBytes(Object owner, long visible, long backing) {
        long[] state = owners.get(owner);
        if (state != null) { state[2] = visible; state[3] = backing; }
        recordMemory();
    }
    public synchronized void unregisterOwner(Object owner) { owners.remove(owner); wakeups.remove(owner); }
    private long futureGrowth() {
        if (tuning.cacheMiB == 0) return 0L;
        long total = 0L;
        for (long[] state : owners.values()) {
            total += Math.max(0, state[0] - state[2]) + Math.max(0, state[1] - state[3]);
        }
        return total;
    }
    /** Reservations bound resident plus queued output. The native guard also covers future growth. */
    public synchronized boolean reserve(long bytes) {
        if (bytes <= 0 || bytes > limitBytes - usedBytes - reservedBytes ||
                !hasMemoryRoom(bytes)) {
            if (metrics != null) metrics.denied();
            return false;
        }
        reservedBytes += bytes;
        recordMemory();
        return true;
    }
    public synchronized void release(long bytes) {
        reservedBytes = Math.max(0L, reservedBytes - bytes);
    }
    public synchronized long reservedBytes() { return reservedBytes; }
    public synchronized long freeBytes() { return Math.max(0, limitBytes - usedBytes - reservedBytes); }
    private void recordMemory() {
        if (metrics == null) return;
        long visible = 0, backing = 0;
        for (long[] state : owners.values()) { visible += state[2]; backing += state[3]; }
        metrics.memory(visible, backing, usedBytes, reservedBytes);
    }

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
        Key id = new Key(owner, key);
        Bitmap bitmap = entries.remove(id);
        boolean wasHistory = history.remove(id);
        if (bitmap != null) usedBytes -= bitmap.getAllocationByteCount();
        if (bitmap != null && !wasHistory && metrics != null) metrics.useful(bitmap.getAllocationByteCount());
        return bitmap;
    }

    synchronized Bitmap borrow(Object owner, TileKey key) { return entries.get(new Key(owner, key)); }

    public synchronized void put(Object owner, TileKey key, Bitmap bitmap) {
        offer(owner, key, bitmap, false);
    }
    public synchronized void putHistory(Object owner, TileKey key, Bitmap bitmap) {
        offer(owner, key, bitmap, true);
    }
    public synchronized boolean offer(Object owner, TileKey key, Bitmap bitmap, boolean recent) {
        if (bitmap == null || bitmap.isRecycled() || !hasMemoryRoom()) return false;
        long size = bitmap.getAllocationByteCount();
        if (size > limitBytes) return false;
        Key id = new Key(owner, key);
        Bitmap previous = entries.put(id, bitmap);
        if (previous != null) usedBytes -= previous.getAllocationByteCount();
        if (recent) history.add(id); else history.remove(id);
        usedBytes += size;
        evictToLimit();
        recordMemory();
        return entries.containsKey(id);
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
                history.remove(entry.getKey());
                iterator.remove();
            }
        }
    }

    public synchronized void clear() {
        entries.clear();
        history.clear();
        usedBytes = 0L;
    }

    private void evictToLimit() {
        while (usedBytes > Math.max(0L, limitBytes - reservedBytes) && !entries.isEmpty()) {
            Key victim = entries.keySet().iterator().next();
            if (tuning.historyFallback && history.contains(victim)) {
                long historyBytes = 0;
                for (Key id : history) {
                    Bitmap bitmap = entries.get(id);
                    if (bitmap != null) historyBytes += bitmap.getAllocationByteCount();
                }
                if (historyBytes <= limitBytes / 4) {
                    for (Key id : entries.keySet()) if (!history.contains(id)) { victim = id; break; }
                }
            }
            Bitmap bitmap = entries.remove(victim);
            boolean wasHistory = history.remove(victim);
            usedBytes -= bitmap.getAllocationByteCount();
            if (!wasHistory && metrics != null) metrics.evicted(bitmap.getAllocationByteCount());
        }
    }

    private synchronized boolean hasMemoryRoom() {
        return hasMemoryRoom(0L);
    }
    private synchronized boolean hasMemoryRoom(long additional) {
        if (!enabled()) return false;
        try {
            if (tuning.boundedAdmission()
                    ? memoryAdmission.test(additional + reservedBytes + futureGrowth())
                    : memoryAvailable.getAsBoolean()) return true;
        } catch (RuntimeException ignored) {
            // A failed sample cannot authorize speculative allocations.
        }
        clear();
        return false;
    }

    private record Key(Object owner, TileKey tile) { }
}
