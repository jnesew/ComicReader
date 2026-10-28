package io.github.jnesew.comicviewer.bench;

import android.graphics.Rect;
import android.net.Uri;
import android.os.SystemClock;
import android.view.MotionEvent;
import io.github.jnesew.comicviewer.document.*;
import io.github.jnesew.comicviewer.model.*;
import io.github.jnesew.comicviewer.render.*;
import org.json.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Foreground, same-input comparisons. All document I/O and disposal run off the UI thread. */
final class BenchRunner {
    private final RenderBenchActivity host;
    private final AtomicBoolean stopped;
    private final String suite;
    private final BenchFiles files;
    private final List<JSONObject> results = new ArrayList<>();
    private final Map<String, File> preparedFixtures = new HashMap<>();
    private final StringBuilder csv = new StringBuilder("policy,fixture,mode,repeat,status,ready_p50_ms,ready_p95_ms,exact_missing_area_ms,frame_misses,shared_cap_bytes,peak_shared_bytes\n");
    private RenderMetrics metrics;
    private ComicCanvasView canvas;
    private final List<Double> readiness = new ArrayList<>();
    private final List<JSONObject> caseEvents = new ArrayList<>();
    private int timeouts;

    BenchRunner(RenderBenchActivity host, AtomicBoolean stopped, String suite, BenchFiles files) {
        this.host = host; this.stopped = stopped; this.suite = suite; this.files = files;
    }

    void run() throws Exception {
        List<BenchPolicy> policies = BenchPolicy.policies(suite);
        String[][] cases = suite.startsWith("smoke")
                ? new String[][]{{"raster.cbz", "single"}, {"vector.pdf", "spread"}, {"strips.cbz", "continuous"},
                        {"images.epub", "continuous"}, {"fallback.cbz", "single"}, {"issue-a.cbz", "series"}}
                : "individual".equals(suite)
                ? new String[][]{{"raster.cbz", "single"}, {"raster.cbz", "spread"}, {"strips.cbz", "continuous"},
                        {"vector.pdf", "spread"}, {"images.epub", "continuous"}, {"fallback.cbz", "single"}, {"issue-a.cbz", "series"}}
                : new String[][]{{"raster.cbz", "single"}, {"strips.cbz", "continuous"}, {"vector.pdf", "spread"}};
        // Fixture copies/hashes are verified before any measured case.
        for (String name : new String[]{"raster.cbz", "strips.cbz", "vector.pdf", "images.epub", "fallback.cbz", "issue-a.cbz", "issue-b.cbz", "issue-c.cbz"}) {
            check(); preparedFixtures.put(name, BenchFiles.fixture(host, name));
        }
        int repeats = suite.startsWith("smoke") ? 1 : "soak".equals(suite) ? 8 : 2;
        for (int repeat = 0; repeat < repeats; repeat++) {
            List<BenchPolicy> order = new ArrayList<>(policies);
            if ((repeat & 1) != 0) Collections.reverse(order);
            for (String[] scenario : cases) {
                if (repeat == 0) one(policies.get(0), scenario[0], scenario[1], -1);
                for (BenchPolicy policy : order) {
                    check(); one(policy, scenario[0], scenario[1], repeat);
                    Thread.sleep(suite.startsWith("smoke") ? 150 : 600);
                }
            }
        }
    }

    private void one(BenchPolicy policy, String fixture, String mode, int repeat) throws Exception {
        String caseId = policy.id + "/" + fixture + "/" + mode + "/" + repeat;
        JSONObject result = new JSONObject().put("policy", policy.id).put("fixture", fixture)
                .put("mode", mode).put("repeat", repeat).put("status", "incomplete");
        files.write("state.json", new JSONObject().put("status", "running").put("case", caseId).toString(2));
        files.event(new JSONObject().put("event", "case_start").put("case", caseId));
        host.progress(caseId);
        List<ComicDocument> documents = new ArrayList<>();
        List<TileRenderer> renderers = new ArrayList<>();
        AtomicReference<String> renderError = new AtomicReference<>();
        SpeculativeTileCache shared = null;
        RenderBenchActivity.Frames frames = null;
        boolean measuring = false;
        metrics = new RenderMetrics(); readiness.clear(); caseEvents.clear(); timeouts = 0;
        try {
            long opening = SystemClock.elapsedRealtimeNanos();
            String[] names = "series".equals(mode) ? new String[]{"issue-a.cbz", "issue-b.cbz", "issue-c.cbz"} : new String[]{fixture};
            for (String name : names) documents.add(open(preparedFixtures.get(name)));
            result.put("document_open_index_ms", (SystemClock.elapsedRealtimeNanos() - opening) / 1e6);
            shared = new SpeculativeTileCache(host, Runtime.getRuntime().maxMemory(), BufferingPolicy.HIGH, policy.tuning, metrics);
            host.activeCache(shared);
            canvas = host.ui(() -> new ComicCanvasView(host));
            ComicCanvasView view = canvas;
            for (ComicDocument document : documents) {
                renderers.add(new TileRenderer(host, document, view::postInvalidateOnAnimation, renderError::set, shared));
            }
            ReadingProgress position = new ReadingProgress();
            position.uri = documents.get(0).key(); position.title = "Synthetic";
            position.readingMode = "series".equals(mode) ? ComicCanvasView.CONTINUOUS : mode;
            position.zoomMode = ComicCanvasView.SPREAD.equals(mode) ? ComicCanvasView.FIT_PAGE : ComicCanvasView.FIT_WIDTH;
            host.ui(() -> {
                view.setRenderExperiment(policy.tuning, metrics);
                view.setBufferingLevel(BufferingPolicy.HIGH);
                view.setDocument(renderers.get(0), documents.get(0).pages(), position);
                if (documents.size() > 1) {
                    List<ComicCanvasView.ContinuousDocument> adjacent = new ArrayList<>();
                    for (int i = 0; i < documents.size(); i++) adjacent.add(new ComicCanvasView.ContinuousDocument(
                            documents.get(i).key(), "Synthetic " + i, renderers.get(i), documents.get(i).pages()));
                    view.setContinuousDocuments(adjacent, documents.get(0).key(), 0, 0);
                }
                metrics.start(SystemClock.elapsedRealtimeNanos());
                host.showCanvas(view, caseId);
                return null;
            });
            frames = host.beginFrames(); measuring = true;
            result.put("shared_cap_bytes", shared.limitBytes());
            result.put("requested_cache_mib", policy.tuning.cacheMiB);
            result.put("low_level", BufferingPolicy.HIGH);
            awaitReady(0, "open", renderError);
            result.put("viewport_width", host.ui(view::getWidth));
            result.put("viewport_height", host.ui(view::getHeight));
            if (repeat < 0) {
                dwell(200);
                result.put("status", timeouts == 0 ? "warmup" : "readiness_timeout");
                return;
            }
            dwell(suite.startsWith("smoke") ? 250 : 1200);
            int last = documents.get(0).count() - 1;
            if (ComicCanvasView.CONTINUOUS.equals(position.readingMode)) {
                int steps = suite.startsWith("smoke") ? 2 : 5;
                for (int i = 0; i < steps; i++) gesture(true, renderError);
                for (int i = 0; i < 2; i++) gesture(false, renderError);
                action("zoom_in", () -> view.multiplyZoom(1.25f), renderError);
                action("zoom_out", view::fitWidth, renderError);
            } else {
                int steps = suite.startsWith("smoke") ? 2 : 4;
                for (int i = 0; i < steps; i++) {
                    action("next", () -> view.showPage(view.navigationTarget(1), 0), renderError);
                    dwell(suite.startsWith("smoke") ? 100 : 450);
                }
                for (int i = 0; i < 2; i++) action("reverse", () -> view.showPage(view.navigationTarget(-1), 0), renderError);
                action("zoom_in", () -> view.multiplyZoom(1.25f), renderError);
                action("zoom_out", view::fitPage, renderError);
            }
            action("jump", () -> view.showPage(last, 0), renderError);
            action("return", () -> view.showPage(0, 0), renderError);
            dwell(suite.startsWith("smoke") ? 150 : 600);
            if (renderError.get() != null) throw new IllegalStateException(renderError.get());
            if (shared.usedBytes() + shared.reservedBytes() > shared.limitBytes()) throw new AssertionError("Shared retained+pending ceiling exceeded");
            result.put("status", timeouts == 0 ? "completed" : "readiness_timeout");
        } catch (InterruptedException interrupted) {
            result.put("status", "interrupted").put("interruption_reason", host.interruptionReason()); throw interrupted;
        } catch (Exception | AssertionError | OutOfMemoryError failure) {
            result.put("status", "failed"); result.put("failure", failure.toString());
        } finally {
            result.put("readiness_timeouts", timeouts);
            result.put("readiness_ms", new JSONArray(readiness));
            result.put("ready_p50_ms", percentile(readiness, .50)); result.put("ready_p95_ms", percentile(readiness, .95));
            result.put("ready_p99_ms", percentile(readiness, .99));
            result.put("metrics", new JSONObject(metrics.finish(SystemClock.elapsedRealtimeNanos())));
            if (frames != null) result.put("frames", host.endFrames(frames));
            if (canvas != null) {
                try { host.ui(() -> { canvas.clearDocument(); return null; }); }
                catch (Exception ignored) { /* Activity destruction already detaches the View. */ }
            }
            for (TileRenderer renderer : renderers) renderer.close();
            host.activeCache(null);
            for (ComicDocument document : documents) document.close();
            if (shared != null && shared.reservedBytes() != 0) {
                result.put("status", "failed"); result.put("failure", "Reservation leaked after closure");
            }
            result.put("memory_after", BenchFiles.memory(host));
            result.put("timing_started", measuring);
            for (JSONObject event : caseEvents) files.event(event);
            files.result(result); results.add(result);
            files.event(new JSONObject().put("event", "case_end").put("case", caseId).put("status", result.getString("status")));
            saveSummary();
        }
    }

    private ComicDocument open(File file) throws Exception {
        ComicDocument document = ComicDocumentFactory.open(host, Uri.fromFile(file),
                new DocumentInfo(file.getName(), file.length(), file.lastModified(), ""), 0, List.of(), -1, -1, null);
        try {
            document.buildPageIndex(host, null, new ComicDocument.IndexCallback() {
                @Override public boolean isCancelled() { return stopped.get(); }
                @Override public void onProgress(int done, int total) { }
                @Override public void onPagesUpdated() { }
                @Override public void onComplete(List<PageInfo> pages) { }
            });
            check(); return document;
        } catch (Exception | Error failure) { document.close(); throw failure; }
    }
    private void action(String name, Runnable command, AtomicReference<String> error) throws Exception {
        check(); long draws = metrics.draws(); long start = SystemClock.elapsedRealtimeNanos();
        host.ui(() -> { command.run(); return null; });
        awaitReady(draws, name, error, start);
    }
    private void awaitReady(long previousDraws, String name, AtomicReference<String> error) throws Exception {
        awaitReady(previousDraws, name, error, SystemClock.elapsedRealtimeNanos());
    }
    private void awaitReady(long previousDraws, String name, AtomicReference<String> error, long start) throws Exception {
        boolean ready = false;
        while (SystemClock.elapsedRealtimeNanos() - start < 8_000_000_000L) {
            check(); if (error.get() != null) throw new IllegalStateException(error.get());
            if (metrics.draws() > previousDraws && metrics.missingFraction() < .00001) { ready = true; break; }
            Thread.sleep(16);
        }
        double elapsed = (SystemClock.elapsedRealtimeNanos() - start) / 1e6;
        if (ready) readiness.add(elapsed); else timeouts++;
        if (caseEvents.size() < 128) caseEvents.add(new JSONObject().put("event", "ready").put("action", name).put("ready", ready)
                .put("elapsed_ms", elapsed).put("exact_missing_fraction", metrics.missingFraction()));
    }
    private void gesture(boolean forward, AtomicReference<String> error) throws Exception {
        check();
        Rect bounds = host.ui(() -> new Rect(0, 0, canvas.getWidth(), canvas.getHeight()));
        long down = SystemClock.uptimeMillis();
        float start = bounds.height() * (forward ? .8f : .2f);
        float end = bounds.height() * (forward ? .2f : .8f);
        touch(down, down, MotionEvent.ACTION_DOWN, bounds.width() / 2f, start);
        long duration = suite.startsWith("smoke") ? 180 : 650;
        for (int i = 1; i <= 15; i++) {
            dwell(duration / 15);
            touch(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, bounds.width() / 2f, start + (end - start) * i / 15f);
        }
        long draws = metrics.draws();
        touch(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, bounds.width() / 2f, end);
        // Wait until the fling settles so readiness is attributed to the final viewport.
        dwell(500);
        awaitReady(Math.max(0, draws - 1), forward ? "scroll_forward" : "scroll_reverse", error);
    }
    private void touch(long down, long at, int action, float x, float y) throws Exception {
        host.ui(() -> {
            MotionEvent event = MotionEvent.obtain(down, at, action, x, y, 0);
            try { canvas.dispatchTouchEvent(event); } finally { event.recycle(); }
            return null;
        });
    }
    private void dwell(long millis) throws InterruptedException {
        long until = SystemClock.elapsedRealtime() + millis;
        while (SystemClock.elapsedRealtime() < until) { check(); Thread.sleep(Math.min(40, Math.max(1, until - SystemClock.elapsedRealtime()))); }
    }
    private void check() throws InterruptedException { if (stopped.get()) throw new InterruptedException("Stopped or activity interrupted"); }
    private static Object percentile(List<Double> values, double p) {
        if (values.isEmpty()) return JSONObject.NULL;
        List<Double> sorted = new ArrayList<>(values); Collections.sort(sorted);
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * p) - 1));
    }
    void saveSummary() throws Exception {
        StringBuilder summary = new StringBuilder("Rendering comparison — exact-resolution readiness\nFresh sessions; kernel/provider caches are not flushed. Timeouts invalidate timing comparisons.\n\n");
        StringBuilder rows = new StringBuilder(csv.substring(0, csv.indexOf("\n") + 1));
        for (JSONObject result : results) {
            JSONObject m = result.getJSONObject("metrics"); JSONObject f = result.optJSONObject("frames");
            summary.append(result.getString("policy")).append(" / ").append(result.getString("fixture")).append(" / ")
                    .append(result.getString("mode")).append(" #").append(result.getInt("repeat")).append(": ")
                    .append(result.getString("status")).append("; ready p95 ").append(result.opt("ready_p95_ms"))
                    .append(" ms; exact-gap area ").append(m.opt("exact_missing_area_ms")).append(" ms\n");
            rows.append(result.getString("policy")).append(',').append(result.getString("fixture")).append(',')
                    .append(result.getString("mode")).append(',').append(result.getInt("repeat")).append(',')
                    .append(result.getString("status")).append(',').append(result.opt("ready_p50_ms")).append(',')
                    .append(result.opt("ready_p95_ms")).append(',').append(m.opt("exact_missing_area_ms")).append(',')
                    .append(f == null ? "null" : f.opt("misses")).append(',').append(result.opt("shared_cap_bytes")).append(',')
                    .append(m.opt("peak_shared_bytes")).append('\n');
        }
        files.write("summary.txt", summary.toString()); files.write("results.csv", rows.toString());
    }
}
