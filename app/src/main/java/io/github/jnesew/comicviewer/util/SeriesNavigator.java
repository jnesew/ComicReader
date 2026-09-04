package io.github.jnesew.comicviewer.util;

import io.github.jnesew.comicviewer.model.ReadingProgress;

import java.util.List;

/** Pure issue-to-issue traversal using the library's canonical series order. */
public final class SeriesNavigator {
    private SeriesNavigator() {
    }

    public static ReadingProgress nextIssue(
            List<ReadingProgress> issues, String currentUri) {
        if (issues == null || currentUri == null || currentUri.isEmpty()) return null;
        List<ReadingProgress> ordered = SeriesOrganizer.sortIssues(issues);
        for (int index = 0; index < ordered.size(); index++) {
            if (!currentUri.equals(ordered.get(index).uri)) continue;
            return index + 1 < ordered.size() ? ordered.get(index + 1) : null;
        }
        return null;
    }
}
