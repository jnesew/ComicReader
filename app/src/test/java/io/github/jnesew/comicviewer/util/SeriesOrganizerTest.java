package io.github.jnesew.comicviewer.util;

import io.github.jnesew.comicviewer.data.LibraryDatabase;
import io.github.jnesew.comicviewer.model.ReadingProgress;
import io.github.jnesew.comicviewer.model.SeriesGroup;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class SeriesOrganizerTest {
    @Test
    public void groupsStableSeriesAndOrdersNumberedIssuesNaturally() {
        ReadingProgress issueTen = issue("ten", 7L, "Quiet City", "10", 70);
        ReadingProgress issueTwo = issue("two", 7L, "Quiet City", "2", 20);
        ReadingProgress special = issue("special", 7L, "Quiet City", "", 10);
        ReadingProgress standalone = issue("solo", 0L, "", "", 40);
        standalone.title = "Solo Story";

        List<SeriesGroup> groups = SeriesOrganizer.group(
                List.of(issueTen, standalone, special, issueTwo),
                List.of(issueTen, standalone, special, issueTwo),
                LibraryDatabase.SORT_TITLE_ASC);

        assertEquals(2, groups.size());
        SeriesGroup quietCity = groups.get(0);
        assertEquals("Quiet City", quietCity.title);
        assertEquals(List.of("two", "ten", "special"), quietCity.issues.stream()
                .map(item -> item.uri).toList());
        assertEquals("two", quietCity.cover.uri);
        assertTrue(groups.get(1).isStandalone());
    }

    @Test
    public void aMatchingIssueKeepsTheWholeSeriesCardAndCount() {
        ReadingProgress one = issue("one", 4L, "Orbit", "1", 0);
        ReadingProgress two = issue("two", 4L, "Orbit", "2", 100);

        List<SeriesGroup> groups = SeriesOrganizer.group(
                List.of(one, two), List.of(two), LibraryDatabase.SORT_RECENT);

        assertEquals(1, groups.size());
        assertEquals(2, groups.get(0).issues.size());
        assertEquals(50, groups.get(0).percent);
    }

    private static ReadingProgress issue(
            String uri, long seriesId, String series, String number, int percent) {
        ReadingProgress item = new ReadingProgress();
        item.uri = uri;
        item.title = uri;
        item.seriesId = seriesId;
        item.seriesTitle = series;
        item.seriesNumber = number;
        item.pageCount = 101;
        item.page = percent;
        return item;
    }
}
