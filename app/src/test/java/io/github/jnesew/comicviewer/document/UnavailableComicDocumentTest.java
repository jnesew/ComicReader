package io.github.jnesew.comicviewer.document;

import io.github.jnesew.comicviewer.model.ReadingProgress;
import io.github.jnesew.comicviewer.util.SeriesNavigator;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class UnavailableComicDocumentTest {
    @Test
    public void noticeHasLayoutWithoutChangingStoredIssueState() {
        ReadingProgress item = issue("missing", false);
        item.page = 18;
        item.pageCount = 45;
        item.lastOpened = 12345L;
        item.scrollRatio = 0.75f;
        item.favorite = true;
        UnavailableComicDocument notice = new UnavailableComicDocument(item);
        assertTrue(notice.isUnavailable());
        assertEquals("missing", notice.key());
        assertEquals(1, notice.pages().size());
        assertEquals(0, notice.indexedPages());
        notice.buildPageIndex(null, null, new ComicDocument.IndexCallback() {
            public void onProgress(int indexed, int count) { fail("Notice cannot be indexed"); }
            public void onPagesUpdated() { fail("Notice cannot be indexed"); }
            public void onComplete(List<io.github.jnesew.comicviewer.model.PageInfo> pages) {
                fail("Notice cannot be persisted as a page index");
            }
        });
        notice.close();
        assertEquals(18, item.page);
        assertEquals(45, item.pageCount);
        assertEquals(12345L, item.lastOpened);
        assertEquals(0.75f, item.scrollRatio, 0f);
        assertTrue(item.favorite);
        assertFalse(item.available);
    }

    @Test
    public void consecutiveUnavailableIssuesRetainTheirNavigationPositions() {
        List<ReadingProgress> issues = List.of(issue("a", true), issue("b", false),
                issue("c", false), issue("d", true));
        assertEquals("b", SeriesNavigator.nextIssue(issues, "a").uri);
        assertEquals("c", SeriesNavigator.nextIssue(issues, "b").uri);
        assertEquals("d", SeriesNavigator.nextIssue(issues, "c").uri);
        assertEquals("c", SeriesNavigator.previousIssue(issues, "d").uri);
        assertEquals("b", SeriesNavigator.previousIssue(issues, "c").uri);
        assertEquals("a", SeriesNavigator.previousIssue(issues, "b").uri);
        assertNull(SeriesNavigator.nextIssue(issues, "d"));
    }

    private static ReadingProgress issue(String key, boolean available) {
        ReadingProgress item = new ReadingProgress();
        item.uri = key;
        item.title = key;
        item.available = available;
        return item;
    }
}
