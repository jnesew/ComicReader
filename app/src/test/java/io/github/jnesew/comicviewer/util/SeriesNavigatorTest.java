package io.github.jnesew.comicviewer.util;

import io.github.jnesew.comicviewer.model.ReadingProgress;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class SeriesNavigatorTest {
    @Test
    public void followsCanonicalNaturalIssueOrder() {
        ReadingProgress issueTen = issue("ten", "10");
        ReadingProgress issueTwo = issue("two", "2");
        ReadingProgress special = issue("special", "");

        List<ReadingProgress> issues = List.of(issueTen, special, issueTwo);

        assertEquals("ten", SeriesNavigator.nextIssue(issues, "two").uri);
        assertEquals("special", SeriesNavigator.nextIssue(issues, "ten").uri);
        assertNull(SeriesNavigator.nextIssue(issues, "special"));
    }

    @Test
    public void returnsNullWhenCurrentIssueIsAbsent() {
        assertNull(SeriesNavigator.nextIssue(List.of(issue("one", "1")), "missing"));
    }

    @Test
    public void returnsNullForInvalidInputs() {
        assertNull(SeriesNavigator.nextIssue(null, "one"));
        assertNull(SeriesNavigator.nextIssue(List.of(), "one"));
        assertNull(SeriesNavigator.nextIssue(List.of(issue("one", "1")), ""));
    }

    private static ReadingProgress issue(String uri, String number) {
        ReadingProgress item = new ReadingProgress();
        item.uri = uri;
        item.title = uri;
        item.seriesId = 7L;
        item.seriesTitle = "Quiet City";
        item.seriesNumber = number;
        return item;
    }
}
