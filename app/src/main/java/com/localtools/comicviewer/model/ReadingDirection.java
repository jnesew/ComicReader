package com.localtools.comicviewer.model;

/** Stable per-title reading-direction values stored in the library database. */
public final class ReadingDirection {
    public static final String AUTO = "auto";
    public static final String LEFT_TO_RIGHT = "ltr";
    public static final String RIGHT_TO_LEFT = "rtl";

    private ReadingDirection() {
    }

    public static String normalize(String value) {
        if (LEFT_TO_RIGHT.equals(value)) return LEFT_TO_RIGHT;
        if (RIGHT_TO_LEFT.equals(value)) return RIGHT_TO_LEFT;
        return AUTO;
    }

    public static boolean isRightToLeft(String value, boolean documentSuggestion) {
        return switch (normalize(value)) {
            case RIGHT_TO_LEFT -> true;
            case LEFT_TO_RIGHT -> false;
            default -> documentSuggestion;
        };
    }
}
