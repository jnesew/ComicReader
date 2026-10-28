package io.github.jnesew.comicviewer.render;

/** Optional bounded counters. No logging, identifiers, or per-event allocations in hot paths. */
public final class RenderMetrics {
    private long draws, decoded, speculativeDecoded, stale, failures, admissionsDenied;
    private long decodeNanos, queueNanos, extractedNanos, usefulBytes, evictedBytes;
    private long peakVisible, peakBacking, peakShared, peakPending;
    private double frameArea, frameMissing, lastMissing, missingAreaNanos;
    private long lastFrameTime;
    private boolean collecting;

    public synchronized void start(long now) { collecting = true; lastFrameTime = now; lastMissing = 1; }
    public synchronized long draws() { return draws; }
    public synchronized void beginFrame(long now) {
        if (!collecting) return;
        integrate(now);
        frameArea = frameMissing = 0;
        draws++;
    }
    public synchronized void area(double area, boolean missing) {
        if (!collecting) return;
        frameArea += area;
        if (missing) frameMissing += area;
    }
    public synchronized void endFrame() {
        if (collecting) lastMissing = frameArea == 0 ? 0 : Math.min(1, frameMissing / frameArea);
    }
    private void integrate(long now) {
        if (lastFrameTime > 0) missingAreaNanos += Math.max(0, now - lastFrameTime) * lastMissing;
        lastFrameTime = now;
    }
    public synchronized double missingFraction() { return lastMissing; }
    public synchronized void decode(boolean speculative, long duration, long queued) {
        if (!collecting) return;
        decoded++; if (speculative) speculativeDecoded++;
        decodeNanos += duration; queueNanos += queued;
    }
    public synchronized void extracted(long nanos) { if (collecting) extractedNanos += nanos; }
    public synchronized void stale() { if (collecting) stale++; }
    public synchronized void failure() { if (collecting) failures++; }
    public synchronized void denied() { if (collecting) admissionsDenied++; }
    public synchronized void useful(long bytes) { if (collecting) usefulBytes += bytes; }
    public synchronized void evicted(long bytes) { if (collecting) evictedBytes += bytes; }
    public synchronized void memory(long visible, long backing, long shared, long pending) {
        if (!collecting) return;
        peakVisible = Math.max(peakVisible, visible); peakBacking = Math.max(peakBacking, backing);
        peakShared = Math.max(peakShared, shared); peakPending = Math.max(peakPending, pending);
    }
    /** Fixed schema, usable without Android JSON in policy tests. */
    public synchronized java.util.Map<String, Number> finish(long now) {
        if (collecting) integrate(now);
        collecting = false;
        java.util.Map<String, Number> result = new java.util.LinkedHashMap<>();
        result.put("draws", draws); result.put("decoded_tiles", decoded);
        result.put("speculative_decoded_tiles", speculativeDecoded);
        result.put("decode_ms", decodeNanos / 1e6); result.put("queue_ms", queueNanos / 1e6);
        result.put("extract_ms", extractedNanos / 1e6);
        result.put("exact_missing_area_ms", missingAreaNanos / 1e6);
        result.put("stale_results", stale); result.put("render_failures", failures);
        result.put("admission_denials", admissionsDenied);
        result.put("prefetched_bytes_used", usefulBytes); result.put("unused_prefetch_evicted_bytes", evictedBytes);
        result.put("peak_visible_bytes", peakVisible); result.put("peak_backing_bytes", peakBacking);
        result.put("peak_shared_bytes", peakShared); result.put("peak_pending_bytes", peakPending);
        return result;
    }
}
