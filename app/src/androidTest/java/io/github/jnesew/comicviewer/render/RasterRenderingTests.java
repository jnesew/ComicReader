package io.github.jnesew.comicviewer.render;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.net.Uri;
import android.os.SystemClock;

import io.github.jnesew.comicviewer.document.ComicDocument;
import io.github.jnesew.comicviewer.model.PageInfo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Real Android decoder/canvas coverage for the reported 1988 x 3056 raster-page shape. */
public final class RasterRenderingTests {
    private static final int WIDTH = 1988;
    private static final int HEIGHT = 3056;
    private static final float SCALE = 1080f / WIDTH;
    private static final float DISPLAY_HEIGHT = HEIGHT * SCALE;
    private static final RectF FIRST = new RectF(2, 2, 1078, DISPLAY_HEIGHT - 2);
    private static final RectF SECOND = new RectF(2, DISPLAY_HEIGHT + 14, 1078, 2098);

    private RasterRenderingTests() { }

    public static void backwardTurn(Instrumentation instrumentation) {
        try (Fixture fixture = new Fixture(instrumentation)) {
            fixture.awaitReady(single(0), FIRST);
            fixture.awaitReady(single(1), FIRST);
            check(fixture.shared.snapshot(fixture.renderer).keySet().stream()
                    .anyMatch(key -> key.page() == 0), "Displaced page was discarded");
            fixture.draw(single(0));
            check(fixture.white(FIRST), "Backward turn exposed dark placeholders");
            check(fixture.shared.usedBytes() <= fixture.shared.limitBytes(), "Buffer exceeded cap");
            instrumentation.runOnMainSync(fixture.renderer::trimMemory);
            check(fixture.shared.snapshot(fixture.renderer).isEmpty(), "Trim retained pixels");
        }
    }

    public static void samplingTransitions(Instrumentation instrumentation) {
        // A split viewport reduces the full first page to sample 2. A single-page viewport
        // chooses sample 1. Both changes must draw cached pixels before new tiles arrive.
        try (Fixture fixture = new Fixture(instrumentation)) {
            fixture.awaitReady(split(), FIRST, SECOND);
            fixture.draw(single(0));
            check(fixture.white(FIRST), "Coarser-to-finer transition exposed placeholders");
        }
        try (Fixture fixture = new Fixture(instrumentation)) {
            fixture.awaitReady(single(0), FIRST);
            fixture.draw(split());
            check(fixture.white(FIRST), "Finer-to-coarser transition exposed placeholders");
        }
    }

    private static List<TileRenderer.PageRequest> single(int page) {
        return List.of(request(page, 0f));
    }

    private static List<TileRenderer.PageRequest> split() {
        return List.of(request(0, 0f), request(1, DISPLAY_HEIGHT + 12f));
    }

    private static TileRenderer.PageRequest request(int page, float top) {
        return new TileRenderer.PageRequest(page,
                new RectF(0, top, 1080, top + DISPLAY_HEIGHT), new RectF(0, 0, 1080, 2100));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Fixture implements AutoCloseable {
        final Instrumentation instrumentation;
        final SpeculativeTileCache shared = new SpeculativeTileCache(
                512L * 1024L * 1024L, BufferingPolicy.HIGH, () -> true, () -> 0L);
        final AtomicReference<String> error = new AtomicReference<>();
        final Bitmap output = Bitmap.createBitmap(1080, 2100, Bitmap.Config.ARGB_8888);
        final RasterDocument document = new RasterDocument();
        final TileRenderer renderer;

        Fixture(Instrumentation instrumentation) {
            this.instrumentation = instrumentation;
            renderer = new TileRenderer(instrumentation.getTargetContext(), document,
                    () -> { }, error::set, shared, 32 * 1024);
        }

        void draw(List<TileRenderer.PageRequest> requests) {
            instrumentation.runOnMainSync(() -> {
                output.eraseColor(Color.BLACK);
                renderer.drawPages(new Canvas(output), requests);
            });
            check(error.get() == null, "Decoder error: " + error.get());
        }

        void awaitReady(List<TileRenderer.PageRequest> requests, RectF... areas) {
            long deadline = SystemClock.elapsedRealtime() + 10_000L;
            do {
                draw(requests);
                boolean ready = true;
                for (RectF area : areas) ready &= white(area);
                if (ready) return;
                SystemClock.sleep(20L);
            } while (SystemClock.elapsedRealtime() < deadline);
            throw new AssertionError("Raster tiles did not become ready");
        }

        boolean white(RectF area) {
            for (int y = (int) area.top; y < area.bottom; y += 19) {
                for (int x = (int) area.left; x < area.right; x += 19) {
                    if (output.getPixel(x, y) != Color.WHITE) return false;
                }
            }
            return true;
        }

        @Override public void close() {
            instrumentation.runOnMainSync(renderer::close);
            check(shared.snapshot(renderer).isEmpty(), "Closed renderer retained pixels");
            document.close();
            output.recycle();
        }
    }

    /** Same image stream surface as a CBZ page, without a private comic fixture. */
    private static final class RasterDocument implements ComicDocument {
        private final List<PageInfo> pages = List.of(
                new PageInfo("page-0.png", WIDTH, HEIGHT),
                new PageInfo("page-1.png", WIDTH, HEIGHT));
        private final byte[] image;

        RasterDocument() {
            Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
            try {
                bitmap.eraseColor(Color.WHITE);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes), "PNG encode failed");
                image = bytes.toByteArray();
            } finally {
                bitmap.recycle();
            }
        }

        @Override public Uri uri() { return Uri.parse("test:raster-regression"); }
        @Override public String key() { return "test:raster-regression"; }
        @Override public String title() { return "Raster regression"; }
        @Override public long documentSize() { return image.length; }
        @Override public long documentModified() { return 0L; }
        @Override public int count() { return pages.size(); }
        @Override public int indexedPages() { return pages.size(); }
        @Override public boolean isIndexComplete() { return true; }
        @Override public boolean suggestedRightToLeft() { return false; }
        @Override public List<PageInfo> pages() { return pages; }
        @Override public List<PageInfo> snapshotPages() { return pages; }
        @Override public PageInfo page(int index) { return pages.get(index); }
        @Override public void buildPageIndex(Context context, ProgressCallback progress,
                IndexCallback callback) { callback.onComplete(pages); }
        @Override public InputStream openPage(int index) { return new ByteArrayInputStream(image); }
        @Override public void extractPage(int index, File destination) throws IOException {
            Files.write(destination.toPath(), image);
        }
        @Override public void close() { }
    }
}
