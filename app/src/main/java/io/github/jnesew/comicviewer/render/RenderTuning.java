package io.github.jnesew.comicviewer.render;

import io.github.jnesew.comicviewer.BuildConfig;

/** Reader policy and explicit benchmark controls. BASELINE preserves the live-tested old policy. */
public final class RenderTuning {
    public static final RenderTuning BASELINE = new RenderTuning(false, false, false, false, 0);
    public static final RenderTuning READER = reader(0);
    /** Only the optimized, separately identified reader trial overrides the frozen reader policy. */
    public static final RenderTuning DEFAULT_READER = BuildConfig.READER_TALL_BANDS == 2 ? scrolling(1, 2) : READER;
    public final boolean byteLookahead;
    public final boolean refill;
    public final boolean predictedFrame;
    public final boolean historyFallback;
    public final int cacheMiB;
    private final boolean admissionOnly;
    public final boolean prioritized;
    public final int speculativePipeline;
    public final int tallLookaheadBands;

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
        this(byteLookahead, refill, predictedFrame, historyFallback, cacheMiB, admissionOnly,
                prioritized, 1, 1);
    }

    private RenderTuning(boolean byteLookahead, boolean refill, boolean predictedFrame,
            boolean historyFallback, int cacheMiB, boolean admissionOnly, boolean prioritized,
            int speculativePipeline, int tallLookaheadBands) {
        this.byteLookahead = byteLookahead;
        this.refill = refill;
        this.predictedFrame = predictedFrame;
        this.historyFallback = historyFallback;
        this.cacheMiB = Math.max(0, Math.min(128, cacheMiB));
        this.admissionOnly = admissionOnly;
        this.prioritized = prioritized;
        this.speculativePipeline = Math.max(1, Math.min(2, speculativePipeline));
        this.tallLookaheadBands = Math.max(1, Math.min(2, tallLookaheadBands));
    }

    /** Larger capacity remains an explicit comparison choice until live validation. */
    public static RenderTuning reader(int requestedMiB) {
        return new RenderTuning(false, true, true, false,
                Math.max(0, Math.min(64, requestedMiB)), true, true);
    }

    /** Diagnostic controls only: retain the normal capacity and one active speculative decode. */
    public static RenderTuning scrolling(int pipeline, int tallBands) {
        return new RenderTuning(false, true, true, false, 0, true, true, pipeline, tallBands);
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
