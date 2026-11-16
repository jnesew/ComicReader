package io.github.jnesew.comicviewer.util;

import io.github.jnesew.comicviewer.model.ReadingProgress;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class SeriesContinueTargetTest {
    private static ReadingProgress issue(String number, boolean read, long opened) {
        ReadingProgress result = new ReadingProgress();
        result.uri = "issue:" + number; result.title = number; result.seriesNumber = number;
        result.read = read; result.lastOpened = opened;
        return result;
    }
    @Test public void resumesLatestExplicitlyUnfinishedIssue() {
        ReadingProgress first = issue("1", false, 0), progress = issue("5", false, 200), done = issue("6", true, 300);
        assertSame(progress, SeriesContinueTarget.select(List.of(done, first, progress, issue("2", false, 100))));
    }
    @Test public void choosesFirstUnreadInCanonicalOrderAndSkipsUnavailable() {
        ReadingProgress missing = issue("1", false, 500); missing.available = false;
        ReadingProgress next = issue("2", false, 0);
        assertSame(next, SeriesContinueTarget.select(List.of(issue("10", false, 0), missing, next, issue("0", true, 100))));
    }
    @Test public void completedSeriesCanResumeLatestAvailableAndEmptySeriesHasNoTarget() {
        ReadingProgress recent = issue("3", true, 200);
        assertSame(recent, SeriesContinueTarget.select(List.of(issue("1", true, 100), recent)));
        ReadingProgress missing = issue("1", false, 0); missing.available = false;
        assertNull(SeriesContinueTarget.select(List.of(missing)));
        assertNull(SeriesContinueTarget.select(List.of()));
    }
}
