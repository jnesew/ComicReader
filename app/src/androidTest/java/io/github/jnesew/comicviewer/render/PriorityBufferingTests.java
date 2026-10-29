package io.github.jnesew.comicviewer.render;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.os.SystemClock;

import io.github.jnesew.comicviewer.document.ComicDocument;
import io.github.jnesew.comicviewer.model.PageInfo;
import io.github.jnesew.comicviewer.model.ReadingProgress;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises actual bitmap accounting, replacement and cancellation without timing rankings. */
public final class PriorityBufferingTests {
    private static final long MIB = 1024L * 1024L;
    private PriorityBufferingTests() { }

    public static void twoTilePipelineRemainsBoundedAndCancelsQueuedOutput(Instrumentation instrumentation) {
        BlockingDocument document = new BlockingDocument(true, 3);
        SpeculativeTileCache cache = new SpeculativeTileCache(256 * MIB, BufferingPolicy.HIGH,
                () -> true, () -> 0, RenderTuning.scrolling(2, 1), null);
        TileRenderer renderer = new TileRenderer(instrumentation.getTargetContext(), document,
                () -> { }, message -> { throw new AssertionError(message); }, cache);
        try {
            List<TileRenderer.PageRequest> requests = new ArrayList<>();
            for (int i = 0; i < 3; i++) requests.add(new TileRenderer.PageRequest(i,
                    new RectF(0, 0, 512, 512), new RectF(0, 0, 512, 512), i));
            instrumentation.runOnMainSync(() -> renderer.prefetchPages(requests, 9, 16));
            try { check(document.started.await(5, TimeUnit.SECONDS), "Pipeline did not start"); }
            catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
            check(cache.reservedBytes() == 2 * MIB, "Pipeline did not reserve exactly two bounded outputs");
            check(document.calls.get() == 1, "Pipeline added concurrent decoder work");
            instrumentation.runOnMainSync(renderer::cancelPrefetch);
            check(cache.reservedBytes() == MIB, "Cancelling the queued tile did not release its reservation");
            document.proceed.countDown();
            long deadline = SystemClock.elapsedRealtime() + 5_000;
            while (cache.reservedBytes() != 0 && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20);
            check(cache.committedBytes() == 0, "Cancelled pipeline published or leaked optional pixels");
            check(document.calls.get() == 1, "Cancelled queued output reached the decoder");
        } finally {
            document.proceed.countDown(); instrumentation.runOnMainSync(renderer::close); document.close();
        }
    }

    public static void replacementAndReservations() {
        RenderMetrics metrics = new RenderMetrics(); metrics.start(1);
        // A deliberately small deterministic test allowance, independent of the emulator heap.
        SpeculativeTileCache cache = new SpeculativeTileCache(16 * MIB, BufferingPolicy.HIGH,
                () -> true, () -> 0, RenderTuning.reader(64), metrics);
        Object owner = new Object();
        List<Bitmap> bitmaps = new ArrayList<>();
        try {
            cache.plan(owner, Map.of(key(0), 0, key(1), 1, key(2), 1, key(3), 1));
            for (int i = 0; i < 4; i++) {
                Bitmap bitmap = tile(); bitmaps.add(bitmap);
                check(cache.reserve(owner, key(i), MIB), "Initial reservation refused");
                check(cache.publishReserved(owner, key(i), bitmap, MIB), "Initial publication refused");
            }
            cache.plan(owner, Map.of(key(0), 0, key(1), 2, key(2), 2, key(3), 2, key(4), 0));
            check(cache.reserve(owner, key(4), MIB), "Near frame could not replace farther content");
            check(cache.contains(owner, key(0)), "Replacement discarded equally near content");
            check(!cache.contains(owner, key(1)), "LRU farther content was not replaced");
            check(cache.committedBytes() <= cache.limitBytes(), "Reservation exceeded the shared ceiling");
            Bitmap next = tile(); bitmaps.add(next);
            check(cache.publishReserved(owner, key(4), next, MIB), "Reserved output did not publish");
            Bitmap history = tile(); bitmaps.add(history);
            check(!cache.offer(owner, key(8), history, true), "History displaced the prepared frame");
            cache.plan(owner, Map.of(key(0), 0, key(2), 0, key(3), 0, key(4), 0, key(5), 0));
            check(!cache.reserve(owner, key(5), MIB), "Equal-priority content churned at full capacity");
            check(cache.reservedBytes() == 0, "Denied reservation leaked pending bytes");
            check(metrics.finish(2).get("priority_replaced_bytes").longValue() == MIB,
                    "Replacement accounting did not identify displaced content");
        } finally {
            cache.clear(); for (Bitmap bitmap : bitmaps) bitmap.recycle();
        }
    }

    public static void stalePublicationAndPressure() {
        SpeculativeTileCache cache = new SpeculativeTileCache(16 * MIB, BufferingPolicy.HIGH,
                () -> true, () -> 0, RenderTuning.reader(64), null);
        Object first = new Object(), second = new Object(); Bitmap bitmap = tile();
        try {
            cache.plan(first, Map.of(key(0), 0)); cache.plan(second, Map.of(key(1), 0));
            check(cache.reserve(first, key(0), MIB), "First owner reservation refused");
            check(cache.reserve(second, key(1), MIB), "Second owner reservation refused");
            cache.cancelPlan(first);
            check(!cache.publishReserved(first, key(0), bitmap, MIB), "Cancelled plan published");
            check(cache.reservedBytes() == MIB, "Cancellation released another owner's reservation");
            cache.setForeground(false);
            check(!cache.publishReserved(second, key(1), bitmap, MIB), "Background result published");
            check(cache.committedBytes() == 0, "Background publication retained pending output");
            cache.setForeground(true); cache.onMemoryPressure();
            cache.plan(first, Map.of(key(0), 0));
            check(!cache.reserve(first, key(0), MIB), "Pressure cooldown authorized replacement work");
        } finally { cache.clear(); bitmap.recycle(); }
    }

    public static void runningPrefetchBecomesVisible(Instrumentation instrumentation) {
        BlockingDocument document = new BlockingDocument();
        SpeculativeTileCache shared = new SpeculativeTileCache(256 * MIB, BufferingPolicy.HIGH,
                () -> true, () -> 0, RenderTuning.READER, null);
        AtomicReference<String> error = new AtomicReference<>();
        TileRenderer renderer = new TileRenderer(instrumentation.getTargetContext(), document,
                () -> { }, error::set, shared);
        Bitmap output = tile();
        List<TileRenderer.PageRequest> request = List.of(new TileRenderer.PageRequest(0,
                new RectF(0, 0, 512, 512), new RectF(0, 0, 512, 512)));
        try {
            instrumentation.runOnMainSync(() -> renderer.prefetchPages(request, 1, 1));
            try { check(document.started.await(5, TimeUnit.SECONDS), "Prefetch did not enter decoder"); }
            catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
            instrumentation.runOnMainSync(() -> {
                renderer.cancelPrefetch();
                renderer.drawPages(new Canvas(output), request);
            });
            document.proceed.countDown();
            long deadline = SystemClock.elapsedRealtime() + 5_000;
            do {
                instrumentation.runOnMainSync(() -> renderer.drawPages(new Canvas(output), request));
                if (output.getPixel(256, 256) == Color.WHITE) break;
                SystemClock.sleep(20);
            } while (SystemClock.elapsedRealtime() < deadline);
            check(output.getPixel(256, 256) == Color.WHITE, "Visible request never received the running tile");
            check(document.calls.get() == 1, "Navigation decoded the same running tile again");
            check(error.get() == null, "Unexpected render error: " + error.get());
            check(shared.reservedBytes() == 0, "Promotion leaked a reservation");
        } finally {
            document.proceed.countDown();
            instrumentation.runOnMainSync(renderer::close); document.close(); output.recycle();
            check(shared.committedBytes() == 0, "Closing retained output or reservations");
        }
    }

    public static void manualZoomPreparesTheNextVisibleCrop(Instrumentation instrumentation) {
        BlockingDocument document = new BlockingDocument(false, 2);
        RenderMetrics metrics = new RenderMetrics(); metrics.start(SystemClock.elapsedRealtimeNanos());
        SpeculativeTileCache shared = new SpeculativeTileCache(256 * MIB, BufferingPolicy.HIGH,
                () -> true, () -> 0, RenderTuning.READER, metrics);
        AtomicReference<ComicCanvasView> view = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();
        TileRenderer renderer = new TileRenderer(instrumentation.getTargetContext(), document,
                () -> { }, error::set, shared);
        Bitmap output = tile();
        try {
            instrumentation.runOnMainSync(() -> {
                ComicCanvasView canvas = new ComicCanvasView(instrumentation.getTargetContext());
                canvas.setRenderExperiment(RenderTuning.READER, metrics);
                canvas.setBufferingLevel(BufferingPolicy.HIGH);
                ReadingProgress progress = new ReadingProgress(); progress.uri = document.key();
                progress.readingMode = ComicCanvasView.SINGLE; progress.zoomMode = ComicCanvasView.MANUAL; progress.zoom = 4;
                canvas.setDocument(renderer, document.pages(), progress); canvas.layout(0, 0, 512, 512);
                view.set(canvas); canvas.draw(new Canvas(output));
            });
            long deadline = SystemClock.elapsedRealtime() + 5_000;
            do {
                if (shared.snapshot(renderer).keySet().stream().filter(key -> key.page() == 1).count() == 2) break;
                SystemClock.sleep(20);
            } while (SystemClock.elapsedRealtime() < deadline);
            check(shared.snapshot(renderer).keySet().stream().filter(key -> key.page() == 1).count() == 2,
                    "Next manual-zoom frame was not prepared");
            instrumentation.runOnMainSync(() -> {
                view.get().showPage(1, 0); view.get().draw(new Canvas(output));
                check(metrics.missingFraction() == 0, "Prefetch prepared a different source crop than the next frame");
            });
            check(error.get() == null, "Unexpected render error: " + error.get());
        } finally {
            instrumentation.runOnMainSync(() -> { if (view.get() != null) view.get().clearDocument(); renderer.close(); });
            document.close(); output.recycle();
            check(shared.committedBytes() == 0, "Closing the zoomed view retained reservations");
        }
    }

    public static void replacedPrefetchWaitsForNearerUseOrNewGeneration(Instrumentation instrumentation) {
        BlockingDocument document = new BlockingDocument(false, 1);
        SpeculativeTileCache shared = new SpeculativeTileCache(256 * MIB, BufferingPolicy.HIGH,
                () -> true, () -> 0, RenderTuning.READER, null);
        TileRenderer renderer = new TileRenderer(instrumentation.getTargetContext(), document,
                () -> { }, message -> { throw new AssertionError(message); }, shared);
        List<Bitmap> promoted = new ArrayList<>();
        RectF frame = new RectF(0, 0, 512, 512);
        List<TileRenderer.PageRequest> farther = List.of(new TileRenderer.PageRequest(0, frame, frame, 1));
        List<TileRenderer.PageRequest> nearer = List.of(new TileRenderer.PageRequest(0, frame, frame, 0));
        try {
            instrumentation.runOnMainSync(() -> renderer.prefetchPages(farther, 7, 1));
            awaitBuffered(shared, renderer);
            promoted.add(shared.take(renderer, key(0)));
            SystemClock.sleep(150);
            check(document.calls.get() == 1, "A displaced optional tile was decoded repeatedly in one window");
            instrumentation.runOnMainSync(() -> renderer.prefetchPages(nearer, 7, 1));
            awaitBuffered(shared, renderer);
            check(document.calls.get() == 2, "A strictly nearer frame could not prepare its tile");
            promoted.add(shared.take(renderer, key(0)));
            SystemClock.sleep(150);
            check(document.calls.get() == 2, "Near-frame replacement started a refill loop");
            instrumentation.runOnMainSync(() -> renderer.prefetchPages(nearer, 8, 1));
            awaitBuffered(shared, renderer);
            check(document.calls.get() == 3, "A new generation could not retry useful output");
        } finally {
            instrumentation.runOnMainSync(renderer::close); document.close();
            for (Bitmap bitmap : promoted) if (bitmap != null) bitmap.recycle();
            check(shared.committedBytes() == 0, "Closing the generation test leaked pending output");
        }
    }

    private static void awaitBuffered(SpeculativeTileCache cache, TileRenderer renderer) {
        long deadline = SystemClock.elapsedRealtime() + 5_000;
        while (!cache.contains(renderer, key(0)) && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20);
        check(cache.contains(renderer, key(0)), "Optional tile did not become ready");
    }

    private static Bitmap tile() { return Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888); }
    private static TileKey key(int page) { return new TileKey(page, 1, 1, 0, 0, 512, 512); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    private static final class BlockingDocument implements ComicDocument {
        final CountDownLatch started = new CountDownLatch(1), proceed = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
        final List<PageInfo> pages = new ArrayList<>();
        final boolean blocking;
        BlockingDocument() { this(true, 1); }
        BlockingDocument(boolean blocking, int count) {
            this.blocking = blocking;
            for (int i = 0; i < count; i++) pages.add(new PageInfo("page-" + i, 512, 512));
        }
        @Override public boolean supportsRenderedTiles() { return true; }
        @Override public Bitmap renderTile(int page, Rect source, float scale) throws IOException {
            calls.incrementAndGet(); started.countDown();
            try { if (blocking && !proceed.await(5, TimeUnit.SECONDS)) throw new IOException("Test decoder timed out"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
            Bitmap bitmap = Bitmap.createBitmap((int) Math.ceil(source.width() * scale),
                    (int) Math.ceil(source.height() * scale), Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.WHITE); return bitmap;
        }
        @Override public Uri uri() { return Uri.parse("test:priority"); }
        @Override public String key() { return "test:priority"; }
        @Override public String title() { return "Priority fixture"; }
        @Override public long documentSize() { return 0; }
        @Override public long documentModified() { return 0; }
        @Override public int count() { return pages.size(); }
        @Override public int indexedPages() { return pages.size(); }
        @Override public boolean isIndexComplete() { return true; }
        @Override public boolean suggestedRightToLeft() { return false; }
        @Override public List<PageInfo> pages() { return pages; }
        @Override public List<PageInfo> snapshotPages() { return pages; }
        @Override public PageInfo page(int index) { return pages.get(index); }
        @Override public void buildPageIndex(Context context, ProgressCallback progress, IndexCallback callback) { callback.onComplete(pages); }
        @Override public void close() { }
    }
}
