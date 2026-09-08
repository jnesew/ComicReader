package io.github.jnesew.comicviewer.reader;
import android.os.Handler;
import android.os.Looper;

/** Main-thread debounced reading state capture; placeholders are never persisted. */
final class ReadingProgressController {
    private final ReaderSession session;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable deferredSave = this::saveNow;
    ReadingProgressController(ReaderSession session) { this.session = session; }
    void scheduleSave() {
        handler.removeCallbacks(deferredSave);
        handler.postDelayed(deferredSave, 400L);
    }

    void saveNow() {
        handler.removeCallbacks(deferredSave);
        DocumentResources resource = session.active;
        if (resource == null || resource.isClosed()) return;
        var canvas = session.reader.canvas;
        if (capture(resource.document, resource.progress,
                new Position(canvas.pageEnd(), canvas.pageRatio(), canvas.zoomMode(), canvas.zoom(),
                        canvas.zoomGesturesLocked(), canvas.readingMode()), System.currentTimeMillis())) {
            session.database.saveReadingProgress(resource.progress);
        }
    }

    record Position(int page, float ratio, String zoomMode, float zoom,
            boolean zoomLocked, String readingMode) { }

    static boolean capture(io.github.jnesew.comicviewer.document.ComicDocument document,
            io.github.jnesew.comicviewer.model.ReadingProgress progress, Position position, long now) {
        if (document.isUnavailable()) return false;
        progress.uri = document.key();
        progress.page = position.page();
        progress.pageCount = document.count();
        progress.scrollRatio = position.ratio();
        progress.zoomMode = position.zoomMode();
        progress.zoom = position.zoom();
        progress.zoomGesturesLocked = position.zoomLocked();
        progress.readingMode = position.readingMode();
        progress.lastOpened = now;
        return true;
    }
}
