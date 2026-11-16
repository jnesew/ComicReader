package io.github.jnesew.comicviewer.util;

import io.github.jnesew.comicviewer.model.ReadingProgress;
import java.util.List;

/** A series overview's resume target, independent of the visible search/filter results. */
public final class SeriesContinueTarget {
    private SeriesContinueTarget() { }

    public static ReadingProgress select(List<ReadingProgress> issues) {
        List<ReadingProgress> ordered = SeriesOrganizer.sortIssues(issues);
        ReadingProgress unfinished = null;
        ReadingProgress recent = null;
        ReadingProgress unread = null;
        ReadingProgress first = null;
        for (ReadingProgress issue : ordered) {
            if (!issue.available) continue;
            if (first == null) first = issue;
            if (!issue.read && unread == null) unread = issue;
            if (issue.lastOpened > 0 && (recent == null || issue.lastOpened > recent.lastOpened)) recent = issue;
            if (!issue.read && issue.lastOpened > 0 &&
                    (unfinished == null || issue.lastOpened > unfinished.lastOpened)) unfinished = issue;
        }
        return unfinished != null ? unfinished : unread != null ? unread : recent != null ? recent : first;
    }
}
