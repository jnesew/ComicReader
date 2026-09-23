package io.github.jnesew.comicviewer.model;

/** Resolve title overrides without turning an inherited value into a saved override. */
public final class ReaderDefaults {
    public static final String SINGLE = "single";
    public static final String SPREAD = "spread";
    public static final String CONTINUOUS = "continuous";

    private ReaderDefaults() { }

    public static String layout(String saved, boolean overridden, String globalDefault) {
        return normalizeLayout(overridden ? saved : globalDefault);
    }

    public static String direction(String saved, boolean overridden, String globalDefault) {
        return ReadingDirection.normalize(overridden ? saved : globalDefault);
    }

    public static String normalizeLayout(String value) {
        if (SPREAD.equals(value)) return SPREAD;
        if (CONTINUOUS.equals(value)) return CONTINUOUS;
        return SINGLE;
    }
}
