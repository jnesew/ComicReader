package io.github.jnesew.comicviewer.model;

import java.util.Collections;
import java.util.List;

/** One card in Series view; standalone titles are represented as one-title groups. */
public final class SeriesGroup {
    public final long id;
    public final String key;
    public final String title;
    public final List<ReadingProgress> issues;
    public final ReadingProgress cover;
    public final int percent;
    public final long lastOpened;
    public final long addedAt;

    public SeriesGroup(
            long id,
            String key,
            String title,
            List<ReadingProgress> issues,
            ReadingProgress cover,
            int percent,
            long lastOpened,
            long addedAt) {
        this.id = id;
        this.key = key;
        this.title = title;
        this.issues = Collections.unmodifiableList(issues);
        this.cover = cover;
        this.percent = percent;
        this.lastOpened = lastOpened;
        this.addedAt = addedAt;
    }

    public boolean isStandalone() {
        return id <= 0L;
    }
}
