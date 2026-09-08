package io.github.jnesew.comicviewer.reader;

import io.github.jnesew.comicviewer.document.ComicDocument;
import io.github.jnesew.comicviewer.model.ReadingProgress;
import java.lang.reflect.Proxy;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class ReaderOwnershipTest {
    @Test public void lateCompletionCannotEraseRetryForSameUri() {
        PendingRequests pending = new PendingRequests();
        var old = pending.begin("comic");
        pending.cancelAll();
        var retry = pending.begin("comic");
        assertFalse(pending.complete("comic", old));
        assertTrue(pending.contains("comic"));
        assertTrue(pending.complete("comic", retry));
        assertFalse(pending.contains("comic"));
        assertFalse(pending.complete("comic", retry));
    }

    @Test public void cancellingBeforeFutureAttachmentStillCancelsWorker() {
        PendingRequests pending = new PendingRequests();
        var request = pending.begin("comic");
        pending.cancelAll();
        FutureTask<Void> worker = new FutureTask<>(() -> null);
        request.attach(worker);
        assertTrue(worker.isCancelled());
        assertFalse(pending.complete("comic", request));
    }

    @Test public void replacingRequestCancelsOnlyItsOwnWorker() {
        PendingRequests pending = new PendingRequests();
        var first = pending.begin("one");
        FutureTask<Void> worker = new FutureTask<>(() -> null);
        first.attach(worker);
        var unrelated = pending.begin("two");
        var replacement = pending.begin("one");
        assertTrue(worker.isCancelled());
        assertFalse(pending.complete("one", first));
        assertTrue(pending.complete("two", unrelated));
        assertTrue(pending.complete("one", replacement));
    }

    @Test public void sharedActiveAndBufferHolderClosesDocumentOnce() {
        AtomicInteger closes = new AtomicInteger();
        DocumentResources active = new DocumentResources(document(false, closes), new ReadingProgress());
        DocumentResources buffered = active;
        buffered.close();
        active.close();
        assertTrue(active.isClosed());
        assertEquals(1, closes.get());
    }

    @Test public void unavailableNoticeCannotOverwriteSavedPositionOrCount() {
        ReadingProgress saved = new ReadingProgress();
        saved.uri = "original";
        saved.page = 12;
        saved.pageCount = 40;
        saved.lastOpened = 123;
        assertFalse(ReadingProgressController.capture(document(true, new AtomicInteger()), saved,
                new ReadingProgressController.Position(0, 0f, "fit_width", 1f, false, "continuous"), 999));
        assertEquals("original", saved.uri);
        assertEquals(12, saved.page);
        assertEquals(40, saved.pageCount);
        assertEquals(123, saved.lastOpened);
    }

    @Test public void captureUsesSpreadEndAndCurrentViewWithoutChangingMetadata() {
        ReadingProgress saved = new ReadingProgress();
        saved.title = "Custom title";
        saved.favorite = true;
        assertTrue(ReadingProgressController.capture(document(false, new AtomicInteger()), saved,
                new ReadingProgressController.Position(13, .4f, "custom", 1.5f, true, "spread"), 999));
        assertEquals("comic", saved.uri);
        assertEquals(13, saved.page);
        assertEquals(40, saved.pageCount);
        assertEquals(.4f, saved.scrollRatio, 0f);
        assertEquals(1.5f, saved.zoom, 0f);
        assertTrue(saved.zoomGesturesLocked);
        assertEquals(999, saved.lastOpened);
        assertEquals("Custom title", saved.title);
        assertTrue(saved.favorite);
    }

    private static ComicDocument document(boolean unavailable, AtomicInteger closes) {
        return (ComicDocument) Proxy.newProxyInstance(ComicDocument.class.getClassLoader(),
                new Class<?>[] { ComicDocument.class }, (proxy, method, args) -> switch (method.getName()) {
                    case "isUnavailable" -> unavailable;
                    case "key" -> "comic";
                    case "count" -> 40;
                    case "close" -> { closes.incrementAndGet(); yield null; }
                    default -> throw new AssertionError("Unexpected call: " + method.getName());
                });
    }
}
