package io.github.jnesew.comicviewer.reader;

import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Context;
import android.net.Uri;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import io.github.jnesew.comicviewer.R;
import io.github.jnesew.comicviewer.data.LibraryDatabase;
import io.github.jnesew.comicviewer.data.ReaderPreferences;
import io.github.jnesew.comicviewer.document.ComicDocument;
import io.github.jnesew.comicviewer.model.ComicMetadata;
import io.github.jnesew.comicviewer.model.PageInfo;
import io.github.jnesew.comicviewer.model.ReadingProgress;
import io.github.jnesew.comicviewer.render.ComicCanvasView;
import io.github.jnesew.comicviewer.render.TileRenderer;
import io.github.jnesew.comicviewer.ui.ReaderScreen;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Details follow the active continuous issue and remain accessible at narrow widths. */
public final class ReaderDetailsTests {
    public static void activeIssue(Instrumentation instrumentation, LibraryDatabase db) {
        String prefsName = "reader-details-tests";
        Context target = instrumentation.getTargetContext(); target.deleteSharedPreferences(prefsName);
        Context context = new ContextThemeWrapper(target, R.style.Theme_ComicViewer) {
            @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
                return target.getSharedPreferences(prefsName, mode);
            }
        };
        ReaderPreferences prefs = new ReaderPreferences(context);
        for (int n = 1; n <= 2; n++) {
            db.ensureImported("info:" + n, "Issue file " + n, 100, 200);
            db.applyDetectedSeries("info:" + n, "Info series", Integer.toString(n), "", "");
        }
        AtomicReference<ReaderSession> owner = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            ReaderSession session = new ReaderSession(context, db, prefs, null, new ReaderSession.Host() {
                public boolean isFinishing() { return false; }
                public void onReaderActivated() { }
                public void onLibraryChanged() { }
                public void onKeepScreenOnChanged() { }
                public void onOpening(String message) { }
                public void onOpeningMessage(String message) { }
                public void onLoadingFinished() { }
                public void onError(String title, String message) { throw new AssertionError(message); }
            });
            owner.set(session);
            try {
                ReaderController controller = new ReaderController(session, new ReaderController.Host() {
                    public void onHome() { }
                    public void onComicEditRequested(ReadingProgress issue) { }
                    public void onChromeVisibilityChanged(boolean visible) { }
                    public void onKeepScreenOnChanged() { }
                });
                ReaderScreen reader = new ReaderScreen(context, prefs, controller); session.attach(reader);
                reader.canvas.setListener(controller);
                ReadingProgress first = db.get("info:1"); long series = first.seriesId; first.seriesId = 0;
                first.readingMode = ComicCanvasView.CONTINUOUS;
                session.activateArchive(new Document("info:1", "First writer", "First synopsis"), first, ReaderSession.OpenPosition.REMEMBERED);
                first.seriesId = series;
                Document second = new Document("info:2", "Second writer", "Second synopsis");
                var next = new DocumentResources(second, new TileRenderer(context, second, () -> { }, message -> { }, session.renderCoordinator), db.get("info:2"));
                session.continuous.continuousResources.put(second.key(), next);
                measure(reader, context, 420);
                check(reader.isIssueInfoVisible(), "Reader info missing when space is available");
                AlertDialog dialog = controller.createIssueDetails(); dialog.create();
                check(text(dialog.getWindow().getDecorView(), "Writer: First writer") != null, "Reader did not show loaded credits");
                check(text(dialog.getWindow().getDecorView(), "First synopsis") != null, "Reader synopsis missing"); dialog.dismiss();
                long openedBefore = db.get("info:1").lastOpened;
                check(session.continuous.switchContinuousIssue(second.key()), "Could not switch active continuous issue");
                dialog = controller.createIssueDetails(); dialog.create();
                View content = dialog.getWindow().getDecorView();
                check(text(content, "Writer: Second writer") != null && text(content, "Writer: First writer") == null,
                        "Reader info retained the previous issue's credits");
                check(text(content, "Second synopsis") != null && text(content, "Issue 2: Chapter 2") != null,
                        "Reader info title and synopsis did not follow the active issue");
                check(db.get("info:2").lastOpened == 0 && !db.get("info:2").read, "Viewing details advanced the second issue");
                check(db.get("info:1").lastOpened >= openedBefore, "Switch lost the saved prior issue"); dialog.dismiss();
                measure(reader, context, 320);
                check(!reader.isIssueInfoVisible(), "Info crowded a narrow reader toolbar");
                check(controller.createIssueDetails() != null, "Narrow layout lost the details action");
                session.reader.setIssueDetailsAvailable(false); measure(reader, context, 420);
                check(!reader.isIssueInfoVisible(), "Empty metadata retained a toolbar icon");
            } catch (Throwable error) { failure.set(error); }
            finally { session.close(); }
        });
        try { owner.get().awaitStopped(); } catch (InterruptedException error) { throw new AssertionError(error); }
        target.deleteSharedPreferences(prefsName);
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
    private static void measure(ReaderScreen reader, Context context, int widthDp) {
        int width = io.github.jnesew.comicviewer.util.Ui.dp(context, widthDp);
        reader.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY));
        reader.layout(0, 0, width, 1600);
    }
    private static TextView text(View view, String value) {
        if (view instanceof TextView text && value.contentEquals(text.getText())) return text;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            TextView found = text(group.getChildAt(i), value); if (found != null) return found;
        }
        return null;
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static final class Document implements ComicDocument {
        private final String key;
        private final ComicMetadata metadata;
        private final List<PageInfo> pages = List.of(new PageInfo("page", 800, 1200));
        Document(String key, String writer, String summary) {
            this.key = key; metadata = new ComicMetadata(Map.of("Title", "Chapter " + key.substring(key.length() - 1),
                    "Writer", writer, "Summary", summary), List.of());
        }
        public Uri uri() { return Uri.parse(key); }
        public String key() { return key; }
        public String title() { return metadata.title; }
        public long documentSize() { return 100; }
        public long documentModified() { return 200; }
        public int count() { return 1; }
        public int indexedPages() { return 1; }
        public boolean isIndexComplete() { return true; }
        public boolean suggestedRightToLeft() { return false; }
        public ComicMetadata metadata() { return metadata; }
        public List<PageInfo> pages() { return pages; }
        public List<PageInfo> snapshotPages() { return pages; }
        public PageInfo page(int page) { return pages.get(page); }
        public void buildPageIndex(Context context, ProgressCallback progress, IndexCallback callback) { callback.onComplete(pages); }
        public void close() { }
    }
}
