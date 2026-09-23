package io.github.jnesew.comicviewer.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class ReadStatusTest {
    @Test public void savedFinalPageDoesNotForceReadStatus() {
        ReadingProgress issue = issue("first", false);
        issue.page = 9;
        issue.pageCount = 10;
        issue.lastOpened = 100;
        assertFalse(issue.isCompleted());
        issue.read = true;
        assertTrue(issue.isCompleted());
        issue.page = 0;
        assertTrue(issue.isCompleted());
    }

    @Test public void lateImportedIssueMakesPreviouslyCaughtUpSeriesUnread() {
        ReadingProgress first = issue("first", true);
        ReadingProgress second = issue("second", true);
        SeriesGroup before = group(List.of(first, second));
        assertTrue(before.isCaughtUp());
        assertEquals(0, before.unreadIssueCount());

        ReadingProgress late = issue("late", false);
        late.available = false;
        SeriesGroup after = group(List.of(first, second, late));
        assertFalse(after.isCaughtUp());
        assertEquals(1, after.unreadIssueCount());
        assertEquals(1, after.unavailableIssueCount());
    }

    private static ReadingProgress issue(String uri, boolean read) {
        ReadingProgress value = new ReadingProgress();
        value.uri = uri;
        value.read = read;
        return value;
    }

    private static SeriesGroup group(List<ReadingProgress> issues) {
        return new SeriesGroup(1, "series:1", "Series", issues, issues.get(0),
                0, 0, 0);
    }
}
