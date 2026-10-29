package io.github.jnesew.comicviewer.render;

/** Reader policy and explicit benchmark controls. BASELINE preserves the live-tested old policy. */
public final class RenderTuning {
    public static final RenderTuning BASELINE = new RenderTuning(false, false, false, false, 0);
    public static final RenderTuning READER = reader(0);
    public final boolean byteLookahead;
    public final boolean refill;
    public final boolean predictedFrame;
    public final boolean historyFallback;
    public final int cacheMiB;
    private final boolean admissionOnly;
    public final boolean prioritized;

    public RenderTuning(boolean byteLookahead, boolean refill, boolean predictedFrame,
            boolean historyFallback, int cacheMiB) {
        this(byteLookahead, refill, predictedFrame, historyFallback, cacheMiB, false);
    }

    /** Independent diagnostic control for admission without extending range or enabling refill. */
    public RenderTuning(boolean byteLookahead, boolean refill, boolean predictedFrame,
            boolean historyFallback, int cacheMiB, boolean admissionOnly) {
        this(byteLookahead, refill, predictedFrame, historyFallback, cacheMiB, admissionOnly, false);
    }

    private RenderTuning(boolean byteLookahead, boolean refill, boolean predictedFrame,
            boolean historyFallback, int cacheMiB, boolean admissionOnly, boolean prioritized) {
        this.byteLookahead = byteLookahead;
        this.refill = refill;
        this.predictedFrame = predictedFrame;
        this.historyFallback = historyFallback;
        this.cacheMiB = Math.max(0, Math.min(128, cacheMiB));
        this.admissionOnly = admissionOnly;
        this.prioritized = prioritized;
    }

    /** Larger capacity remains an explicit comparison choice until live validation. */
    public static RenderTuning reader(int requestedMiB) {
        return new RenderTuning(false, true, true, false,
                Math.max(0, Math.min(64, requestedMiB)), true, true);
    }

    public boolean boundedAdmission() { return admissionOnly || byteLookahead || refill || cacheMiB > 0; }

    /** Separate from the requested capacity: Java/native/system checks still gate work. */
    public long cacheBytes(long heap, String level) {
        if (BufferingPolicy.STANDARD.equals(BufferingPolicy.normalize(level))) return 0;
        if (prioritized && cacheMiB > 0) {
            if (!BufferingPolicy.HIGH.equals(BufferingPolicy.normalize(level))) {
                return BufferingPolicy.speculativeBytes(level, heap);
            }
            return Math.min(cacheMiB * 1024L * 1024L, Math.max(0L, heap / 4L));
        }
        return cacheMiB == 0 ? BufferingPolicy.speculativeBytes(level, heap)
                : Math.min(cacheMiB * 1024L * 1024L, Math.max(0L, heap / 2L));
    }
}
