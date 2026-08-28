package com.localtools.comicviewer.model;

import com.localtools.comicviewer.util.InputLimits;

/** Conservative embedded metadata used only for library grouping and issue order. */
public final class SeriesMetadata {
    public static final SeriesMetadata EMPTY = new SeriesMetadata("", "");

    public final String name;
    public final String number;

    public SeriesMetadata(String name, String number) {
        this.name = InputLimits.normalizeText(name, InputLimits.MAX_SERIES_CODE_POINTS);
        this.number = InputLimits.normalizeText(number, InputLimits.MAX_ISSUE_CODE_POINTS);
    }

    public boolean hasSeries() {
        return !name.isEmpty();
    }
}
