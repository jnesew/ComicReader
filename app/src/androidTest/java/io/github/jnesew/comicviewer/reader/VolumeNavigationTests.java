package io.github.jnesew.comicviewer.reader;

import android.app.Instrumentation;
import android.view.KeyEvent;
import io.github.jnesew.comicviewer.data.LibraryDatabase;
import io.github.jnesew.comicviewer.data.ReaderPreferences;
import io.github.jnesew.comicviewer.document.ComicDocument;
import io.github.jnesew.comicviewer.model.ReadingProgress;
import io.github.jnesew.comicviewer.render.ComicCanvasView;
import io.github.jnesew.comicviewer.ui.ReaderScreen;
import java.util.concurrent.atomic.AtomicReference;

/** Public key dispatch through the real reader controller and session, using a provider-backed comic. */
public final class VolumeNavigationTests {
    public static void run(Instrumentation instrumentation, LibraryDatabase db,
            ReaderPreferences preferences, ComicDocument document) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<ReaderSession> owner = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            ReaderSession session = new ReaderSession(instrumentation.getTargetContext(), db, preferences, null,
                    new ReaderSession.Host() {
                        @Override public boolean isFinishing() { return false; }
                        @Override public void onReaderActivated() { }
                        @Override public void onLibraryChanged() { }
                        @Override public void onKeepScreenOnChanged() { }
                        @Override public void onOpening(String message) { }
                        @Override public void onOpeningMessage(String message) { }
                        @Override public void onLoadingFinished() { }
                        @Override public void onError(String title, String message) { throw new AssertionError(message); }
                    });
            owner.set(session);
            try {
                ReaderController controller = new ReaderController(session, new ReaderController.Host() {
                    @Override public void onHome() { }
                    @Override public void onComicEditRequested(ReadingProgress progress) { }
                    @Override public void onChromeVisibilityChanged(boolean visible) { }
                    @Override public void onKeepScreenOnChanged() { }
                });
                ReaderScreen reader = new ReaderScreen(instrumentation.getTargetContext(), preferences, controller);
                session.attach(reader); reader.canvas.setListener(controller);
                reader.canvas.layout(0, 0, 800, 600);
                ReadingProgress saved = db.get(document.key());
                saved.page = 0; saved.scrollRatio = 0; saved.seriesId = 0;
                saved.zoom = 1; saved.readingMode = ComicCanvasView.CONTINUOUS;
                session.activateArchive(document, saved, ReaderSession.OpenPosition.REMEMBERED);
                preferences.setVolumeNavigation(true);
                double start = reader.canvas.continuousScrollPosition();
                check(controller.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN)), "Volume down escaped");
                double moved = reader.canvas.continuousScrollPosition();
                check(Math.abs(moved - start - 540d) < .01d, "Volume down jumped an image");
                check(controller.dispatchKeyEvent(new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN, 1)), "Repeat escaped");
                check(controller.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_DOWN)), "Key up escaped");
                check(reader.canvas.continuousScrollPosition() == moved, "Repeat or release navigated");
                controller.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP));
                check(Math.abs(reader.canvas.continuousScrollPosition() - start) < .01d, "Volume up did not reverse the step");
                reader.canvas.setReadingMode(ComicCanvasView.SINGLE);
                reader.canvas.showPage(0, 0);
                controller.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN));
                check(reader.canvas.page() == 1, "Paged mode stopped turning pages");
                preferences.setVolumeNavigation(false);
                check(!controller.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN)), "Disabled volume navigation consumed key");
            } catch (Throwable error) { failure.set(error); }
            finally { if (session.reader != null) session.close(); }
        });
        try { if (owner.get() != null) owner.get().awaitStopped(); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
