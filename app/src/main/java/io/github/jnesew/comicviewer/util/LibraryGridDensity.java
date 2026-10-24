package io.github.jnesew.comicviewer.util;

/** Persisted adaptive cover-grid presets. Standard intentionally matches the existing layout. */
public enum LibraryGridDensity {
    LARGE("large", 168, 252, 17, 14, 18),
    STANDARD("standard", 132, 205, 16, 13, 18),
    COMPACT("compact", 104, 162, 14, 12, 16),
    DENSE("dense", 80, 124, 12, 11, 14);

    public final String key;
    public final int minimumCardWidthDp;
    public final int coverHeightDp;
    public final int titleTextSp;
    public final int detailTextSp;
    public final int verticalSpacingDp;

    LibraryGridDensity(
            String key,
            int minimumCardWidthDp,
            int coverHeightDp,
            int titleTextSp,
            int detailTextSp,
            int verticalSpacingDp) {
        this.key = key;
        this.minimumCardWidthDp = minimumCardWidthDp;
        this.coverHeightDp = coverHeightDp;
        this.titleTextSp = titleTextSp;
        this.detailTextSp = detailTextSp;
        this.verticalSpacingDp = verticalSpacingDp;
    }

    public static LibraryGridDensity fromKey(String key) {
        if (key != null) {
            for (LibraryGridDensity density : values()) {
                if (density.key.equals(key)) return density;
            }
        }
        return STANDARD;
    }
}
