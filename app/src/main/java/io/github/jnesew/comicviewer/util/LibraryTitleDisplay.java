package io.github.jnesew.comicviewer.util;

/** Persisted title space below library covers. */
public enum LibraryTitleDisplay {
    COVER_ONLY("cover_only", 0),
    ONE_LINE("one_line", 1),
    TWO_LINES("two_lines", 2);

    public final String key;
    public final int titleLines;

    LibraryTitleDisplay(String key, int titleLines) {
        this.key = key;
        this.titleLines = titleLines;
    }

    public static LibraryTitleDisplay fromKey(String key) {
        for (LibraryTitleDisplay display : values()) {
            if (display.key.equals(key)) return display;
        }
        return TWO_LINES;
    }
}
