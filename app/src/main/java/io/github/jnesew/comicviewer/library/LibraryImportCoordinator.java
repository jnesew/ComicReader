package io.github.jnesew.comicviewer.library;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import io.github.jnesew.comicviewer.data.CoverStore;
import io.github.jnesew.comicviewer.data.ContentFingerprint;
import io.github.jnesew.comicviewer.data.LibraryDatabase;
import io.github.jnesew.comicviewer.data.LibraryFolderScanner;
import io.github.jnesew.comicviewer.document.ComicDocument;
import io.github.jnesew.comicviewer.document.ComicDocumentFactory;
import io.github.jnesew.comicviewer.document.DocumentInfo;
import io.github.jnesew.comicviewer.model.PageInfo;
import io.github.jnesew.comicviewer.model.ReadingProgress;
import io.github.jnesew.comicviewer.model.SeriesMetadata;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.content.Context;

/** Owns import/backfill tasks and their temporary documents. Reader indexing only shares job claims. */
public final class LibraryImportCoordinator implements AutoCloseable {
    public interface Listener {
        void onLibraryChanged();
        void onImportFinished(int count);
    }
    private final Context context;
    private final LibraryDatabase database;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean destroyed;
    private Listener listener;
    private final Set<String> libraryJobs = Collections.synchronizedSet(new java.util.HashSet<>());
    private final ExecutorService libraryWorker = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "comic-library-worker");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    public LibraryImportCoordinator(Context context, LibraryDatabase database, Listener listener) {
        this.context = context.getApplicationContext();
        this.database = database;
        this.listener = listener;
    }
    public boolean claimJob(String key) { return libraryJobs.add(key); }
    public void releaseJob(String key) { libraryJobs.remove(key); }
    public boolean isProcessing(String key) { return libraryJobs.contains(key); }
    private void notifyChanged() {
        if (!destroyed && listener != null) listener.onLibraryChanged();
    }
    @Override public void close() {
        destroyed = true;
        listener = null;
        mainHandler.removeCallbacksAndMessages(null);
        libraryWorker.shutdownNow();
    }
    public void awaitStopped() throws InterruptedException {
        libraryWorker.awaitTermination(Long.MAX_VALUE, java.util.concurrent.TimeUnit.NANOSECONDS);
    }
    public void importComics(List<Uri> uris) {
        libraryWorker.execute(() -> {
            int imported = 0;
            for (Uri uri : uris) {
                if (destroyed || Thread.currentThread().isInterrupted()) break;
                if (processLibraryItem(uri, true, null, true)) imported++;
            }
            int count = imported;
            mainHandler.post(() -> {
                if (destroyed) return;
                notifyChanged();
                if (listener != null) listener.onImportFinished(count);
            });
        });
    }

    public void startCoverBackfill() {
        libraryWorker.execute(() -> {
            for (ReadingProgress item : database.coversNeedingBackfill(200)) {
                if (destroyed || Thread.currentThread().isInterrupted()) return;
                try {
                    processLibraryItem(Uri.parse(item.uri), false);
                } catch (RuntimeException ignored) {
                    database.setCover(item.uri, "", LibraryDatabase.COVER_FAILED);
                }
            }
        });
    }

    public void startMetadataBackfill() {
        libraryWorker.execute(() -> {
            for (ReadingProgress item : database.metadataNeedingBackfill(500)) {
                if (destroyed || Thread.currentThread().isInterrupted()) return;
                String key = item.uri;
                if (!libraryJobs.add(key)) continue;
                try {
                    Uri uri = Uri.parse(key);
                    DocumentInfo document = ComicDocumentFactory.describe(context, uri);
                    List<PageInfo> cachedPages = item.indexComplete
                            ? database.pageIndex(key) : Collections.emptyList();
                    try (ComicDocument opened = ComicDocumentFactory.open(
                            context, uri, document, item.page, cachedPages,
                            item.documentSize, item.documentModified, null)) {
                        database.updateTitle(key, opened.title());
                        applySeriesMetadata(opened, null);
                    }
                } catch (IOException | RuntimeException error) {
                    database.markMetadataFailed(key);
                } finally {
                    libraryJobs.remove(key);
                }
            }
            mainHandler.post(() -> {
                if (!destroyed) notifyChanged();
            });
        });
    }

    boolean processLibraryItem(Uri uri, boolean buildFullIndex) {
        return processLibraryItem(uri, buildFullIndex, null, false);
    }

    boolean processLibraryItem(
            Uri uri,
            boolean buildFullIndex,
            LibraryFolderScanner.Entry sourceEntry,
            boolean manualImport) {
        String key = uri.toString();
        if (!libraryJobs.add(key)) return false;
        boolean created = false;
        try {
            DocumentInfo document = ComicDocumentFactory.describe(context, uri);
            String sample = manualImport
                    ? sampleContent(uri, document.size) : "";
            created = database.get(key).uri.isEmpty();
            ReadingProgress item = database.ensureImported(
                    key, document.title, document.size, document.modified);
            if (manualImport) {
                database.markManualSource(key);
                if (!sample.isEmpty()) {
                    database.setLibraryFingerprint(
                            key, document.size, sample, item.contentFingerprint);
                }
            }
            List<PageInfo> cachedPages = item.indexComplete
                    ? database.pageIndex(key) : Collections.emptyList();
            try (ComicDocument opened = ComicDocumentFactory.open(
                    context, uri, document, item.page, cachedPages,
                    item.documentSize, item.documentModified, null)) {
                database.updateTitle(key, opened.title());
                applySeriesMetadata(opened, sourceEntry);
                if (!CoverStore.exists(item.coverPath)) {
                    String cover = CoverStore.ensureCover(context, opened);
                    database.setCover(key, cover, LibraryDatabase.COVER_READY);
                }
                database.updateArchiveState(key, opened.count(), opened.indexedPages(),
                        opened.isIndexComplete(), opened.documentSize(), opened.documentModified());
                mainHandler.post(() -> {
                    if (!destroyed) notifyChanged();
                });
                if (buildFullIndex && !opened.isIndexComplete()) {
                    opened.buildPageIndex(context, null, new ComicDocument.IndexCallback() {
                        @Override public boolean isCancelled() {
                            return destroyed || Thread.currentThread().isInterrupted();
                        }
                        @Override
                        public void onProgress(int indexedPages, int pageCount) {
                            database.updateArchiveState(key, pageCount, indexedPages, false,
                                    opened.documentSize(), opened.documentModified());
                        }

                        @Override
                        public void onPagesUpdated() {
                        }

                        @Override
                        public void onComplete(List<PageInfo> pages) {
                            database.replacePageIndex(key, pages);
                        }
                    });
                }
            }
            mainHandler.post(() -> {
                if (!destroyed) notifyChanged();
            });
            return true;
        } catch (IOException | RuntimeException error) {
            if (created) {
                String cover = database.forget(key);
                CoverStore.delete(context, cover);
            } else {
                database.setCover(key, "", LibraryDatabase.COVER_FAILED);
            }
            return false;
        } finally {
            libraryJobs.remove(key);
        }
    }

    public void applySeriesMetadata(
            ComicDocument opened, LibraryFolderScanner.Entry sourceEntry) {
        SeriesMetadata metadata = opened.seriesMetadata();
        String folderKey = sourceEntry == null ? "" : sourceEntry.seriesFolderKey;
        String folderName = sourceEntry == null ? "" : sourceEntry.seriesFolderName;
        if (sourceEntry == null && !metadata.hasSeries()) {
            ReadingProgress current = database.get(opened.key());
            if (current.detectedSeriesKey.startsWith("folder:")) {
                folderKey = current.detectedSeriesKey.substring("folder:".length());
                folderName = current.detectedSeriesName;
            }
        }
        database.applyDetectedSeries(
                opened.key(), metadata.name, metadata.number, folderKey, folderName);
    }

    public String sampleContent(Uri uri, long documentSize) {
        try {
            return ContentFingerprint.sample(context, uri, documentSize);
        } catch (IOException | RuntimeException ignored) {
            // Opening remains the authoritative readability and format validation step.
            return "";
        }
    }
}
