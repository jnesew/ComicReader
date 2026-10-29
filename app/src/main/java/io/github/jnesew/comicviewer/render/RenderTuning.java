package io.github.jnesew.comicviewer.render;

/** Explicit opt-in experiments. Normal reader constructors retain the baseline. */
public final class RenderTuning {
    public static final RenderTuning BASELINE = new RenderTuning(false, false, false, false, 0);
    public final boolean byteLookahead;
    public final boolean refill;
    public final boolean predictedFrame;
    public final boolean historyFallback;
    public final int cacheMiB;
    private final boolean admissionOnly;

    public RenderTuning(boolean byteLookahead, boolean refill, boolean predictedFrame,
            boolean historyFallback, int cacheMiB) {
        this(byteLookahead, refill, predictedFrame, historyFallback, cacheMiB, false);
    }

    /** Independent diagnostic control for admission without extending range or enabling refill. */
    public RenderTuning(boolean byteLookahead, boolean refill, boolean predictedFrame,
            boolean historyFallback, int cacheMiB, boolean admissionOnly) {
        this.byteLookahead = byteLookahead;
        this.refill = refill;
        this.predictedFrame = predictedFrame;
        this.historyFallback = historyFallback;
        this.cacheMiB = Math.max(0, Math.min(128, cacheMiB));
        this.admissionOnly = admissionOnly;
    }

    public boolean boundedAdmission() { return admissionOnly || byteLookahead || refill || cacheMiB > 0; }

    /** Separate from the requested capacity: Java/native/system checks still gate work. */
    public long cacheBytes(long heap, String level) {
        if (BufferingPolicy.STANDARD.equals(BufferingPolicy.normalize(level))) return 0;
        return cacheMiB == 0 ? BufferingPolicy.speculativeBytes(level, heap)
                : Math.min(cacheMiB * 1024L * 1024L, Math.max(0L, heap / 2L));
    }
}
