package io.github.jnesew.comicviewer.reader;
import android.net.Uri;
import android.widget.Toast;
import io.github.jnesew.comicviewer.document.ComicDocument;
import io.github.jnesew.comicviewer.document.ComicDocumentFactory;
import io.github.jnesew.comicviewer.document.DocumentInfo;
import io.github.jnesew.comicviewer.model.PageInfo;
import io.github.jnesew.comicviewer.model.ReadingDirection;
import io.github.jnesew.comicviewer.model.ReadingProgress;
import io.github.jnesew.comicviewer.render.ComicCanvasView;
import io.github.jnesew.comicviewer.render.TileRenderer;
import io.github.jnesew.comicviewer.util.SeriesNavigator;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import io.github.jnesew.comicviewer.R;

/** Session-scoped series buffering and adjacent request ownership. */
final class ContinuousSeriesController {
    private final ReaderSession session;
    ContinuousSeriesController(ReaderSession session) { this.session = session; }
    final Map<String, DocumentResources> continuousResources =
            new java.util.concurrent.ConcurrentHashMap<>();
    final Map<String, String> continuousErrors = new java.util.HashMap<>();
    private final PendingRequests pending = new PendingRequests();
    private final ExecutorService adjacentLoader = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "comic-adjacent-loader");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    void close() { adjacentLoader.shutdownNow(); }
    void awaitStopped() throws InterruptedException {
        adjacentLoader.awaitTermination(Long.MAX_VALUE, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    void beginContinuousSession() {
        if (session.document() == null || session.renderer() == null || session.progress() == null ||
                !session.reader.canvas.isContinuous()) return;
        session.progress().readingMode = ComicCanvasView.CONTINUOUS;
        continuousResources.putIfAbsent(session.document().key(),
                session.active);
        refreshContinuousDocuments(session.document().key(), session.reader.canvas.page(), session.reader.canvas.pageRatio());
        requestContinuousAdjacent(-1, false);
        requestContinuousAdjacent(1, false);
    }

    void leaveContinuousSession(String readingMode) {
        if (session.document() == null || session.renderer() == null || session.progress() == null) return;
        int page = session.reader.canvas.page();
        float ratio = session.reader.canvas.pageRatio();
        session.progress().page = page;
        session.progress().scrollRatio = ratio;
        if (!session.document().isUnavailable()) {
            session.progress().lastOpened = System.currentTimeMillis();
            session.database.saveReadingProgress(session.progress());
        }
        cancelContinuousPrefetch();
        for (DocumentResources resource : new ArrayList<>(continuousResources.values())) {
            if (resource.document != session.document()) resource.close();
        }
        continuousResources.clear();
        continuousErrors.clear();
        session.progress().readingMode = readingMode;
        session.progress().zoomMode = ComicCanvasView.FIT_WIDTH;
        session.progress().zoom = 1f;
        session.reader.canvas.setDocument(session.renderer(), session.document().pages(), session.progress());
        session.reader.updatePosition(session.reader.canvas.page(), session.reader.canvas.pageEnd(), session.document().count());
        session.reader.updateZoom(session.reader.canvas.zoomMode(), session.reader.canvas.zoom(),
                session.reader.canvas.zoomGesturesLocked());
    }

    boolean switchContinuousIssue(String key) {
        DocumentResources next = continuousResources.get(key);
        if (next == null) return false;
        if (session.progress() != null && session.document() != null && !session.document().isUnavailable()) {
            session.progress().lastOpened = System.currentTimeMillis();
            session.database.saveReadingProgress(session.progress());
        }
        cancelContinuousPrefetch();
        session.active = next;
        session.reader.setUnavailable(session.document().isUnavailable());
        session.progress().readingMode = ComicCanvasView.CONTINUOUS;
        session.progress().lastOpened = System.currentTimeMillis();
        session.replacePagePreview(session.document());
        session.reader.setTitle(session.progress().title);
        session.reader.updatePosition(session.reader.canvas.page(), session.reader.canvas.pageEnd(), session.document().count());
        session.reader.updateBookmark(session.database.isBookmarked(session.document().key(), session.reader.canvas.page()));
        session.reader.setRightToLeft(ReadingDirection.isRightToLeft(
                session.progress().readingDirection, session.document().suggestedRightToLeft()));
        trimContinuousResources();
        refreshContinuousDocuments(key, session.reader.canvas.page(), session.reader.canvas.pageRatio());
        requestContinuousAdjacent(-1, false);
        requestContinuousAdjacent(1, false);
        return true;
    }

    void refreshContinuousDocuments(
            String anchorKey, int anchorPage, float anchorRatio) {
        if (!session.reader.canvas.isContinuous() || session.progress() == null) return;
        List<ReadingProgress> issues = session.database.seriesIssues(session.progress().seriesId);
        ArrayList<ComicCanvasView.ContinuousDocument> documents = new ArrayList<>();
        if (issues.isEmpty()) {
            DocumentResources only = continuousResources.get(anchorKey);
            if (only != null) documents.add(asContinuousDocument(only));
        } else {
            for (ReadingProgress issue : issues) {
                DocumentResources resource = continuousResources.get(issue.uri);
                if (resource != null) documents.add(asContinuousDocument(resource));
            }
        }
        if (!documents.isEmpty()) {
            session.reader.canvas.setContinuousDocuments(documents, anchorKey, anchorPage, anchorRatio);
        }
        updateContinuousBoundaries();
    }

    static ComicCanvasView.ContinuousDocument asContinuousDocument(
            DocumentResources resource) {
        return new ComicCanvasView.ContinuousDocument(
                resource.document.key(), resource.progress.title,
                resource.renderer, resource.document.pages());
    }

    void trimContinuousResources() {
        if (session.progress() == null || session.progress().seriesId <= 0L) return;
        List<ReadingProgress> issues = session.database.seriesIssues(session.progress().seriesId);
        int active = indexOfIssue(issues, session.progress().uri);
        if (active < 0) return;
        Set<String> keep = new java.util.HashSet<>();
        for (int index = Math.max(0, active - 1);
                index <= Math.min(issues.size() - 1, active + 1); index++) {
            keep.add(issues.get(index).uri);
        }
        for (Map.Entry<String, DocumentResources> entry :
                new ArrayList<>(continuousResources.entrySet())) {
            if (keep.contains(entry.getKey())) continue;
            if (continuousResources.remove(entry.getKey(), entry.getValue())) {
                entry.getValue().close();
            }
        }
    }

    void requestContinuousAdjacent(int direction, boolean retry) {
        if (!session.reader.canvas.isContinuous() || session.progress() == null || session.progress().seriesId <= 0L ||
                session.destroyed) return;
        List<ReadingProgress> issues = session.database.seriesIssues(session.progress().seriesId);
        if (issues.size() <= 1) {
            updateContinuousBoundaries();
            return;
        }
        ReadingProgress edge = loadedEdge(issues, direction);
        ReadingProgress adjacent = edge == null ? null : direction > 0
                ? SeriesNavigator.nextIssue(issues, edge.uri)
                : SeriesNavigator.previousIssue(issues, edge.uri);
        if (adjacent == null) {
            updateContinuousBoundaries();
            return;
        }
        int activeIndex = indexOfIssue(issues, session.progress().uri);
        int adjacentIndex = indexOfIssue(issues, adjacent.uri);
        if (activeIndex < 0 || adjacentIndex < 0 ||
                Math.abs(adjacentIndex - activeIndex) > 1) return;
        if (continuousResources.containsKey(adjacent.uri) ||
                pending.contains(adjacent.uri)) return;
        if (!adjacent.available) {
            installContinuousNotice(adjacent.uri, session.openGeneration, session.progress().seriesId);
            return;
        }
        if (retry) continuousErrors.remove(adjacent.uri);
        if (continuousErrors.containsKey(adjacent.uri)) {
            updateContinuousBoundaries();
            return;
        }

        PendingRequests.Request request = pending.begin(adjacent.uri);
        updateContinuousBoundaries();
        int generation = session.openGeneration;
        String uriKey = adjacent.uri;
        long seriesId = session.progress().seriesId;
        Future<?> task = adjacentLoader.submit(() ->
                openContinuousAdjacent(uriKey, direction, generation, seriesId, request));
        request.attach(task);
    }

    void openContinuousAdjacent(
            String uriKey, int direction, int generation, long seriesId, PendingRequests.Request request) {
        ComicDocument opened = null;
        TileRenderer openedRenderer = null;
        try {
            if (Thread.currentThread().isInterrupted() || generation != session.openGeneration) return;
            Uri uri = Uri.parse(uriKey);
            ReadingProgress saved = session.database.get(uriKey);
            if (saved.uri.isEmpty()) throw new IOException("Library item is no longer available.");
            DocumentInfo document = ComicDocumentFactory.describe(session.context, uri);
            List<PageInfo> cachedPages = saved.indexComplete
                    ? session.database.pageIndex(saved.uri) : Collections.emptyList();
            opened = ComicDocumentFactory.open(
                    session.context, uri, document, direction > 0 ? 0 : Integer.MAX_VALUE,
                    cachedPages, saved.documentSize, saved.documentModified, null);
            session.database.updateTitle(opened.key(), opened.title());
            ReadingProgress activated = session.database.get(opened.key());
            activated.pageCount = opened.count();
            activated.indexedPages = opened.indexedPages();
            activated.indexComplete = opened.isIndexComplete();
            activated.documentSize = opened.documentSize();
            activated.documentModified = opened.documentModified();
            activated.readingMode = ComicCanvasView.CONTINUOUS;
            ComicDocument readyDocument = opened;
            openedRenderer = new TileRenderer(
                    session.context,
                    readyDocument,
                    session.reader.canvas::postInvalidateOnAnimation,
                    message -> Toast.makeText(session.context, message, Toast.LENGTH_LONG).show());
            DocumentResources resource = new DocumentResources(
                    readyDocument, openedRenderer, activated);
            session.mainHandler.post(() -> {
                boolean currentRequest = pending.complete(uriKey, request);
                if (!currentRequest || generation != session.openGeneration || session.destroyed || !session.reader.canvas.isContinuous() ||
                        session.progress() == null || session.progress().seriesId != seriesId) {
                    resource.close();
                    return;
                }
                continuousResources.put(uriKey, resource);
                continuousErrors.remove(uriKey);
                refreshContinuousDocuments(
                        session.document().key(), session.reader.canvas.page(), session.reader.canvas.pageRatio());
                session.indexing.startContinuousBackgroundIndex(resource, generation);
            });
        } catch (IOException | RuntimeException error) {
            try {
                if (openedRenderer != null) openedRenderer.close();
            } finally {
                if (opened != null) opened.close();
            }
            String message = session.safeMessage(error);
            session.mainHandler.post(() -> {
                if (!pending.complete(uriKey, request)) return;
                if (generation != session.openGeneration || session.destroyed) return;
                continuousErrors.put(uriKey, message);
                installContinuousNotice(uriKey, generation, seriesId);
            });
        }
    }

    void installContinuousNotice(String uriKey, int generation, long seriesId) {
        if (session.destroyed || generation != session.openGeneration || !session.reader.canvas.isContinuous() ||
                session.progress() == null || session.progress().seriesId != seriesId) return;
        ReadingProgress item = session.database.get(uriKey);
        if (item.uri.isEmpty() || continuousResources.containsKey(uriKey)) return;
        item.readingMode = ComicCanvasView.CONTINUOUS;
        ComicDocument notice = new io.github.jnesew.comicviewer.document.UnavailableComicDocument(item);
        TileRenderer renderer = new TileRenderer(session.context, notice,
                session.reader.canvas::postInvalidateOnAnimation, message -> { });
        continuousResources.put(uriKey, new DocumentResources(notice, renderer, item));
        refreshContinuousDocuments(session.document().key(), session.reader.canvas.page(), session.reader.canvas.pageRatio());
    }

    void cancelContinuousPrefetch() {
        pending.cancelAll();
    }

    void updateContinuousBoundaries() {
        if (!session.reader.canvas.isContinuous() || session.progress() == null) return;
        List<ReadingProgress> issues = session.database.seriesIssues(session.progress().seriesId);
        updateContinuousBoundary(issues, -1);
        updateContinuousBoundary(issues, 1);
    }

    void updateContinuousBoundary(List<ReadingProgress> issues, int direction) {
        ReadingProgress edge = loadedEdge(issues, direction);
        ReadingProgress adjacent = edge == null ? null : direction > 0
                ? SeriesNavigator.nextIssue(issues, edge.uri)
                : SeriesNavigator.previousIssue(issues, edge.uri);
        String text;
        boolean retry = false;
        if (adjacent == null) {
            text = session.context.getString(direction > 0
                    ? R.string.reader_end_of_series : R.string.reader_start_of_series);
        } else if (!adjacent.available) {
            text = session.context.getString(direction > 0
                    ? R.string.reader_next_issue_unavailable
                    : R.string.reader_previous_issue_unavailable);
        } else if (pending.contains(adjacent.uri)) {
            text = session.context.getString(direction > 0
                    ? R.string.reader_loading_next_issue
                    : R.string.reader_loading_previous_issue);
        } else if (continuousErrors.containsKey(adjacent.uri)) {
            text = session.context.getString(direction > 0
                    ? R.string.reader_next_issue_retry
                    : R.string.reader_previous_issue_retry);
            retry = true;
        } else {
            text = "";
        }
        session.reader.canvas.setContinuousBoundary(direction, text, retry);
    }

    ReadingProgress loadedEdge(List<ReadingProgress> issues, int direction) {
        ReadingProgress edge = null;
        for (ReadingProgress issue : issues) {
            if (!continuousResources.containsKey(issue.uri)) continue;
            edge = issue;
            if (direction < 0) break;
        }
        return edge;
    }

    static int indexOfIssue(List<ReadingProgress> issues, String uri) {
        for (int index = 0; index < issues.size(); index++) {
            if (issues.get(index).uri.equals(uri)) return index;
        }
        return -1;
    }
}
