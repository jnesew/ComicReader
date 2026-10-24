package io.github.jnesew.comicviewer.render;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.Uri;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;

import io.github.jnesew.comicviewer.document.ComicDocument;
import io.github.jnesew.comicviewer.model.PageInfo;
import io.github.jnesew.comicviewer.model.ReadingProgress;
import io.github.jnesew.comicviewer.util.PageLayoutEngine;
import io.github.jnesew.comicviewer.util.Ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises the actual View and public reader commands, without reflection into layout state. */
public final class ReaderGeometryTests {
    private ReaderGeometryTests() { }

    public static void actualSizeLabels(Instrumentation instrumentation) {
        for (java.util.Locale locale : new java.util.Locale[]{java.util.Locale.ENGLISH, java.util.Locale.forLanguageTag("fi")}) {
            android.content.res.Configuration configuration = new android.content.res.Configuration(
                    instrumentation.getTargetContext().getResources().getConfiguration());
            configuration.setLocale(locale);
            String label = instrumentation.getTargetContext().createConfigurationContext(configuration)
                    .getString(io.github.jnesew.comicviewer.R.string.reader_actual_size);
            check(label.endsWith("(100%)"), "Actual size label has an escaped percent: " + label);
        }
    }

    public static void zoomFromFitPage(Instrumentation instrumentation) {
        onMain(instrumentation, () -> {
            for (int height : new int[]{5000, 10000, 262144}) {
                try (Fixture f = new Fixture(instrumentation, List.of(new PageInfo("strip", 1000, height)))) {
                    f.view.fitPage();
                    f.view.multiplyZoom(1.12f);
                    near(Math.max(.1f, 600f / height / .8f * 1.12f), f.view.zoom(), .0001f,
                            "Zoom button ignored Fit page scale or manual zoom bounds");
                }
            }
        });
    }

    public static void actualSizeRestoresConsistently(Instrumentation instrumentation) {
        onMain(instrumentation, () -> {
            try (Fixture f = new Fixture(instrumentation, List.of(new PageInfo("tiny", 10, 10)))) {
                f.view.actualSize();
                // Rotation/re-layout must preserve the same bounded zoom selected by Actual size.
                Bitmap before = f.draw();
                f.view.layout(0, 0, 801, 600);
                f.view.layout(0, 0, 800, 600);
                Bitmap after = f.draw();
                try {
                    check(contentBounds(before).equals(contentBounds(after)), "Actual size changed after relayout");
                } finally { before.recycle(); after.recycle(); }
            }
        });
    }

    public static void continuousZoomKeepsFocus(Instrumentation instrumentation) {
        onMain(instrumentation, () -> {
            List<PageInfo> pages = new ArrayList<>();
            for (int i = 0; i < 60; i++) pages.add(new PageInfo("page-" + i, 1000, i % 2 == 0 ? 1000 : 2000));
            try (Fixture f = new Fixture(instrumentation, pages)) {
                ReadingProgress progress = new ReadingProgress();
                progress.uri = "test:geometry";
                progress.readingMode = ComicCanvasView.CONTINUOUS;
                f.view.setDocument(f.renderer, pages, progress);
                f.view.showPage(2, .5f);
                PageLayoutEngine old = continuousLayout(f.context, pages, 1f);
                double focusY = old.positionFor(2, .5f) + 300f;
                int focusPage = old.pageAt(focusY);
                float focusRatio = old.pageRatio(focusPage, focusY);
                f.view.multiplyZoom(2f);
                PageLayoutEngine enlarged = continuousLayout(f.context, pages, 2f);
                double expectedScroll = enlarged.positionFor(focusPage, focusRatio) - 300f;
                int expectedPage = enlarged.pageAt(expectedScroll + 1f);
                check(f.view.page() == expectedPage, "Continuous zoom changed focused page");
                near(enlarged.pageRatio(expectedPage, expectedScroll), f.view.pageRatio(),
                        1f / enlarged.height(expectedPage), "Continuous zoom moved focused content");
            }
        });
    }

    public static void longCollectionScrollsPrecisely(Instrumentation instrumentation) {
        AtomicReference<Fixture> fixture = new AtomicReference<>();
        onMain(instrumentation, () -> {
            List<PageInfo> pages = new ArrayList<>();
            for (int i = 0; i < 20_000; i++) pages.add(new PageInfo("strip-" + i, 1024, 262144));
            Fixture f = new Fixture(instrumentation, pages); fixture.set(f);
            ReadingProgress progress = new ReadingProgress();
            progress.uri = "test:geometry"; progress.readingMode = ComicCanvasView.CONTINUOUS; progress.zoom = 5f;
            f.view.setDocument(f.renderer, pages, progress);
            f.view.showPage(19_999, .5f);
            float before = f.view.pageRatio();
            wheel(f.view, -1f);
            check(f.view.page() == 19_999 && f.view.pageRatio() > before,
                    "Small scroll was lost near the end of a large collection");
            long now = SystemClock.uptimeMillis();
            for (int i = 0; i < 3; i++) {
                MotionEvent event = MotionEvent.obtain(now, now + i * 30,
                        new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP}[i],
                        400, new int[]{500, 200, 100}[i], 0);
                try { f.view.onTouchEvent(event); } finally { event.recycle(); }
            }
        });
        try {
            SystemClock.sleep(60);
            onMain(instrumentation, () -> {
                Fixture f = fixture.get(); f.view.computeScroll();
                check(f.view.page() == 19_999 && f.view.pageRatio() > .5f && f.view.pageRatio() < .55f,
                        "Fling overflowed the document's global coordinate");
            });
        } finally { if (fixture.get() != null) onMain(instrumentation, fixture.get()::close); }
    }

    static void wheel(ComicCanvasView view, float value) {
        MotionEvent.PointerProperties pointer = new MotionEvent.PointerProperties();
        pointer.id = 0; pointer.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords coordinates = new MotionEvent.PointerCoords();
        coordinates.x = 400; coordinates.y = 300; coordinates.setAxisValue(MotionEvent.AXIS_VSCROLL, value);
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1,
                new MotionEvent.PointerProperties[]{pointer}, new MotionEvent.PointerCoords[]{coordinates},
                0, 0, 1, 1, 0, 0, InputDevice.SOURCE_MOUSE, 0);
        try { view.onGenericMotionEvent(event); } finally { event.recycle(); }
    }

    static void drag(ComicCanvasView view, float dx, float dy) {
        long now = SystemClock.uptimeMillis();
        for (int i = 0; i < 3; i++) {
            MotionEvent event = MotionEvent.obtain(now, now + i * 100,
                    new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_CANCEL}[i],
                    400 + (i == 0 ? 0 : dx), 300 + (i == 0 ? 0 : dy), 0);
            try { view.onTouchEvent(event); } finally { event.recycle(); }
        }
    }

    private static PageLayoutEngine continuousLayout(Context context, List<PageInfo> pages, float zoom) {
        PageLayoutEngine result = new PageLayoutEngine();
        float[] before = new float[pages.size()];
        before[0] = Ui.dp(context, 58);
        result.calculate(pages, 800 - Ui.dp(context, 8) * 2, zoom, Ui.dp(context, 8),
                before, Ui.dp(context, 58));
        return result;
    }

    private static Rect contentBounds(Bitmap bitmap) {
        Rect bounds = new Rect();
        for (int y = 0; y < bitmap.getHeight(); y++) {
            for (int x = 0; x < bitmap.getWidth(); x++) {
                if (bitmap.getPixel(x, y) != Color.BLACK) bounds.union(x, y, x + 1, y + 1);
            }
        }
        return bounds;
    }

    static void onMain(Instrumentation instrumentation, Runnable runnable) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { runnable.run(); } catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void near(float expected, float actual, float tolerance, String message) {
        check(Math.abs(expected - actual) <= tolerance, message + ": expected " + expected + ", got " + actual);
    }

    private static final class Fixture implements AutoCloseable {
        final Context context;
        final ComicCanvasView view;
        final TileRenderer renderer;
        Fixture(Instrumentation instrumentation, List<PageInfo> pages) {
            context = instrumentation.getTargetContext();
            view = new ComicCanvasView(context);
            renderer = new TileRenderer(context, new GeometryDocument(pages), () -> { },
                    message -> { throw new AssertionError(message); }, null);
            ReadingProgress progress = new ReadingProgress();
            progress.uri = "test:geometry";
            view.layout(0, 0, 800, 600);
            view.setDocument(renderer, pages, progress);
        }
        Bitmap draw() {
            Bitmap output = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888);
            view.draw(new Canvas(output));
            return output;
        }
        @Override public void close() { view.clearDocument(); renderer.close(); }
    }

    private static final class GeometryDocument implements ComicDocument {
        final List<PageInfo> pages;
        GeometryDocument(List<PageInfo> pages) { this.pages = pages; }
        @Override public Uri uri() { return Uri.parse(key()); }
        @Override public String key() { return "test:geometry"; }
        @Override public String title() { return "Geometry fixture"; }
        @Override public long documentSize() { return 0; }
        @Override public long documentModified() { return 0; }
        @Override public int count() { return pages.size(); }
        @Override public int indexedPages() { return count(); }
        @Override public boolean isIndexComplete() { return true; }
        @Override public boolean suggestedRightToLeft() { return false; }
        @Override public List<PageInfo> pages() { return pages; }
        @Override public List<PageInfo> snapshotPages() { return pages; }
        @Override public PageInfo page(int index) { return pages.get(index); }
        @Override public boolean supportsRenderedTiles() { return true; }
        @Override public Bitmap renderTile(int index, Rect source, float scale) {
            Bitmap result = Bitmap.createBitmap(Math.max(1, Math.round(source.width() * scale)),
                    Math.max(1, Math.round(source.height() * scale)), Bitmap.Config.ARGB_8888);
            result.eraseColor(Color.WHITE);
            return result;
        }
        @Override public void buildPageIndex(Context c, ProgressCallback p, IndexCallback callback) { callback.onComplete(pages); }
        @Override public void close() { }
    }
}
