package io.github.jnesew.comicviewer.render;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Looper;
import android.os.SystemClock;

import io.github.jnesew.comicviewer.document.ComicDocument;
import io.github.jnesew.comicviewer.document.ComicDocumentFactory;
import io.github.jnesew.comicviewer.document.DocumentInfo;
import io.github.jnesew.comicviewer.model.PageInfo;
import io.github.jnesew.comicviewer.util.InputLimits;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Observable recovery and disposal with real renderer workers and controlled decode boundaries. */
public final class RenderingRecoveryTests {
    private RenderingRecoveryTests() { }

    public static void retiringPrefetchKeepsItsReservation(Instrumentation instrumentation) {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        RenderCoordinator cache = new RenderCoordinator(256L << 20, BufferingPolicy.HIGH,
                () -> true, () -> 0);
        Document document = new Document(List.of(new PageInfo("page", 512, 512))) {
            @Override public Bitmap renderTile(int page, Rect source, float scale) throws IOException {
                started.countDown(); hold(release);
                return super.renderTile(page, source, scale);
            }
        };
        try (Fixture f = new Fixture(instrumentation, document, cache)) {
            try {
                f.main(() -> f.renderer.prefetchPages(frame(0), 1, 1));
                await(started, "Optional decode did not start");
                f.main(f.renderer::close);
                check(cache.reservedBytes() == 1L << 20, "Retiring optional output lost its reservation");
                check(cache.pixelBytes() == 1L << 20, "Retiring pixels disappeared from the session allowance");
            } finally { release.countDown(); }
        }
        check(cache.pixelBytes() == 0, "Retired optional work retained its reservation");
    }

    public static void memoryFailureChoosesCheaperPixels(Instrumentation instrumentation) {
        List<Float> scales = Collections.synchronizedList(new ArrayList<>());
        RenderCoordinator cache = new RenderCoordinator(256L << 20, BufferingPolicy.STANDARD,
                () -> true, () -> 0);
        Document document = new Document(List.of(new PageInfo("page", 512, 512))) {
            @Override public Bitmap renderTile(int page, Rect source, float scale) throws IOException {
                scales.add(scale);
                if (scale > .5f) throw new OutOfMemoryError("Full detail does not fit");
                return super.renderTile(page, source, scale);
            }
        };
        try (Fixture f = new Fixture(instrumentation, document, cache)) {
            f.show(frame(0)); f.awaitWhite();
            check(scales.equals(List.of(1f, .5f)), "OOM did not recover at a cheaper level: " + scales);
            check(cache.pixelBytes() <= cache.pixelLimitBytes(), "Recovery exceeded its pixel allowance");
        }
        check(cache.pixelBytes() == 0, "Closing retained pixel reservations");
    }

    public static void correctedGeometryCanRecoverAfterOom(Instrumentation instrumentation) {
        AtomicReference<PageInfo> info = new AtomicReference<>(new PageInfo("page", 512, 512));
        AtomicInteger failures = new AtomicInteger();
        Document document = new Document(List.of(info.get())) {
            @Override public PageInfo page(int index) { return info.get(); }
            @Override public Bitmap renderTile(int page, Rect source, float scale) throws IOException {
                if (info.get().width == 512) {
                    failures.incrementAndGet(); throw new OutOfMemoryError("Provisional geometry fails");
                }
                return super.renderTile(page, source, scale);
            }
        };
        try (Fixture f = new Fixture(instrumentation, document, null)) {
            f.show(frame(0));
            waitFor(() -> failures.get() == 3, "Fixture did not exhaust its bounded OOM attempts");
            f.main(() -> info.set(new PageInfo("page", 1024, 512)));
            f.show(List.of(new TileRenderer.PageRequest(0, new RectF(0, 0, 512, 256),
                    new RectF(0, 0, 512, 1024))));
            f.awaitWhite(256, 128);
            check(failures.get() == 3, "Corrected geometry retried the obsolete allocation");
        }
    }

    public static void coarseOomRetriesTheVisibleLevel(Instrumentation instrumentation) {
        List<Float> scales = Collections.synchronizedList(new ArrayList<>());
        RenderCoordinator cache = new RenderCoordinator(256L << 20, BufferingPolicy.STANDARD,
                () -> true, () -> 0);
        Document document = new Document(List.of(new PageInfo("page", 2048, 2048))) {
            @Override public Bitmap renderTile(int page, Rect source, float scale) throws IOException {
                scales.add(scale);
                if (scales.size() == 1) throw new OutOfMemoryError("Coarse coverage failed");
                return super.renderTile(page, source, scale);
            }
        };
        try (Fixture f = new Fixture(instrumentation, document, cache, 32 * 1024)) {
            f.show(List.of(new TileRenderer.PageRequest(0, new RectF(0, 0, 2048, 2048),
                    new RectF(0, 0, 2048, 2048))));
            f.awaitWhite(256, 768);
            check(scales.size() >= 2 && scales.get(0) == .25f && scales.get(1) == .5f,
                    "Coarse failure repeatedly prepared coverage or over-reduced detail: " + scales);
        }
    }

    public static void coarseCoveragePrecedesBlockedDetail(Instrumentation instrumentation) {
        CountDownLatch detailStarted = new CountDownLatch(1), release = new CountDownLatch(1);
        List<Float> scales = Collections.synchronizedList(new ArrayList<>());
        RenderCoordinator cache = new RenderCoordinator(256L << 20, BufferingPolicy.STANDARD,
                () -> true, () -> 0);
        Document document = new Document(List.of(new PageInfo("page", 2048, 2048))) {
            @Override public Bitmap renderTile(int page, Rect source, float scale) throws IOException {
                scales.add(scale);
                if (scale >= 1) { detailStarted.countDown(); hold(release); }
                return super.renderTile(page, source, scale);
            }
        };
        try (Fixture f = new Fixture(instrumentation, document, cache, 32 * 1024)) {
            try {
                f.show(List.of(new TileRenderer.PageRequest(0, new RectF(0, 0, 2048, 2048),
                        new RectF(0, 0, 2048, 2048))));
                await(detailStarted, "Detail did not start after coarse coverage");
                f.awaitWhite(256, 768);
                check(scales.get(0) == .25f, "Detail ran before inexpensive coverage: " + scales);
                check(cache.pixelBytes() <= cache.pixelLimitBytes(), "In-flight detail was not budgeted");
            } finally { release.countDown(); }
        }
        check(cache.pixelBytes() == 0, "Retiring detail leaked its reservation");
    }

    public static void extractedPagesSurviveDecoderEviction(Instrumentation instrumentation) {
        AtomicInteger extractions = new AtomicInteger();
        List<PageInfo> pages = new ArrayList<>();
        for (int i = 0; i < 6; i++) pages.add(new PageInfo(i + ".png", 400, 4000));
        Document document = new Document(pages) {
            @Override public boolean supportsRenderedTiles() { return false; }
            @Override public void extractPage(int page, File destination) throws IOException {
                extractions.incrementAndGet(); copyAsset(instrumentation, "page.png", destination);
            }
        };
        RenderMetrics metrics = new RenderMetrics(); metrics.start(1);
        RenderCoordinator cache = new RenderCoordinator(256L << 20, BufferingPolicy.STANDARD,
                () -> true, () -> 0, RenderTuning.READER, metrics);
        try (Fixture f = new Fixture(instrumentation, document, cache)) {
            for (int page = 0; page < 6; page++) {
                f.show(List.of(new TileRenderer.PageRequest(page, new RectF(0, 0, 512, 5120),
                        new RectF(0, 0, 512, 512))));
                int expected = page + 1;
                waitFor(() -> metrics.snapshot(2).get("tile_publications").intValue() >= expected,
                        "Raster page did not publish");
            }
            check(metrics.snapshot(2).get("decoder_evictions").intValue() >= 2,
                    "Fixture did not displace a decoder");
            f.main(f.renderer::trimMemory);
            f.show(List.of(new TileRenderer.PageRequest(0, new RectF(0, 0, 512, 5120),
                    new RectF(0, 0, 512, 512))));
            waitFor(() -> metrics.snapshot(2).get("tile_publications").intValue() >= 7,
                    "Returning to the page did not reopen its decoder");
            check(extractions.get() == 6, "Decoder eviction inflated a retained page again");
        }
    }

    public static void visibleAndBackingPixelsShareTheSessionCeiling() {
        RenderCoordinator cache = new RenderCoordinator(128L << 20, BufferingPolicy.STANDARD,
                () -> true, () -> 0);
        Object first = new Object(), second = new Object();
        cache.registerOwner(first); cache.registerOwner(second);
        RenderCoordinator.VisibleTiles firstTiles = cache.visibleTiles(first, 36 * 1024);
        RenderCoordinator.VisibleTiles secondTiles = cache.visibleTiles(second, 36 * 1024);
        TileKey protectedKey = new TileKey(0, 1, 1, 0, 0, 1024, 1024);
        cache.visibleDemand(first, java.util.Set.of(protectedKey));
        List<Bitmap> pixels = new ArrayList<>();
        try {
            for (int i = 0; i < 10; i++) {
                Bitmap bitmap = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888);
                pixels.add(bitmap);
                TileKey key = new TileKey(i, 1, 1, 0, 0, 1024, 1024);
                (i < 5 ? firstTiles : secondTiles).put(key, bitmap);
            }
            check(firstTiles.get(protectedKey) != null, "Another document evicted visible coverage");
            check(cache.claimBacking(second, 24L << 20), "Backing could not use shared free capacity");
            check(cache.reserveDecode(4L << 20), "Visible output could not replace offscreen history");
            check(firstTiles.get(protectedKey) != null, "Allocation discarded pinned visible pixels");
            check(cache.pixelBytes() <= cache.pixelLimitBytes(), "Combined pixel ceiling was exceeded");
            cache.releaseDecode(4L << 20);
            cache.claimBacking(second, 0);
        } finally {
            firstTiles.evictAll(); secondTiles.evictAll(); cache.clear();
            cache.unregisterOwner(first); cache.unregisterOwner(second);
            for (Bitmap bitmap : pixels) bitmap.recycle();
        }
        check(cache.pixelBytes() == 0, "Pixel ownership did not drain");
    }

    public static void latestVisibleDemand(Instrumentation instrumentation) {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        List<Integer> rows = Collections.synchronizedList(new ArrayList<>());
        Document document = new Document(List.of(new PageInfo("strip", 512, 34 * 512))) {
            @Override public Bitmap renderTile(int page, Rect source, float scale) throws IOException {
                int row = source.top / 512;
                rows.add(row);
                if (row == 0) { started.countDown(); hold(release); }
                return super.renderTile(page, source, scale);
            }
        };
        try (Fixture f = new Fixture(instrumentation, document, null)) {
            f.show(strip(0)); await(started, "Initial tile did not start");
            f.main(() -> {
                for (int row = 1; row <= 33; row++) f.draw(strip(row));
            });
            release.countDown();
            f.awaitWhite();
            check(rows.equals(List.of(0, 33)), "Obsolete visible regions delayed the current one: " + rows);
        } finally { release.countDown(); }
    }

    public static void trimAndCloseDoNotWaitForDecode(Instrumentation instrumentation) {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        CountDownLatch disposed = new CountDownLatch(1);
        AtomicBoolean closedOnWorker = new AtomicBoolean();
        AtomicInteger calls = new AtomicInteger();
        Document document = new Document(List.of(new PageInfo("strip", 512, 1024))) {
            @Override public Bitmap renderTile(int page, Rect source, float scale) throws IOException {
                calls.incrementAndGet(); started.countDown(); hold(release);
                return super.renderTile(page, source, scale);
            }
        };
        RenderMetrics metrics = new RenderMetrics(); metrics.start(1);
        RenderCoordinator cache = new RenderCoordinator(256L << 20, BufferingPolicy.HIGH,
                () -> true, () -> 0, RenderTuning.READER, metrics);
        Fixture f = new Fixture(instrumentation, document, cache);
        try {
            f.show(List.of(new TileRenderer.PageRequest(0, new RectF(0, 0, 512, 1024),
                    new RectF(0, 0, 512, 1024))));
            await(started, "Visible decode did not start");
            f.main(() -> {
                long before = SystemClock.uptimeMillis();
                cache.setForeground(false);
                f.renderer.trimMemory();
                f.renderer.closeAfter(() -> {
                    closedOnWorker.set(Looper.myLooper() != Looper.getMainLooper());
                    disposed.countDown();
                });
                check(SystemClock.uptimeMillis() - before < 500, "Lifecycle callback waited for decoding");
            });
            check(disposed.getCount() == 1, "Document was disposed during its active decode");
            release.countDown(); await(disposed, "Deferred disposal never completed");
            f.renderer.awaitStopped();
            check(closedOnWorker.get(), "Document disposal ran on the main thread");
            check(calls.get() == 1, "Queued visible work ran after background trim");
            check(metrics.snapshot(2).get("tile_publications").longValue() == 0,
                    "An invalidated visible completion refilled the cache");
            check(cache.committedBytes() == 0, "Background closure retained reservations or pixels");
        } catch (InterruptedException error) { throw new AssertionError(error); }
        finally { release.countDown(); f.close(); }
    }

    public static void visibleFailuresRetryWithoutPollingDraws(Instrumentation instrumentation) {
        for (boolean permanent : new boolean[]{false, true}) {
            AtomicInteger calls = new AtomicInteger();
            Document document = new Document(List.of(new PageInfo("page", 512, 512))) {
                @Override public Bitmap renderTile(int page, Rect source, float scale) throws IOException {
                    if (calls.incrementAndGet() == 1 || permanent) throw new IOException("Transient fixture read");
                    return super.renderTile(page, source, scale);
                }
            };
            try (Fixture f = new Fixture(instrumentation, document, null)) {
                f.show(frame(0));
                if (!permanent) {
                    f.awaitWhite();
                    check(calls.get() == 2, "Transient visible error did not recover exactly once");
                } else {
                    waitFor(() -> calls.get() == 3, "Permanent failure never reached its bounded attempts");
                    f.main(() -> { for (int i = 0; i < 10; i++) f.draw(frame(0)); });
                    SystemClock.sleep(100);
                    check(calls.get() == 3, "Bad input entered an unbounded visible retry loop");
                }
            }
        }
    }

    public static void visibleOomKeepsHealthyDemand(Instrumentation instrumentation) {
        AtomicInteger failures = new AtomicInteger();
        RenderCoordinator cache = new RenderCoordinator(256L << 20, BufferingPolicy.STANDARD,
                () -> true, () -> 0, RenderTuning.READER, null);
        Document document = new Document(List.of(new PageInfo("bad", 512, 512), new PageInfo("good", 512, 512))) {
            @Override public Bitmap renderTile(int page, Rect source, float scale) throws IOException {
                if (page == 0) {
                    failures.incrementAndGet();
                    throw new OutOfMemoryError("Page-specific allocation failure");
                }
                return super.renderTile(page, source, scale);
            }
        };
        try (Fixture f = new Fixture(instrumentation, document, cache)) {
            List<TileRenderer.PageRequest> requests = List.of(
                    new TileRenderer.PageRequest(0, new RectF(0, 0, 512, 512), new RectF(0, 0, 512, 1024)),
                    new TileRenderer.PageRequest(1, new RectF(0, 512, 512, 1024), new RectF(0, 0, 512, 1024)));
            f.show(requests);
            waitFor(() -> failures.get() == 3, "Visible OOM did not reach its bounded attempts");
            f.awaitWhite(256, 768);
            f.main(() -> { for (int i = 0; i < 10; i++) f.draw(requests); });
            SystemClock.sleep(100);
            check(failures.get() == 3, "Recovering healthy demand restarted exhausted OOM retries");
        }
    }

    public static void speculativeOomKeepsVisibleDemand(Instrumentation instrumentation) {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        RenderCoordinator cache = new RenderCoordinator(256L << 20, BufferingPolicy.HIGH,
                () -> true, () -> 0, RenderTuning.READER, null);
        Document document = new Document(List.of(new PageInfo("optional", 512, 512), new PageInfo("visible", 512, 512))) {
            @Override public Bitmap renderTile(int page, Rect source, float scale) throws IOException {
                if (page == 0) {
                    started.countDown(); hold(release);
                    throw new OutOfMemoryError("Optional allocation failure");
                }
                return super.renderTile(page, source, scale);
            }
        };
        Fixture f = new Fixture(instrumentation, document, cache);
        try {
            f.main(() -> f.renderer.prefetchPages(frame(0), 1, 1));
            await(started, "Optional decode did not start");
            f.show(frame(1));
            release.countDown(); f.awaitWhite();
            check(!cache.enabled() && cache.committedBytes() == 0,
                    "Visible recovery bypassed speculative pressure cooldown");
        } finally { release.countDown(); f.close(); }
    }

    public static void queuedWorkDropsObsoleteDemand(Instrumentation instrumentation) {
        CountDownLatch started = new CountDownLatch(3), release = new CountDownLatch(1);
        RenderCoordinator cache = new RenderCoordinator(256L << 20, BufferingPolicy.STANDARD,
                () -> true, () -> 0, RenderTuning.READER, null);
        List<Fixture> fixtures = new ArrayList<>();
        List<Integer> decoded = Collections.synchronizedList(new ArrayList<>());
        try {
            for (int i = 0; i < 3; i++) {
                Document blocker = new Document(List.of(new PageInfo("blocker", 512, 512))) {
                    @Override public Bitmap renderTile(int page, Rect source, float scale) throws IOException {
                        started.countDown(); hold(release);
                        return super.renderTile(page, source, scale);
                    }
                };
                Fixture fixture = new Fixture(instrumentation, blocker, cache);
                fixtures.add(fixture); fixture.show(frame(0));
            }
            await(started, "Initial session decodes did not start");
            instrumentation.runOnMainSync(() -> {
                for (Fixture fixture : fixtures) fixture.renderer.close();
            });
            Document document = new Document(List.of(new PageInfo("old", 512, 512), new PageInfo("current", 512, 512))) {
                @Override public Bitmap renderTile(int page, Rect source, float scale) throws IOException {
                    decoded.add(page);
                    return super.renderTile(page, source, scale);
                }
            };
            Fixture waiter = new Fixture(instrumentation, document, cache);
            fixtures.add(waiter); waiter.show(frame(0));
            waiter.show(frame(1));
            release.countDown(); waiter.awaitWhite();
            check(decoded.equals(List.of(1)), "Superseded queued work decoded obsolete pages: " + decoded);
        } finally {
            release.countDown(); for (Fixture fixture : fixtures) fixture.close();
        }
    }

    public static void retiredWorkKeepsSessionConcurrencyBounded(Instrumentation instrumentation) {
        CountDownLatch started = new CountDownLatch(3), release = new CountDownLatch(1);
        CountDownLatch replacementStarted = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger();
        RenderCoordinator cache = new RenderCoordinator(256L << 20, BufferingPolicy.HIGH,
                () -> true, () -> 0, RenderTuning.READER, null);
        List<Fixture> fixtures = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) {
                boolean replacement = i == 3;
                Document document = new Document(List.of(new PageInfo("page", 512, 512))) {
                    @Override public Bitmap renderTile(int page, Rect source, float scale) throws IOException {
                        peak.accumulateAndGet(active.incrementAndGet(), Math::max);
                        try {
                            if (replacement) replacementStarted.countDown();
                            else { started.countDown(); hold(release); }
                            return super.renderTile(page, source, scale);
                        } finally { active.decrementAndGet(); }
                    }
                };
                fixtures.add(new Fixture(instrumentation, document, cache));
            }
            for (int i = 0; i < 3; i++) fixtures.get(i).show(frame(0));
            await(started, "Initial session decodes did not start");
            instrumentation.runOnMainSync(() -> {
                for (int i = 0; i < 3; i++) fixtures.get(i).renderer.close();
            });
            check(cache.pixelBytes() >= 3L << 20, "Retiring decodes released their pixel reservations early");
            fixtures.get(3).show(frame(0));
            check(!replacementStarted.await(300, TimeUnit.MILLISECONDS), "Replacement exceeded the session's native decode allowance");
            release.countDown(); fixtures.get(3).awaitWhite();
            check(peak.get() == 3, "Retiring decoders increased native concurrency: " + peak.get());
        } catch (InterruptedException error) { throw new AssertionError(error); }
        finally { release.countDown(); for (Fixture fixture : fixtures) fixture.close(); }
    }

    public static void pressureDiscardCanRefillSamePlan(Instrumentation instrumentation) {
        AtomicBoolean room = new AtomicBoolean(true);
        AtomicInteger calls = new AtomicInteger();
        Document document = new Document(List.of(new PageInfo("page", 512, 512))) {
            @Override public Bitmap renderTile(int page, Rect source, float scale) throws IOException {
                calls.incrementAndGet(); return super.renderTile(page, source, scale);
            }
        };
        RenderCoordinator cache = new RenderCoordinator(256L << 20, BufferingPolicy.HIGH,
                room::get, () -> 0, RenderTuning.READER, null);
        try (Fixture f = new Fixture(instrumentation, document, cache)) {
            f.main(() -> f.renderer.prefetchPages(frame(0), 41, 1));
            waitFor(() -> cache.usedBytes() > 0, "Initial optional pixels did not publish");
            room.set(false);
            check(!cache.beginSpeculativeDecode(), "Refused sample admitted optional work");
            check(cache.usedBytes() == 0, "Refused sample retained optional pixels");
            room.set(true);
            f.main(() -> f.renderer.prefetchPages(frame(0), 41, 1));
            waitFor(() -> cache.usedBytes() > 0, "Pressure recovery suppressed the unchanged plan");
            check(calls.get() == 2, "Pressure recovery churned optional output");
        }
    }

    public static void previewTrimRejectsInFlightOutput(Instrumentation instrumentation) {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicReference<Bitmap> first = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger(), deliveries = new AtomicInteger();
        Document document = new Document(List.of(new PageInfo("page", 512, 512))) {
            @Override public Bitmap renderPreview(int page, int width, int height) {
                int call = calls.incrementAndGet();
                if (call == 1) { started.countDown(); hold(release); }
                Bitmap result = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
                if (call == 1) first.set(result);
                return result;
            }
        };
        try (PagePreviewLoader loader = new PagePreviewLoader(document, 32, 32, new PagePreviewLoader.Callback() {
            @Override public void onPreviewReady(int page, Bitmap bitmap) { deliveries.incrementAndGet(); delivered.countDown(); }
            @Override public void onPreviewUnavailable(int page) { throw new AssertionError("Unexpected preview error"); }
        })) {
            instrumentation.runOnMainSync(() -> loader.request(0)); await(started, "Preview did not start");
            instrumentation.runOnMainSync(loader::trimMemory); release.countDown();
            waitFor(() -> first.get() != null && first.get().isRecycled(), "Trimmed preview pixels were not released");
            check(deliveries.get() == 0, "Trimmed preview reached its old callback");
            instrumentation.runOnMainSync(() -> loader.request(0)); await(delivered, "New preview did not complete");
            check(calls.get() == 2 && deliveries.get() == 1, "Trimmed preview was cached or delivered again");
        } finally { release.countDown(); }
    }

    public static void indexContinuesPastBadPage(Instrumentation instrumentation) {
        Context context = instrumentation.getTargetContext();
        File file = new File(context.getCacheDir(), "index-recovery.cbz");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
                for (int index = 0; index < 3; index++) {
                    zip.putNextEntry(new ZipEntry(index + ".png"));
                    if (index == 1) zip.write(new byte[]{1, 2, 3});
                    else {
                        Bitmap bitmap = Bitmap.createBitmap(100, index == 0 ? 100 : 800, Bitmap.Config.ARGB_8888);
                        try { bitmap.eraseColor(Color.WHITE); check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip), "PNG encode failed"); }
                        finally { bitmap.recycle(); }
                    }
                    zip.closeEntry();
                }
            }
            try (ComicDocument document = ComicDocumentFactory.open(context, Uri.fromFile(file),
                    new DocumentInfo(file.getName(), file.length(), 0, ""), 0, List.of(), -1, -1, null)) {
                AtomicInteger updates = new AtomicInteger(), completed = new AtomicInteger();
                try {
                    document.buildPageIndex(context, null, new ComicDocument.IndexCallback() {
                        @Override public void onProgress(int done, int total) { }
                        @Override public void onPagesUpdated() { updates.incrementAndGet(); }
                        @Override public void onComplete(List<PageInfo> pages) { completed.incrementAndGet(); }
                    });
                    throw new AssertionError("Bad page did not leave an incomplete index");
                } catch (IOException expected) { }
                check(document.page(2).height == 800, "Later valid strip retained provisional square geometry");
                check(document.indexedPages() == 2 && !document.isIndexComplete() && completed.get() == 0,
                        "Partial index was reported as complete");
                check(updates.get() >= 2, "Later valid dimensions were not published");
            }
        } catch (IOException error) { throw new AssertionError(error); }
        finally { file.delete(); }
    }

    public static void planReuseTracksClipAndDimensions(Instrumentation instrumentation) {
        List<PageInfo> pages = new ArrayList<>(List.of(new PageInfo("strip", 512, 1024)));
        RenderCoordinator cache = new RenderCoordinator(256L << 20, BufferingPolicy.HIGH,
                () -> true, () -> 0, RenderTuning.READER, null);
        try (Fixture f = new Fixture(instrumentation, new Document(pages), cache)) {
            f.main(() -> f.renderer.prefetchPages(frame(0), 77, 1));
            waitFor(() -> cache.contains(f.renderer, new TileKey(0, 1, 1, 0, 0, 512, 1024)), "Initial plan did not publish");
            List<TileRenderer.PageRequest> moved = List.of(new TileRenderer.PageRequest(0,
                    new RectF(0, -512, 512, 512), new RectF(0, 0, 512, 512)));
            f.main(() -> f.renderer.prefetchPages(moved, 77, 1));
            waitFor(() -> cache.contains(f.renderer, new TileKey(0, 1, 1, 0, 1, 512, 1024)), "Same-signature plan ignored its changed crop");
            f.main(() -> {
                pages.set(0, new PageInfo("strip", 1024, 1024));
                f.renderer.prefetchPages(moved, 77, 1);
            });
            waitFor(() -> cache.contains(f.renderer, new TileKey(0, 1, .5f, 0, 0, 1024, 1024)), "Reused plan ignored corrected dimensions");
        }
    }

    public static void renderedLevelsReuseResidentCoverage(Instrumentation instrumentation) {
        RenderMetrics metrics = new RenderMetrics(); metrics.start(1);
        RenderCoordinator cache = new RenderCoordinator(256L << 20, BufferingPolicy.HIGH,
                () -> true, () -> 0, RenderTuning.READER, metrics);
        Document document = new Document(List.of(new PageInfo("rendered", 1024, 1024)));
        try (Fixture f = new Fixture(instrumentation, document, cache, 8 * 1024)) {
            f.show(List.of(new TileRenderer.PageRequest(0, new RectF(0, 0, 1024, 1024), new RectF(0, 0, 1024, 1024))));
            waitFor(() -> metrics.snapshot(2).get("tile_publications").longValue() == 4, "Fine rendered tiles were not ready");
            f.main(() -> {
                metrics.beginFrame(3);
                f.draw(frame(0));
                metrics.endFrame();
                check(metrics.missingFraction() > 0 && metrics.uncoveredFraction() == 0,
                        "Rendered fine-to-coarse transition lost resident fallback coverage");
                check(f.output.getPixel(500, 500) == Color.WHITE, "Finer rendered tiles left a dark gap");
            });
        }
    }

    public static void fallbackUsesExtractedFile(Instrumentation instrumentation) {
        AtomicInteger streams = new AtomicInteger(), extractions = new AtomicInteger();
        Document document = new Document(List.of(new PageInfo("page.gif", 8192, 4096))) {
            @Override public boolean supportsRenderedTiles() { return false; }
            @Override public InputStream openPage(int page) throws IOException {
                streams.incrementAndGet(); throw new IOException("A second archive inflation is unnecessary");
            }
            @Override public void extractPage(int page, File destination) throws IOException {
                extractions.incrementAndGet();
                copyAsset(instrumentation, "page.gif", destination);
            }
        };
        RenderMetrics metrics = new RenderMetrics(); metrics.start(1);
        RenderCoordinator cache = new RenderCoordinator(256L << 20, BufferingPolicy.HIGH,
                () -> true, () -> 0, RenderTuning.READER, metrics);
        try (Fixture f = new Fixture(instrumentation, document, cache)) {
            f.show(List.of(new TileRenderer.PageRequest(0, new RectF(0, 0, 512, 256), new RectF(0, 0, 512, 256))));
            waitFor(() -> metrics.snapshot(2).get("tile_publications").longValue() == 1, "GIF fallback did not publish from its extracted file");
            f.main(() -> check(f.output.getPixel(4, 50) == Color.RED, "Extracted-file fallback changed image pixels"));
            check(streams.get() == 0 && extractions.get() == 1, "Fallback inflated its archive entry repeatedly");
        }
    }

    public static void speculativePagesPreserveVisibleDecoders(Instrumentation instrumentation) {
        AtomicInteger extractions = new AtomicInteger();
        List<PageInfo> pages = new ArrayList<>();
        for (int i = 0; i < 5; i++) pages.add(new PageInfo(i + ".png", 400, 4000));
        Document document = new Document(pages) {
            @Override public boolean supportsRenderedTiles() { return false; }
            @Override public void extractPage(int page, File destination) throws IOException {
                extractions.incrementAndGet(); copyAsset(instrumentation, "page.png", destination);
            }
        };
        RenderMetrics metrics = new RenderMetrics(); metrics.start(1);
        RenderCoordinator cache = new RenderCoordinator(256L << 20, BufferingPolicy.HIGH,
                () -> true, () -> 0, RenderTuning.READER, metrics);
        try (Fixture f = new Fixture(instrumentation, document, cache, 8 * 1024)) {
            List<TileRenderer.PageRequest> visible = new ArrayList<>();
            for (int page = 0; page < 4; page++) visible.add(new TileRenderer.PageRequest(page,
                    new RectF(page * 128, 0, (page + 1) * 128, 1280), new RectF(0, 0, 512, 100)));
            f.show(visible);
            waitFor(() -> metrics.snapshot(2).get("tile_publications").longValue() == 4, "Visible decoder set did not become ready");
            f.main(() -> f.renderer.prefetchPages(List.of(new TileRenderer.PageRequest(4,
                    new RectF(0, 0, 128, 1280), new RectF(0, 0, 128, 100))), 1, 1));
            waitFor(() -> metrics.snapshot(2).get("decoder_denials").longValue() == 1 && cache.reservedBytes() == 0,
                    "Optional decoder admission did not finish");
            check(extractions.get() == 4 && metrics.snapshot(2).get("decoder_evictions").longValue() == 0,
                    "Optional work displaced a currently visible decoder");
            f.show(List.of(new TileRenderer.PageRequest(0, new RectF(0, -1100, 128, 180), new RectF(0, 0, 128, 100))));
            waitFor(() -> cache.usedBytes() > 0 && metrics.snapshot(2).get("tile_publications").longValue() >= 6,
                    "Deferred preparation did not resume when visible decoders became offscreen");
            f.show(List.of(new TileRenderer.PageRequest(0, new RectF(0, -550, 128, 730), new RectF(0, 0, 128, 100))));
            waitFor(() -> metrics.snapshot(2).get("tile_publications").longValue() >= 7, "Visible strip did not render its next region");
            check(extractions.get() == 5 && metrics.snapshot(2).get("decoder_opens").longValue() == 5,
                    "Optional pages forced re-extraction of the remaining visible decoder");
            check(metrics.snapshot(2).get("render_failures").longValue() == 0, "Optional admission was counted as a decode failure");
        }
    }

    private static void copyAsset(Instrumentation instrumentation, String name, File destination) throws IOException {
        try (InputStream input = instrumentation.getContext().getAssets().open("rendering/" + name)) {
            InputLimits.copyAtomically(input, destination, InputLimits.MAX_PAGE_BYTES,
                    InputLimits.Reason.PAGE_BYTES, "Fixture replace failed", "Fixture rename failed");
        }
    }

    private static List<TileRenderer.PageRequest> strip(int row) {
        return List.of(new TileRenderer.PageRequest(0, new RectF(0, -512 * row, 512, 512 * (34 - row)),
                new RectF(0, 0, 512, 512)));
    }
    private static List<TileRenderer.PageRequest> frame(int page) {
        return List.of(new TileRenderer.PageRequest(page, new RectF(0, 0, 512, 512), new RectF(0, 0, 512, 512)));
    }
    private static void hold(CountDownLatch latch) {
        boolean interrupted = false;
        for (;;) {
            try { check(latch.await(5, TimeUnit.SECONDS), "Decode barrier was not released"); break; }
            catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
    private static void await(CountDownLatch latch, String message) {
        try { check(latch.await(5, TimeUnit.SECONDS), message); }
        catch (InterruptedException error) { throw new AssertionError(error); }
    }
    private static void waitFor(BooleanSupplier ready, String message) {
        long deadline = SystemClock.uptimeMillis() + 5_000;
        while (!ready.getAsBoolean() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(10);
        check(ready.getAsBoolean(), message);
    }
    private static void check(boolean ready, String message) { if (!ready) throw new AssertionError(message); }

    private static class Document implements ComicDocument {
        final List<PageInfo> pages;
        Document(List<PageInfo> pages) { this.pages = pages; }
        @Override public Uri uri() { return Uri.parse("test:recovery"); }
        @Override public String key() { return uri().toString(); }
        @Override public String title() { return "Recovery fixture"; }
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
        @Override public Bitmap renderTile(int page, Rect source, float scale) throws IOException {
            Bitmap result = Bitmap.createBitmap(Math.max(1, Math.round(source.width() * scale)),
                    Math.max(1, Math.round(source.height() * scale)), Bitmap.Config.ARGB_8888);
            result.eraseColor(Color.WHITE); return result;
        }
        @Override public void buildPageIndex(Context context, ProgressCallback progress, IndexCallback callback) { callback.onComplete(pages); }
        @Override public void close() { }
    }

    private static final class Fixture implements AutoCloseable {
        final Instrumentation instrumentation;
        final Bitmap output = Bitmap.createBitmap(512, 1024, Bitmap.Config.ARGB_8888);
        final TileRenderer renderer;
        List<TileRenderer.PageRequest> latest = List.of();
        Fixture(Instrumentation instrumentation, ComicDocument document, RenderCoordinator cache) {
            this(instrumentation, document, cache, 4 * 1024);
        }
        Fixture(Instrumentation instrumentation, ComicDocument document, RenderCoordinator cache, int cacheKb) {
            this.instrumentation = instrumentation;
            renderer = new TileRenderer(instrumentation.getTargetContext(), document,
                    () -> draw(latest), message -> { }, cache, cacheKb);
        }
        void main(Runnable action) { instrumentation.runOnMainSync(action); }
        void show(List<TileRenderer.PageRequest> requests) { main(() -> draw(requests)); }
        void draw(List<TileRenderer.PageRequest> requests) {
            latest = requests;
            output.eraseColor(Color.BLACK);
            renderer.drawPages(new Canvas(output), requests);
        }
        void awaitWhite() {
            awaitWhite(256, 256);
        }
        void awaitWhite(int x, int y) {
            waitFor(() -> {
                AtomicBoolean ready = new AtomicBoolean();
                main(() -> ready.set(output.getPixel(x, y) == Color.WHITE));
                return ready.get();
            }, "Visible content did not recover through its own invalidation");
        }
        @Override public void close() {
            renderer.close();
            try { renderer.awaitStopped(); } catch (InterruptedException error) { throw new AssertionError(error); }
            main(() -> { });
            output.recycle();
        }
    }
}
