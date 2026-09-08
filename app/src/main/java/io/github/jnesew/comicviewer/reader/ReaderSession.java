package io.github.jnesew.comicviewer.reader;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Toast;
import io.github.jnesew.comicviewer.data.CoverStore;
import io.github.jnesew.comicviewer.data.LibraryDatabase;
import io.github.jnesew.comicviewer.data.ReaderPreferences;
import io.github.jnesew.comicviewer.document.ComicDocument;
import io.github.jnesew.comicviewer.document.ComicDocumentFactory;
import io.github.jnesew.comicviewer.document.DocumentInfo;
import io.github.jnesew.comicviewer.model.OpeningZoomPolicy;
import io.github.jnesew.comicviewer.model.PageInfo;
import io.github.jnesew.comicviewer.model.ReadingDirection;
import io.github.jnesew.comicviewer.model.ReadingProgress;
import io.github.jnesew.comicviewer.render.ComicCanvasView;
import io.github.jnesew.comicviewer.render.PagePreviewLoader;
import io.github.jnesew.comicviewer.render.TileRenderer;
import io.github.jnesew.comicviewer.library.LibraryImportCoordinator;
import io.github.jnesew.comicviewer.ui.ReaderScreen;
import io.github.jnesew.comicviewer.util.Ui;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.content.Context;
import io.github.jnesew.comicviewer.R;

/** Owns the current reading session and all open/prefetched document resources. */
public final class ReaderSession implements AutoCloseable {
    public interface Host {
        boolean isFinishing();
        void onReaderActivated();
        void onLibraryChanged();
        void onKeepScreenOnChanged();
        void onOpening(String message);
        void onOpeningMessage(String message);
        void onLoadingFinished();
        void onError(String title, String message);
    }
    enum OpenPosition { REMEMBERED, BEGINNING, END }
    final Context context;
    final LibraryDatabase database;
    final ReaderPreferences preferences;
    final LibraryImportCoordinator imports;
    final Host host;
    ReaderScreen reader;
    final Handler mainHandler = new Handler(Looper.getMainLooper());
    final ContinuousSeriesController continuous = new ContinuousSeriesController(this);
    final DocumentIndexCoordinator indexing = new DocumentIndexCoordinator(this);
    final ReadingProgressController saves = new ReadingProgressController(this);
    private final PendingRequests opening = new PendingRequests();
    volatile DocumentResources active;
    PagePreviewLoader pagePreviewLoader;
    volatile boolean readerActive;
    volatile boolean comicOpening;
    volatile int openGeneration;
    volatile boolean destroyed;
    private final ExecutorService archiveLoader = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "comic-archive-loader");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });

    public ReaderSession(Context context, LibraryDatabase database, ReaderPreferences preferences,
            LibraryImportCoordinator imports, Host host) {
        this.context = context;
        this.database = database;
        this.preferences = preferences;
        this.imports = imports;
        this.host = host;
    }
    public void attach(ReaderScreen reader) { this.reader = reader; }
    public boolean isActive() { return readerActive; }
    public boolean isOpening() { return comicOpening; }
    public boolean isBusy() { return readerActive || comicOpening; }
    ComicDocument document() { return active == null ? null : active.document; }
    TileRenderer renderer() { return active == null ? null : active.renderer; }
    public ReadingProgress progress() { return active == null ? null : active.progress; }
    public void saveNow() { saves.saveNow(); }
    public void cancelOpening() {
        opening.cancelAll();
        ++openGeneration;
        comicOpening = false;
        host.onLoadingFinished();
    }
    public void leaveReader() {
        opening.cancelAll();
        ++openGeneration;
        comicOpening = false;
        saves.saveNow();
        closeCurrentArchive();
        readerActive = false;
    }
    @Override public void close() {
        if (destroyed) return;
        destroyed = true;
        opening.cancelAll();
        ++openGeneration;
        saves.saveNow();
        closeCurrentArchive();
        archiveLoader.shutdownNow();
        continuous.close();
        indexing.close();
        // Pending open completions still need to run to dispose unadopted resources.
    }
    public void awaitStopped() throws InterruptedException {
        archiveLoader.awaitTermination(Long.MAX_VALUE, java.util.concurrent.TimeUnit.NANOSECONDS);
        continuous.awaitStopped();
        indexing.awaitStopped();
    }
    public void trimMemory() {
        if (pagePreviewLoader != null) pagePreviewLoader.trimMemory();
        trimReaderMemory();
    }
    public void trimReaderMemory() {
        if (continuous.continuousResources.isEmpty()) {
            if (renderer() != null) renderer().trimMemory();
            return;
        }
        for (DocumentResources resource : continuous.continuousResources.values()) {
            resource.renderer.trimMemory();
        }
    }

    void activateUnavailable(ReadingProgress item, String mode) {
        item.readingMode = mode;
        activateArchive(new io.github.jnesew.comicviewer.document.UnavailableComicDocument(item),
                item, OpenPosition.BEGINNING);
    }

    public void openComic(Uri uri, boolean manualImport) {
        openComic(uri, manualImport, OpenPosition.REMEMBERED);
    }

    public void openComic(Uri uri, boolean manualImport, OpenPosition openPosition) {
        openComic(uri, manualImport, openPosition, null);
    }

    public void openComic(
            Uri uri,
            boolean manualImport,
            OpenPosition openPosition,
            String readingModeOverride) {
        if (uri == null || destroyed) return;
        continuous.cancelContinuousPrefetch();
        comicOpening = true;
        int generation = ++openGeneration;
        PendingRequests.Request request = opening.begin("active");
        host.onOpening(context.getString(R.string.reader_opening));
        archiveLoader.execute(() -> {
            boolean created = false;
            ComicDocument pendingDocument = null;
            try {
                DocumentInfo document = ComicDocumentFactory.describe(context, uri);
                String sample = manualImport
                        ? imports.sampleContent(uri, document.size) : "";
                // Describing a missing SAF document can return its URI as a fallback title.
                // Do not persist that description (or reset its index/grouping) before open succeeds.
                ReadingProgress saved = database.get(uri.toString());
                List<PageInfo> cachedPages = saved.indexComplete
                        ? database.pageIndex(saved.uri) : Collections.emptyList();
                int openingPage = switch (openPosition) {
                    case BEGINNING -> 0;
                    case END -> Integer.MAX_VALUE;
                    default -> saved.page;
                };
                ComicDocument opened = ComicDocumentFactory.open(
                        context, uri, document, openingPage, cachedPages,
                        saved.documentSize, saved.documentModified, message ->
                        mainHandler.post(() -> {
                            if (generation == openGeneration) host.onOpeningMessage(message);
                        }));
                pendingDocument = opened;
                created = saved.uri.isEmpty();
                database.ensureImported(
                        uri.toString(), opened.title(), document.size, document.modified);
                if (manualImport) {
                    database.markManualSource(opened.key());
                    if (!sample.isEmpty()) {
                        database.setLibraryFingerprint(
                                opened.key(), document.size, sample, saved.contentFingerprint);
                    }
                }
                database.updateTitle(opened.key(), opened.title());
                imports.applySeriesMetadata(opened, null);
                ReadingProgress activated = database.get(opened.key());
                if (readingModeOverride != null) {
                    activated.readingMode = readingModeOverride;
                }
                mainHandler.post(() -> {
                    if (!opening.complete("active", request) || destroyed ||
                            generation != openGeneration || host.isFinishing()) {
                        opened.close();
                        return;
                    }
                    activateArchive(opened, activated, openPosition);
                    host.onLoadingFinished();
                    indexing.startBackgroundIndex(active, generation);
                });
                pendingDocument = null;
            } catch (IOException | RuntimeException error) {
                if (pendingDocument != null) pendingDocument.close();
                if (created) {
                    String cover = database.forget(uri.toString());
                    CoverStore.delete(context, cover);
                }
                mainHandler.post(() -> {
                    if (!opening.complete("active", request) || destroyed ||
                            generation != openGeneration || host.isFinishing()) return;
                    comicOpening = false;
                    host.onLoadingFinished();
                    ReadingProgress failed = database.get(uri.toString());
                    if (readingModeOverride != null && failed.seriesId > 0L) {
                        activateUnavailable(failed, readingModeOverride);
                        return;
                    }
                    host.onError(context.getString(R.string.error_open_comic), safeMessage(error));
                });
            }
        });
    }

    void activateArchive(
            ComicDocument opened, ReadingProgress saved, OpenPosition openPosition) {
        saves.saveNow();
        closeCurrentArchive();
        active = new DocumentResources(opened, saved);
        if (progress().uri.isEmpty()) {
            progress().uri = opened.key();
            progress().title = opened.title();
            progress().originalTitle = opened.title();
        }
        progress().pageCount = opened.count();
        progress().indexedPages = opened.indexedPages();
        progress().indexComplete = opened.isIndexComplete();
        progress().documentSize = opened.documentSize();
        progress().documentModified = opened.documentModified();
        if (openPosition == OpenPosition.BEGINNING) {
            progress().page = 0;
            progress().scrollRatio = 0f;
        } else if (openPosition == OpenPosition.END) {
            progress().page = opened.count() - 1;
            progress().scrollRatio = ComicCanvasView.CONTINUOUS.equals(progress().readingMode)
                    ? 1f : 0f;
        }
        progress().page = clamp(progress().page, 0, opened.count() - 1);
        OpeningZoomPolicy.OpeningZoom openingZoom = OpeningZoomPolicy.resolve(
                preferences.rememberZoom(),
                !progress().isNew(),
                progress().zoomMode,
                progress().zoom,
                preferences.defaultZoomMode());
        progress().zoomMode = openingZoom.mode();
        progress().zoom = openingZoom.zoom();

        active.renderer = new TileRenderer(
                context,
                opened,
                reader.canvas::postInvalidateOnAnimation,
                message -> Toast.makeText(context, message, Toast.LENGTH_LONG).show());
        if (ComicCanvasView.CONTINUOUS.equals(progress().readingMode)) {
            continuous.continuousResources.put(opened.key(),
                    active);
        }
        pagePreviewLoader = new PagePreviewLoader(
                opened,
                Ui.dp(context, 112),
                Ui.dp(context, 144),
                new PagePreviewLoader.Callback() {
                    @Override
                    public void onPreviewReady(int page, android.graphics.Bitmap bitmap) {
                        reader.showPagePreview(page, bitmap);
                    }

                    @Override
                    public void onPreviewUnavailable(int page) {
                        reader.showPagePreviewUnavailable(page);
                    }
                });
        reader.canvas.setTapZones(preferences.tapZones());
        reader.setRightToLeft(ReadingDirection.isRightToLeft(
                progress().readingDirection, opened.suggestedRightToLeft()));
        reader.canvas.setCanvasColor(preferences.canvasColor());
        reader.setUnavailable(opened.isUnavailable());
        reader.canvas.setDocument(renderer(), opened.pages(), progress());
        reader.setTitle(progress().title);
        reader.updatePosition(reader.canvas.page(), reader.canvas.pageEnd(), opened.count());
        reader.updateMode(reader.canvas.readingMode());
        reader.updateZoom(reader.canvas.zoomMode(), reader.canvas.zoom(),
                reader.canvas.zoomGesturesLocked());
        reader.updateBookmark(database.isBookmarked(opened.key(), reader.canvas.page()));

        readerActive = true;
        comicOpening = false;
        host.onReaderActivated();
        reader.setVisibility(View.VISIBLE);
        reader.showChrome();
        host.onKeepScreenOnChanged();
        saves.saveNow();
        if (reader.canvas.isContinuous()) continuous.beginContinuousSession();
    }

    void replacePagePreview(ComicDocument document) {
        reader.dismissPagePreview();
        if (pagePreviewLoader != null) pagePreviewLoader.close();
        pagePreviewLoader = new PagePreviewLoader(
                document,
                Ui.dp(context, 112),
                Ui.dp(context, 144),
                new PagePreviewLoader.Callback() {
                    @Override
                    public void onPreviewReady(int page, android.graphics.Bitmap bitmap) {
                        reader.showPagePreview(page, bitmap);
                    }

                    @Override
                    public void onPreviewUnavailable(int page) {
                        reader.showPagePreviewUnavailable(page);
                    }
                });
    }

    void closeCurrentArchive() {
        continuous.cancelContinuousPrefetch();
        reader.dismissPagePreview();
        reader.canvas.clearDocument();
        if (pagePreviewLoader != null) {
            pagePreviewLoader.close();
            pagePreviewLoader = null;
        }
        for (DocumentResources resource : new ArrayList<>(continuous.continuousResources.values())) {
            resource.close();
        }
        continuous.continuousResources.clear();
        if (active != null) active.close();
        continuous.continuousErrors.clear();
        active = null;
    }

    public void applyComicMetadataEdit(String uri) {
        ReadingProgress updated = database.get(uri);
        if (updated.uri.isEmpty()) return;
        DocumentResources resource = continuous.continuousResources.get(uri);
        if (resource != null) {
            resource.progress = updated;
        }
        if (progress() != null && uri.equals(progress().uri)) {
            active.progress = updated;
            reader.setTitle(updated.title);
            if (reader.canvas.isContinuous()) {
                continuous.cancelContinuousPrefetch();
                continuous.continuousErrors.clear();
                for (Map.Entry<String, DocumentResources> entry :
                        new ArrayList<>(continuous.continuousResources.entrySet())) {
                    if (uri.equals(entry.getKey())) continue;
                    if (continuous.continuousResources.remove(entry.getKey(), entry.getValue())) {
                        entry.getValue().close();
                    }
                }
                continuous.beginContinuousSession();
            }
        }
        host.onLibraryChanged();
    }
    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
    String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? context.getString(R.string.error_file_unreadable) : message;
    }
}
