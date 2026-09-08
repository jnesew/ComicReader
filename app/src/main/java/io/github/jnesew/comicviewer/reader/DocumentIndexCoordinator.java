package io.github.jnesew.comicviewer.reader;

import io.github.jnesew.comicviewer.data.CoverStore;
import io.github.jnesew.comicviewer.data.LibraryDatabase;
import io.github.jnesew.comicviewer.document.ComicDocument;
import io.github.jnesew.comicviewer.model.PageInfo;
import io.github.jnesew.comicviewer.model.ReadingProgress;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Indexes borrowed resources; only the session may dispose them. */
final class DocumentIndexCoordinator {
    private final ReaderSession session;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "comic-index-worker");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });

    DocumentIndexCoordinator(ReaderSession session) { this.session = session; }
    void close() { worker.shutdownNow(); }
    void awaitStopped() throws InterruptedException {
        worker.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
    }

    void startBackgroundIndex(DocumentResources resource, int generation) {
        start(resource, generation, true);
    }

    void startContinuousBackgroundIndex(DocumentResources resource, int generation) {
        start(resource, generation, false);
    }

    private boolean current(DocumentResources resource, int generation) {
        return !session.destroyed && generation == session.openGeneration && !resource.isClosed()
                && (session.active == resource ||
                    session.continuous.continuousResources.get(resource.document.key()) == resource);
    }

    private void start(DocumentResources resource, int generation, boolean ensureCover) {
        if (resource.document.isUnavailable()) return;
        worker.execute(() -> {
            ComicDocument document = resource.document;
            try {
                if (!current(resource, generation)) return;
                if (ensureCover) ensureCover(resource, generation);
                if (document.isIndexComplete() || !current(resource, generation)) return;
                document.buildPageIndex(session.context, null, new ComicDocument.IndexCallback() {
                    @Override public boolean isCancelled() {
                        return Thread.currentThread().isInterrupted() || !current(resource, generation);
                    }
                    @Override public void onProgress(int indexedPages, int pageCount) {
                        synchronized (resource) {
                            if (isCancelled()) return;
                            session.database.updateArchiveState(document.key(), pageCount, indexedPages,
                                    false, document.documentSize(), document.documentModified());
                        }
                    }
                    @Override public void onPagesUpdated() {
                        session.mainHandler.post(() -> refresh(resource, generation));
                    }
                    @Override public void onComplete(List<PageInfo> pages) {
                        synchronized (resource) {
                            if (isCancelled()) return;
                            session.database.replacePageIndex(document.key(), pages);
                        }
                        int count = pages.size();
                        session.mainHandler.post(() -> {
                            if (!current(resource, generation)) return;
                            resource.progress.indexedPages = count;
                            resource.progress.indexComplete = true;
                            refresh(resource, generation);
                            session.host.onLibraryChanged();
                        });
                    }
                });
            } catch (IOException | RuntimeException ignored) {
                // Closing/evicting a resource cancels its borrowed index work.
            }
        });
    }

    private void refresh(DocumentResources resource, int generation) {
        if (!current(resource, generation) || !session.readerActive) return;
        if (session.reader.canvas.isContinuous()) {
            session.continuous.refreshContinuousDocuments(session.document().key(),
                    session.reader.canvas.page(), session.reader.canvas.pageRatio());
        } else if (session.active == resource) {
            session.reader.canvas.onPageInfoChanged();
        }
    }

    private void ensureCover(DocumentResources resource, int generation) {
        String key = resource.document.key();
        if (!session.imports.claimJob(key)) return;
        try {
            ReadingProgress item = session.database.get(key);
            if (CoverStore.exists(item.coverPath) || !current(resource, generation)) return;
            String path = CoverStore.ensureCover(session.context, resource.document);
            synchronized (resource) {
                if (!current(resource, generation)) return;
                session.database.setCover(key, path, LibraryDatabase.COVER_READY);
            }
            session.mainHandler.post(() -> {
                if (!current(resource, generation)) return;
                resource.progress.coverPath = path;
                resource.progress.coverState = LibraryDatabase.COVER_READY;
                session.host.onLibraryChanged();
            });
        } catch (IOException | RuntimeException error) {
            synchronized (resource) {
                if (current(resource, generation)) {
                    session.database.setCover(key, "", LibraryDatabase.COVER_FAILED);
                }
            }
        } finally {
            session.imports.releaseJob(key);
        }
    }
}
