package io.github.jnesew.comicviewer.util;

/** Pure presentation policy for a completed source-folder scan. */
public final class LibraryScanResult {
    public static final long DISPLAY_DURATION_MS = 6_000L;

    public final int added;
    public final int updated;
    public final int unchanged;
    public final int duplicates;
    public final int skipped;
    public final boolean incomplete;

    public LibraryScanResult(
            int added,
            int updated,
            int unchanged,
            int duplicates,
            int skipped,
            boolean incomplete) {
        this.added = Math.max(0, added);
        this.updated = Math.max(0, updated);
        this.unchanged = Math.max(0, unchanged);
        this.duplicates = Math.max(0, duplicates);
        this.skipped = Math.max(0, skipped);
        this.incomplete = incomplete;
    }

    /** Unchanged files are deliberately silent; only work or actionable findings are surfaced. */
    public boolean shouldShow() {
        return incomplete || added > 0 || updated > 0 || duplicates > 0 || skipped > 0;
    }

    public boolean shouldPersist() {
        return incomplete;
    }
}
