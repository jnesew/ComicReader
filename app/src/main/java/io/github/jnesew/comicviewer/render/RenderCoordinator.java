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

/** Session-wide pixel accounting, tile retention, optional-work admission and scheduling. */
public final class RenderCoordinator {
    private final LinkedHashMap<Key, Bitmap> entries = new LinkedHashMap<>(16, .75f, true);
    private final LinkedHashMap<Key, Bitmap> visibleEntries = new LinkedHashMap<>(32, .75f, true);
    private final Map<Object, java.util.Set<TileKey>> visibleDemand = new LinkedHashMap<>();
    private long visibleBytes;
    private long decodingBytes;
    private final long heapBytes;
    private final BooleanSupplier memoryAvailable;
    private LongPredicate memoryAdmission;
    private final LongSupplier clock;
    private RenderTuning tuning = RenderTuning.READER;
    private RenderMetrics metrics;
    private long reservedBytes;
    private final Map<Object, Long> backingAllocations = new LinkedHashMap<>();
    private final Map<Object, Runnable> wakeups = new LinkedHashMap<>();
    private final Map<Object, Map<TileKey, Integer>> plans = new LinkedHashMap<>();
    private final java.util.Set<Key> history = new java.util.HashSet<>();
    private long limitBytes;
    private long usedBytes;
    private boolean foreground = true;
    private long pressureUntil;
    private long pressureGeneration;
    private boolean memoryRefused;
    private final Semaphore decodeSlot = new Semaphore(1);
    private final RenderScheduler scheduler = new RenderScheduler();
    private final AtomicInteger visibleWaiting = new AtomicInteger();

    public RenderCoordinator(Context context, long heapBytes, String level) {
        this(context, heapBytes, level, RenderTuning.READER, null);
    }

    public RenderCoordinator(Context context, long heapBytes, String level,
            RenderTuning tuning, RenderMetrics metrics) {
        this(heapBytes, level, new AndroidBufferingMemory(context, heapBytes), tuning, metrics);
    }

    private RenderCoordinator(long heapBytes, String level, AndroidBufferingMemory memory,
            RenderTuning tuning, RenderMetrics metrics) {
        this(heapBytes, level, memory, SystemClock::elapsedRealtime, tuning, metrics);
        memoryAdmission = memory::hasRoomFor;
    }

    /** Instrumentation seam; production uses the Android system/native monitor above. */
    public RenderCoordinator(long heapBytes, String level, LongSupplier availableHeapBytes) {
        this(heapBytes, level,
                () -> availableHeapBytes.getAsLong() >=
                        Math.max(16L * 1024L * 1024L, heapBytes / 10L),
                SystemClock::elapsedRealtime);
    }

    public RenderCoordinator(long heapBytes, String level,
            BooleanSupplier memoryAvailable, LongSupplier clock) {
        this.heapBytes = heapBytes;
        this.memoryAvailable = memoryAvailable;
        this.memoryAdmission = ignored -> memoryAvailable.getAsBoolean();
        this.clock = clock;
        setLevel(level);
    }

    /** Deterministic Android/JVM test seam; not the production pressure monitor. */
    public RenderCoordinator(long heapBytes, String level, BooleanSupplier memoryAvailable,
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
    public synchronized boolean isForeground() { return foreground; }
    synchronized long pressureGeneration() { return pressureGeneration; }
    public synchronized long usedBytes() { return usedBytes; }
    public synchronized long limitBytes() { return limitBytes; }
    RenderTuning tuning() { return tuning; }
    RenderMetrics metrics() { return metrics; }

    public long pixelLimitBytes() {
        return BufferingPolicy.sessionBytes(heapBytes) - BufferingPolicy.previewBytes(heapBytes);
    }
    long visibleLimitBytes() { return BufferingPolicy.visibleSessionBytes(heapBytes); }
    long backingLimitBytes() { return BufferingPolicy.backingBytesPerDocument(heapBytes); }

    /** A lightweight owner view; all pixels live in the same session LRU. */
    final class VisibleTiles {
        private final Object owner;
        private final int maxKb;
        VisibleTiles(Object owner, int maxKb) { this.owner = owner; this.maxKb = maxKb; }
        int maxSize() { return (int) Math.min(maxKb, visibleLimitBytes() / 1024L); }
        Bitmap get(TileKey tile) {
            synchronized (RenderCoordinator.this) { return visibleEntries.get(new Key(owner, tile)); }
        }
        void put(TileKey tile, Bitmap bitmap) { put(tile, bitmap, 0); }
        void put(TileKey tile, Bitmap bitmap, long reservation) {
            synchronized (RenderCoordinator.this) {
                if (reservation != 0) releaseDecode(reservation);
                Key key = new Key(owner, tile);
                Bitmap previous = visibleEntries.put(key, bitmap);
                if (previous != null) visibleBytes -= previous.getAllocationByteCount();
                visibleBytes += bitmap.getAllocationByteCount();
                trimVisible(owner, maxSize() * 1024L);
                recordMemory();
            }
        }
        Bitmap adoptOptional(TileKey tile) {
            Bitmap bitmap;
            synchronized (RenderCoordinator.this) {
                Key key = new Key(owner, tile);
                bitmap = entries.remove(key);
                boolean recent = history.remove(key);
                if (bitmap != null) {
                    usedBytes -= bitmap.getAllocationByteCount();
                    if (!recent && metrics != null) metrics.useful(bitmap.getAllocationByteCount());
                    put(tile, bitmap);
                }
            }
            if (bitmap != null && tuning.prioritized) workFinished();
            return bitmap;
        }
        Map<TileKey, Bitmap> snapshot() {
            synchronized (RenderCoordinator.this) {
                Map<TileKey, Bitmap> result = new LinkedHashMap<>();
                for (Map.Entry<Key, Bitmap> entry : visibleEntries.entrySet()) {
                    if (entry.getKey().owner == owner) result.put(entry.getKey().tile, entry.getValue());
                }
                return result;
            }
        }
        void evictAll() {
            synchronized (RenderCoordinator.this) {
                java.util.Iterator<Map.Entry<Key, Bitmap>> iterator = visibleEntries.entrySet().iterator();
                while (iterator.hasNext()) {
                    Map.Entry<Key, Bitmap> entry = iterator.next();
                    if (entry.getKey().owner == owner) {
                        visibleBytes -= entry.getValue().getAllocationByteCount(); iterator.remove();
                    }
                }
                recordMemory();
            }
        }
    }

    VisibleTiles visibleTiles(Object owner, int maxKb) { return new VisibleTiles(owner, maxKb); }

    synchronized void visibleDemand(Object owner, java.util.Set<TileKey> keys) {
        if (keys.isEmpty()) visibleDemand.remove(owner);
        else visibleDemand.put(owner, keys);
    }

    private boolean visible(Key key) {
        java.util.Set<TileKey> demand = visibleDemand.get(key.owner);
        return demand != null && demand.contains(key.tile);
    }

    private long ownerVisibleBytes(Object owner) {
        long bytes = 0;
        for (Map.Entry<Key, Bitmap> entry : visibleEntries.entrySet()) {
            if (entry.getKey().owner == owner) bytes += entry.getValue().getAllocationByteCount();
        }
        return bytes;
    }

    private void trimVisible(Object owner, long ownerLimit) {
        while (visibleBytes > visibleLimitBytes() || ownerVisibleBytes(owner) > ownerLimit ||
                pixelBytes() > pixelLimitBytes()) {
            Key victim = null;
            boolean ownerOver = ownerVisibleBytes(owner) > ownerLimit;
            for (Key key : visibleEntries.keySet()) {
                if ((!ownerOver || key.owner == owner) && !visible(key)) { victim = key; break; }
            }
            if (victim == null) {
                for (Key key : visibleEntries.keySet()) {
                    if (!ownerOver || key.owner == owner) { victim = key; break; }
                }
            }
            if (victim == null) break;
            Bitmap bitmap = visibleEntries.remove(victim);
            visibleBytes -= bitmap.getAllocationByteCount();
            // Already allocated pixels may survive a backward turn within the optional ceiling.
            if (enabled() && bitmap.getAllocationByteCount() <= limitBytes - usedBytes - reservedBytes &&
                    bitmap.getAllocationByteCount() <= pixelLimitBytes() - pixelBytes()) {
                Bitmap old = entries.put(victim, bitmap);
                if (old != null) usedBytes -= old.getAllocationByteCount();
                usedBytes += bitmap.getAllocationByteCount(); history.add(victim);
            }
        }
    }

    private long backingBytes() {
        long bytes = 0;
        for (long bytesHeld : backingAllocations.values()) bytes += bytesHeld;
        return bytes;
    }

    public synchronized long pixelBytes() {
        return visibleBytes + usedBytes + reservedBytes + decodingBytes + backingBytes();
    }

    private boolean makeRoom(long additional) {
        while (additional > pixelLimitBytes() - pixelBytes()) {
            if (!entries.isEmpty()) {
                Key victim = entries.keySet().iterator().next();
                Bitmap bitmap = entries.remove(victim); history.remove(victim);
                usedBytes -= bitmap.getAllocationByteCount();
                if (metrics != null) metrics.evicted(bitmap.getAllocationByteCount());
                continue;
            }
            Key victim = null;
            for (Key key : visibleEntries.keySet()) if (!visible(key)) { victim = key; break; }
            if (victim == null) return false;
            visibleBytes -= visibleEntries.remove(victim).getAllocationByteCount();
        }
        return true;
    }

    synchronized boolean reserveDecode(long bytes) {
        if (bytes <= 0 || !makeRoom(bytes)) return false;
        decodingBytes += bytes; recordMemory(); return true;
    }
    synchronized void releaseDecode(long bytes) {
        decodingBytes -= bytes;
        if (decodingBytes < 0) throw new IllegalStateException("Unbalanced decode reservation");
        recordMemory();
    }

    synchronized boolean claimBacking(Object owner, long bytes) {
        Long held = backingAllocations.get(owner);
        if (held == null || bytes < 0) return false;
        long growth = Math.max(0, bytes - held);
        if (!makeRoom(growth)) return false;
        backingAllocations.put(owner, bytes); recordMemory(); return true;
    }

    public synchronized void registerOwner(Object owner) {
        backingAllocations.put(owner, 0L);
    }
    public synchronized void onWorkAvailable(Object owner, Runnable wakeup) { wakeups.put(owner, wakeup); }
    public void workFinished() {
        workAvailableExcept(null);
    }
    private void workAvailableExcept(Object excluded) {
        java.util.List<Runnable> callbacks;
        synchronized (this) {
            callbacks = new java.util.ArrayList<>();
            for (Map.Entry<Object, Runnable> item : wakeups.entrySet()) {
                if (item.getKey() != excluded) callbacks.add(item.getValue());
            }
        }
        for (Runnable callback : callbacks) callback.run();
    }
    public synchronized void unregisterOwner(Object owner) {
        backingAllocations.remove(owner); wakeups.remove(owner); plans.remove(owner); visibleDemand.remove(owner);
    }
    /** Lower rank means nearer use. All owners compete under the same byte ceiling. */
    void plan(Object owner, Map<TileKey, Integer> candidates) {
        boolean changed;
        synchronized (this) {
            Map<TileKey, Integer> old = plans.put(owner, new LinkedHashMap<>(candidates));
            changed = !candidates.equals(old);
        }
        // A changed rank can unblock another owner even when this owner queues no work.
        if (changed) workAvailableExcept(owner);
    }
    void cancelPlan(Object owner) {
        boolean changed;
        synchronized (this) { changed = plans.remove(owner) != null; }
        if (changed) workAvailableExcept(owner);
    }
    synchronized boolean planned(Object owner, TileKey key) {
        Map<TileKey, Integer> plan = plans.get(owner);
        return plan != null && plan.containsKey(key);
    }
    synchronized int priority(Object owner, TileKey key) { return rank(new Key(owner, key)); }
    private int rank(Key key) {
        Map<TileKey, Integer> plan = plans.get(key.owner);
        return plan == null ? Integer.MAX_VALUE : plan.getOrDefault(key.tile, Integer.MAX_VALUE);
    }
    /** Replace only content farther away than this request; never churn equally useful tiles. */
    synchronized boolean reserve(Object owner, TileKey key, long bytes) {
        int priority = rank(new Key(owner, key));
        if (!enabled() || priority == Integer.MAX_VALUE || bytes <= 0 || bytes > limitBytes) return false;
        while (bytes > limitBytes - usedBytes - reservedBytes) {
            Key victim = null;
            int worst = priority;
            for (Key candidate : entries.keySet()) {
                int candidateRank = rank(candidate);
                if (candidateRank > worst) { victim = candidate; worst = candidateRank; }
            }
            if (victim == null) break;
            Bitmap removed = entries.remove(victim);
            usedBytes -= removed.getAllocationByteCount();
            boolean wasHistory = history.remove(victim);
            if (metrics != null) {
                metrics.priorityEvicted(removed.getAllocationByteCount());
                if (!wasHistory) metrics.evicted(removed.getAllocationByteCount());
            }
        }
        boolean accepted = reserve(bytes);
        if (!accepted && priority == 0 && metrics != null) metrics.nearDenied();
        return accepted;
    }
    /** Transfer a reservation to resident output atomically; stale plans cannot publish. */
    synchronized boolean publishReserved(Object owner, TileKey key, Bitmap bitmap, long bytes) {
        release(bytes);
        if (!planned(owner, key) || bitmap == null || bitmap.isRecycled() ||
                bitmap.getAllocationByteCount() > limitBytes - usedBytes - reservedBytes) return false;
        return offer(owner, key, bitmap, false);
    }
    private long futureGrowth() {
        return tuning.cacheMiB == 0 ? 0 : Math.max(0, pixelLimitBytes() - pixelBytes());
    }
    /** Reservations bound resident plus queued output. The native guard also covers future growth. */
    public synchronized boolean reserve(long bytes) {
        if (bytes <= 0 || bytes > limitBytes - usedBytes - reservedBytes) {
            if (metrics != null) metrics.capacityDenied();
            return false;
        }
        if (!makeRoom(bytes) || !hasMemoryRoom(bytes)) {
            if (metrics != null) metrics.memoryDenied();
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
    public synchronized long committedBytes() { return usedBytes + reservedBytes; }
    public synchronized long futureGrowthBytes() { return futureGrowth(); }
    public synchronized long freeBytes() { return Math.max(0, limitBytes - usedBytes - reservedBytes); }
    private void recordMemory() {
        if (metrics == null) return;
        long visible = visibleBytes, backing = 0;
        for (long bytesHeld : backingAllocations.values()) backing += bytesHeld;
        metrics.memory(visible, backing, usedBytes, reservedBytes, decodingBytes);
    }

    public synchronized void setForeground(boolean foreground) {
        this.foreground = foreground;
        if (!foreground) clear();
    }

    public synchronized void onMemoryPressure() {
        pressureGeneration++;
        pressureUntil = clock.getAsLong() + 30_000L;
        clear();
    }

    public void visibleQueued() { visibleWaiting.incrementAndGet(); }
    public void visibleFinished() { visibleWaiting.decrementAndGet(); }
    public boolean beginSpeculativeDecode() {
        if (!hasMemoryRoom() || visibleWaiting.get() != 0 ||
                !decodeSlot.tryAcquire()) return false;
        boolean admitted = false;
        try {
            admitted = visibleWaiting.get() == 0 && hasMemoryRoom();
            return admitted;
        } finally {
            // A pressure-sample OOM must not strand the session's only permit.
            // The caller owns it only once admission has returned successfully.
            if (!admitted) decodeSlot.release();
        }
    }
    public void endSpeculativeDecode() { decodeSlot.release(); }

    RenderScheduler scheduler() { return scheduler; }

    public synchronized boolean contains(Object owner, TileKey key) {
        return entries.containsKey(new Key(owner, key));
    }

    public Bitmap take(Object owner, TileKey key) {
        Bitmap bitmap;
        synchronized (this) {
            Key id = new Key(owner, key);
            bitmap = entries.remove(id);
            boolean wasHistory = history.remove(id);
            if (bitmap != null) usedBytes -= bitmap.getAllocationByteCount();
            if (bitmap != null && !wasHistory && metrics != null) metrics.useful(bitmap.getAllocationByteCount());
        }
        if (bitmap != null && tuning.prioritized) workFinished();
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
        if (size > limitBytes || !makeRoom(size)) return false;
        Key id = new Key(owner, key);
        if (tuning.prioritized && recent) {
            Bitmap old = entries.get(id);
            long replaced = old == null ? 0 : old.getAllocationByteCount();
            // History is opportunistic: a displaced visible tile cannot evict the next frame.
            if (size - replaced > limitBytes - usedBytes - reservedBytes) return false;
        }
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
        plans.remove(owner);
        visibleDemand.remove(owner);
        visibleTiles(owner, Integer.MAX_VALUE).evictAll();
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
        clearEntries();
        plans.clear();
    }
    private void clearEntries() {
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
        long started = metrics == null ? 0 : System.nanoTime();
        try {
            if (tuning.boundedAdmission()
                    ? memoryAdmission.test(additional + reservedBytes + futureGrowth())
                    : memoryAvailable.getAsBoolean()) {
                memoryRefused = false;
                return true;
            }
        } catch (RuntimeException ignored) {
            // A failed sample cannot authorize speculative allocations.
        } finally {
            if (metrics != null) metrics.guard(System.nanoTime() - started);
        }
        if (metrics != null) metrics.guardRefused();
        if (!memoryRefused) pressureGeneration++;
        memoryRefused = true;
        // Keep the desired plan on a refused sample: restoring it must not create
        // a plan-change/refill loop while the same memory refusal persists.
        if (tuning.prioritized) clearEntries(); else clear();
        return false;
    }

    private record Key(Object owner, TileKey tile) { }
}
