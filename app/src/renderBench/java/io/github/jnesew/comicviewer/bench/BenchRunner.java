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

/** Foreground comparisons. File I/O and resource disposal stay outside measured actions and off the UI thread. */
final class BenchRunner {
    private final RenderBenchActivity host;
    private final AtomicBoolean stopped;
    private final BenchPlan plan;
    private final BenchFiles files;
    private final List<JSONObject> results = new ArrayList<>();
    private final Map<String, File> prepared = new HashMap<>();
    private final List<Window> windows = new ArrayList<>();
    private final List<Double> readiness = new ArrayList<>();
    private final AtomicReference<String> renderError = new AtomicReference<>();
    private final List<String> documentKeys = new ArrayList<>();
    private RenderMetrics metrics;
    private ComicCanvasView canvas;
    private SpeculativeTileCache shared;
    private BenchFrames frames;
    private String caseId, fixture, mode;
    private int cycle, timeouts, sequence, targetSpeed;
    private final Map<String, Integer> occurrences = new HashMap<>();

    BenchRunner(RenderBenchActivity host, AtomicBoolean stopped, String suite, BenchFiles files) {
        this.host = host; this.stopped = stopped; this.plan = new BenchPlan(suite); this.files = files;
    }
    String run() throws Exception {
        for (String name : new String[]{"raster.cbz", "strips.cbz", "vector.pdf", "images.epub", "fallback.cbz", "issue-a.cbz", "issue-b.cbz", "issue-c.cbz"}) {
            check(); prepared.put(name, BenchFiles.fixture(host, name));
        }
        String[][] scenarios = plan.scenarios();
        for (int repeat = 0; repeat < plan.repeats(); repeat++) {
            for (int index = 0; index < scenarios.length; index++) {
                String[] scenario = scenarios[index];
                int[] speeds = plan.speeds(repeat);
                for (int speedIndex = 0; speedIndex < speeds.length; speedIndex++) {
                    int speed = speeds[speedIndex];
                    if (repeat == 0 && speedIndex == 0) one(plan.policies(scenario[0]).get(0), scenario[0], scenario[1], -1, -1, speed);
                    List<BenchPolicy> order = plan.order(scenario[0], index + speedIndex, repeat);
                    for (int slot = 0; slot < order.size(); slot++) {
                        check(); one(order.get(slot), scenario[0], scenario[1], repeat, slot, speed);
                        dwell(plan.shortRun() ? 100 : 400);
                    }
                }
            }
        }
        for (JSONObject result : results) {
            String status = result.getString("status");
            if (!status.equals("completed") && !status.equals("warmup")) return "completed_with_case_errors";
        }
        return "completed";
    }
    private void one(BenchPolicy policy, String fixture, String mode, int repeat, int order, int speed) throws Exception {
        this.fixture = fixture; this.mode = mode; targetSpeed = speed; cycle = 0; occurrences.clear();
        caseId = policy.id + "/" + fixture + "/" + mode + "/" + repeat + (speed > 0 ? "/speed-" + speed : "");
        JSONObject result = new JSONObject().put("policy", policy.id).put("fixture", fixture).put("mode", mode)
                .put("repeat", repeat).put("case_id", caseId).put("status", "incomplete")
                .put("policy_order", order).put("protocol", BenchPlan.PROTOCOL).put("session_sequence", ++sequence)
                .put("session_reuse", plan.sustained()).put("planned_cycles", repeat < 0 ? 0 : plan.cycles());
        if (speed > 0) result.put("target_viewports_per_second", speed);
        files.write("state.json", new JSONObject().put("status", "running").put("case", caseId).toString());
        files.event(new JSONObject().put("event", "case_start").put("case", caseId));
        List<ComicDocument> documents = new ArrayList<>(); List<TileRenderer> renderers = new ArrayList<>();
        metrics = new RenderMetrics(); canvas = null; shared = null; frames = null;
        windows.clear(); readiness.clear(); renderError.set(null); timeouts = 0;
        documentKeys.clear();
        long started = SystemClock.elapsedRealtimeNanos();
        try {
            long opening = SystemClock.elapsedRealtimeNanos();
            String[] names = mode.equals("series") ? new String[]{"issue-a.cbz", "issue-b.cbz", "issue-c.cbz"} : new String[]{fixture};
            for (String name : names) documents.add(open(prepared.get(name)));
            for (ComicDocument document : documents) documentKeys.add(document.key());
            result.put("document_open_index_ms", (SystemClock.elapsedRealtimeNanos() - opening) / 1e6);
            result.put("document_open_count", documents.size()).put("renderer_creation_count", documents.size());
            shared = new SpeculativeTileCache(host, Runtime.getRuntime().maxMemory(), BufferingPolicy.HIGH, policy.tuning, metrics);
            host.activeCache(shared);
            canvas = host.ui(() -> new ComicCanvasView(host));
            for (ComicDocument document : documents) renderers.add(new TileRenderer(host, document,
                    canvas::postInvalidateOnAnimation, renderError::set, shared));
            ReadingProgress position = new ReadingProgress(); position.uri = documents.get(0).key(); position.title = "Synthetic";
            position.readingMode = continuous() ? ComicCanvasView.CONTINUOUS : mode;
            position.zoomMode = mode.equals("spread") ? ComicCanvasView.FIT_PAGE : ComicCanvasView.FIT_WIDTH;
            result.put("initial_zoom_mode", position.zoomMode).put("low_level", BufferingPolicy.HIGH)
                    .put("shared_cap_bytes", shared.limitBytes()).put("requested_cache_mib", policy.tuning.cacheMiB)
                    .put("bounded_admission", policy.tuning.boundedAdmission()).put("refill", policy.tuning.refill)
                    .put("prioritized", policy.tuning.prioritized)
                    .put("speculative_pipeline", policy.tuning.speculativePipeline)
                    .put("tall_lookahead_bands", policy.tuning.tallLookaheadBands)
                    .put("byte_lookahead", policy.tuning.byteLookahead).put("predicted_frame", policy.tuning.predictedFrame)
                    .put("history_fallback", policy.tuning.historyFallback);
            frames = host.beginFrames(); metrics.start(SystemClock.elapsedRealtimeNanos());
            measure("open", "command_to_exact", () -> {
                long draws = metrics.draws(); long at = SystemClock.elapsedRealtimeNanos();
                host.ui(() -> {
                    canvas.setRenderExperiment(policy.tuning, metrics); canvas.setBufferingLevel(BufferingPolicy.HIGH);
                    canvas.setDocument(renderers.get(0), documents.get(0).pages(), position);
                    if (documents.size() > 1) {
                        List<ComicCanvasView.ContinuousDocument> adjacent = new ArrayList<>();
                        for (int i = 0; i < documents.size(); i++) adjacent.add(new ComicCanvasView.ContinuousDocument(
                                documents.get(i).key(), "Synthetic " + i, renderers.get(i), documents.get(i).pages()));
                        canvas.setContinuousDocuments(adjacent, documents.get(0).key(), 0, 0);
                    }
                    host.showCanvas(canvas, caseId); return null;
                });
                return awaitReady(draws, at, false);
            }, settle());
            result.put("viewport_width", host.ui(canvas::getWidth)).put("viewport_height", host.ui(canvas::getHeight));
            if (repeat < 0) { result.put("status", timeouts == 0 ? "warmup" : "readiness_timeout"); return; }
            observe("idle_prefill", plan.shortRun() ? 150 : 1200);
            // Identical source/anchor probes in all three raster layouts, followed by natural input.
            if (plan.roundTwo() && !plan.scrolling() && fixture.equals("raster.cbz")) {
                int[] targets = {2, 4, 2, 0};
                for (int index = 0; index < targets.length; index++) {
                    int page = targets[index];
                    command(index < 2 ? "anchor_forward" : "anchor_reverse",
                            () -> canvas.showPage(page, 0), settle());
                }
            }
            for (cycle = 0; cycle < plan.cycles(); cycle++) {
                occurrences.clear();
                if (plan.sustained()) {
                    host.progress(caseId + " · " + (cycle + 1) + "/" + plan.cycles());
                    command("cycle_reset", () -> showTarget(0, false), settle());
                }
                Map<String, Number> before = metrics.snapshot(SystemClock.elapsedRealtimeNanos());
                script(documents.get(0).count() - 1);
                assertBudget();
                if (plan.sustained()) {
                    observe("cycle_idle", plan.shortRun() ? 100 : 1500);
                    Map<String, Number> after = metrics.snapshot(SystemClock.elapsedRealtimeNanos());
                    // This persisted checkpoint is outside action windows; a killed run stays incomplete.
                    files.checkpoint(new JSONObject().put("case_id", caseId).put("cycle", cycle)
                            .put("same_session", true).put("metrics_delta", delta(before, after))
                            .put("shared_bytes", shared.usedBytes()).put("pending_bytes", shared.reservedBytes())
                            .put("memory", BenchFiles.memory(host)));
                    files.write("state.json", new JSONObject().put("status", "running").put("case", caseId)
                            .put("completed_cycles", cycle + 1).toString());
                }
            }
            result.put("completed_cycles", plan.cycles());
            result.put("status", timeouts == 0 ? "completed" : "readiness_timeout");
        } catch (InterruptedException interrupted) {
            result.put("status", "interrupted").put("interruption_reason", host.interruptionReason()); throw interrupted;
        } catch (ReadinessTimeout timeout) {
            result.put("status", "readiness_timeout").put("failure", timeout.getMessage());
        } catch (Exception | AssertionError | OutOfMemoryError failure) {
            result.put("status", "failed").put("failure", failure.toString());
        } finally {
            long ended = System.nanoTime();
            result.put("readiness_timeouts", timeouts).put("readiness_ms", new JSONArray(readiness))
                    .put("ready_p50_ms", percentile(readiness, .50)).put("ready_p95_ms", percentile(readiness, .95))
                    .put("ready_p99_ms", percentile(readiness, .99)).put("metrics", new JSONObject(metrics.finish(SystemClock.elapsedRealtimeNanos())))
                    .put("case_elapsed_ms", (SystemClock.elapsedRealtimeNanos() - started) / 1e6);
            if (frames != null) {
                frames.cutoff(ended);
                try {
                    JSONObject frameResult = host.endFrames(frames); result.put("frames", frameResult);
                    result.put("frame_diagnostics_valid", frameResult.getBoolean("valid") && frameResult.getLong("count") > 0);
                    long attributed = 0;
                    for (Window window : windows) {
                        JSONObject sample = frames.window(window.start, window.end);
                        attributed += sample.getLong("count"); window.value.put("frames", sample);
                    }
                    frameResult.put("unattributed_frames", Math.max(0, frameResult.getLong("count") - attributed));
                } catch (Exception failure) { result.put("frame_error", failure.toString()).put("frame_diagnostics_valid", false); }
            }
            if (canvas != null) {
                try { host.ui(() -> { canvas.clearDocument(); return null; }); }
                catch (Exception ignored) { /* Destroying the Activity also detaches the View. */ }
            }
            for (TileRenderer renderer : renderers) try { renderer.close(); } catch (Exception failure) { fail(result, failure); }
            host.activeCache(null);
            for (ComicDocument document : documents) try { document.close(); } catch (Exception failure) { fail(result, failure); }
            if (shared != null && shared.reservedBytes() != 0) fail(result, new IllegalStateException("Reservation leaked after closure"));
            result.put("memory_after", BenchFiles.memory(host)).put("timing_started", frames != null);
            for (Window window : windows) files.action(window.value);
            files.result(result); results.add(result);
            files.event(new JSONObject().put("event", "case_end").put("case", caseId).put("status", result.getString("status")));
            saveSummary();
        }
    }
    private static void fail(JSONObject result, Exception failure) throws JSONException {
        if (!result.optString("status").equals("interrupted")) result.put("status", "failed");
        result.put("failure", failure.toString());
    }
    private void assertBudget() {
        if (renderError.get() != null) throw new IllegalStateException(renderError.get());
        if (shared.committedBytes() > shared.limitBytes() ||
                metrics.snapshot(SystemClock.elapsedRealtimeNanos()).get("peak_shared_plus_pending_bytes").longValue() > shared.limitBytes())
            throw new AssertionError("Shared retained + pending ceiling exceeded");
    }
    private boolean continuous() { return mode.equals("continuous") || mode.equals("series"); }
    private int settle() { return plan.shortRun() ? 60 : 180; }
    private void script(int last) throws Exception {
        if (plan.scrolling()) {
            command("speed_reset_" + targetSpeed, () -> showTarget(0, false), settle());
            observe("speed_prefill_" + targetSpeed, plan.shortRun() ? 100 : 400);
            sustainedDrag(targetSpeed, true); sustainedDrag(targetSpeed, false);
        } else if (continuous()) {
            int steps = plan.shortRun() ? 2 : plan.roundTwo() ? 4 : 5;
            for (int i = 0; i < steps; i++) gesture(true, plan.roundTwo() && i >= steps / 2);
            for (int i = 0; i < (plan.shortRun() ? 1 : 2); i++) gesture(false, false);
        } else {
            for (int i = 0; i < (plan.shortRun() ? 2 : 4); i++) command("next", () -> canvas.showPage(canvas.navigationTarget(1), 0), plan.shortRun() ? 60 : 450);
            for (int i = 0; i < (plan.shortRun() ? 1 : 2); i++) command("reverse", () -> canvas.showPage(canvas.navigationTarget(-1), 0), settle());
        }
        command("zoom_in", () -> canvas.multiplyZoom(1.25f), settle());
        command("zoom_out", () -> { if (continuous()) canvas.fitWidth(); else canvas.fitPage(); }, settle());
        command("jump", () -> showTarget(last, true), settle());
        command("return", () -> showTarget(0, false), settle());
        if (plan.roundTwo()) measure("burst_jump", "last_command_to_exact", () -> {
            host.ui(() -> { showTarget(Math.min(2, last), false); return null; }); dwell(32);
            host.ui(() -> { showTarget(last, true); return null; }); dwell(32);
            long draws = metrics.draws(), at = SystemClock.elapsedRealtimeNanos();
            host.ui(() -> { showTarget(0, false); return null; });
            return awaitReady(draws, at, false);
        }, settle());
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
    private void command(String name, Runnable command, int hold) throws Exception {
        measure(name, "command_to_exact", () -> {
            long draws = metrics.draws(), at = SystemClock.elapsedRealtimeNanos();
            host.ui(() -> { command.run(); return null; }); return awaitReady(draws, at, false);
        }, hold);
    }
    private double awaitReady(long previousDraws, long start, boolean waitForMotion) throws Exception {
        boolean ready = false;
        while (SystemClock.elapsedRealtimeNanos() - start < 8_000_000_000L) {
            check(); if (renderError.get() != null) throw new IllegalStateException(renderError.get());
            if (metrics.draws() > previousDraws && metrics.missingFraction() < .00001 &&
                    (!waitForMotion || !host.ui(canvas::isMotionRunning))) { ready = true; break; }
            Thread.sleep(16);
        }
        double elapsed = (SystemClock.elapsedRealtimeNanos() - start) / 1e6;
        if (ready) readiness.add(elapsed); else timeouts++;
        return ready ? elapsed : Double.NaN;
    }
    private void gesture(boolean forward, boolean fast) throws Exception {
        String name = forward ? (fast ? "scroll_fast" : "scroll_forward") : "scroll_reverse";
        measure(name, plan.roundTwo() ? "after_up_to_motion_settled_and_exact" : "after_fixed_500ms_settle", () -> {
            Rect bounds = host.ui(() -> new Rect(0, 0, canvas.getWidth(), canvas.getHeight()));
            long down = SystemClock.uptimeMillis();
            float start = bounds.height() * (forward ? .8f : .2f), end = bounds.height() * (forward ? .2f : .8f);
            touch(down, down, MotionEvent.ACTION_DOWN, bounds.width() / 2f, start);
            long duration = plan.shortRun() || fast ? 180 : 650;
            for (int i = 1; i <= 15; i++) {
                dwell(duration / 15); touch(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE,
                        bounds.width() / 2f, start + (end - start) * i / 15f);
            }
            long draws = metrics.draws(); touch(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, bounds.width() / 2f, end);
            if (!plan.roundTwo()) dwell(500);
            return awaitReady(Math.max(0, draws - 1), SystemClock.elapsedRealtimeNanos(), plan.roundTwo());
        }, settle());
    }
    private void observe(String name, long duration) throws Exception { measure(name, "observation_only", () -> { dwell(duration); return null; }, 0); }
    private void measure(String action, String kind, Timed operation, long hold) throws Exception {
        measure(action, kind, operation, hold, null);
    }
    private void sustainedDrag(int speed, boolean forward) throws Exception {
        JSONObject motion = new JSONObject().put("target_viewports_per_second", speed)
                .put("forward", forward).put("gesture_count", plan.shortRun() ? 2 : 4);
        measure("speed_" + speed + (forward ? "_forward" : "_reverse"), "after_cancel_to_exact", () -> {
            Rect bounds = host.ui(() -> new Rect(0, 0, canvas.getWidth(), canvas.getHeight()));
            float start = bounds.height() * (forward ? .85f : .15f);
            float end = bounds.height() * (forward ? .15f : .85f);
            long duration = Math.max(1, Math.round(700.0 / speed));
            int steps = Math.max(2, (int) Math.ceil(duration / 16.0));
            double distance = 0, previous = host.ui(canvas::continuousScrollPosition);
            Map<String, Number> beforeMotion = metrics.snapshot(SystemClock.elapsedRealtimeNanos());
            long started = SystemClock.elapsedRealtimeNanos();
            for (int gesture = 0; gesture < (plan.shortRun() ? 2 : 4); gesture++) {
                long down = SystemClock.uptimeMillis();
                touch(down, down, MotionEvent.ACTION_DOWN, bounds.width() / 2f, start);
                for (int step = 1; step <= steps; step++) {
                    long target = down + duration * step / steps;
                    dwell(Math.max(0, target - SystemClock.uptimeMillis()));
                    touch(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, bounds.width() / 2f,
                            start + (end - start) * step / steps);
                    double position = host.ui(canvas::continuousScrollPosition);
                    distance += Math.abs(position - previous); previous = position;
                }
                // Cancel ends each drag without injecting a fling or waiting for pixels between drags.
                touch(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, bounds.width() / 2f, end);
            }
            long ended = SystemClock.elapsedRealtimeNanos();
            motion.put("delivered_scroll_viewports", distance / bounds.height())
                    .put("motion_duration_ms", (ended - started) / 1e6)
                    .put("metrics_delta", delta(beforeMotion, metrics.snapshot(ended)))
                    .put("delivered_viewports_per_second", distance / bounds.height() * 1e9 / Math.max(1, ended - started));
            long draws = metrics.draws();
            host.ui(() -> { canvas.postInvalidateOnAnimation(); return null; });
            return awaitReady(draws, SystemClock.elapsedRealtimeNanos(), true);
        }, settle(), motion);
    }
    private void measure(String action, String kind, Timed operation, long hold, JSONObject motion) throws Exception {
        check(); if (windows.size() >= 160) throw new IllegalStateException("Action limit exceeded");
        String occurrenceKey = cycle + "/" + action;
        int occurrence = occurrences.merge(occurrenceKey, 1, Integer::sum) - 1;
        Map<String, Number> before = metrics.snapshot(SystemClock.elapsedRealtimeNanos());
        Window window = new Window(); window.start = System.nanoTime();
        window.value = new JSONObject().put("case_id", caseId).put("action", action).put("cycle", cycle)
                .put("occurrence", occurrence).put("readiness_kind", kind).put("trailing_settle_ms", hold)
                .put("shared_start_bytes", shared.usedBytes()).put("pending_start_bytes", shared.reservedBytes())
                .put("future_growth_start_bytes", shared.futureGrowthBytes())
                .put("start_page", host.ui(canvas::page)).put("start_document_slot", documentSlot());
        if (motion != null) window.value.put("motion", motion);
        windows.add(window);
        try {
            Double ready = operation.run();
            window.value.put("ready_ms", ready == null || ready.isNaN() ? JSONObject.NULL : ready)
                    .put("status", ready != null && ready.isNaN() ? "readiness_timeout" : "completed");
            if (ready != null && ready.isNaN()) throw new ReadinessTimeout("Exact readiness timed out: " + action);
            dwell(hold);
        } catch (Exception | AssertionError | OutOfMemoryError failure) {
            window.value.put("status", failure instanceof InterruptedException ? "interrupted" :
                    failure instanceof ReadinessTimeout ? "readiness_timeout" : "failed"); throw failure;
        } finally {
            window.end = System.nanoTime();
            Map<String, Number> after = metrics.snapshot(SystemClock.elapsedRealtimeNanos());
            window.value.put("start_nanos", window.start).put("end_nanos", window.end)
                    .put("window_ms", (window.end - window.start) / 1e6).put("metrics_delta", delta(before, after))
                    .put("shared_end_bytes", shared.usedBytes()).put("pending_end_bytes", shared.reservedBytes())
                    .put("future_growth_end_bytes", shared.futureGrowthBytes())
                    .put("end_missing_fraction", metrics.missingFraction())
                    .put("end_uncovered_fraction", metrics.uncoveredFraction());
            // Activity destruction makes UI state unavailable; preserve the interrupted action anyway.
            try { window.value.put("end_page", host.ui(canvas::page)).put("end_document_slot", documentSlot())
                    .put("motion_running_after", host.ui(canvas::isMotionRunning)); }
            catch (Exception ignored) { window.value.put("position_unavailable", true); }
        }
    }
    private int documentSlot() throws Exception {
        return documentKeys.size() <= 1 ? 0 : documentKeys.indexOf(host.ui(canvas::continuousDocumentKey));
    }
    private void showTarget(int localPage, boolean lastDocument) {
        if (documentKeys.size() > 1) canvas.showContinuousAnchor(documentKeys.get(lastDocument ? documentKeys.size() - 1 : 0), localPage, 0);
        else canvas.showPage(localPage, 0);
    }
    private static JSONObject delta(Map<String, Number> before, Map<String, Number> after) throws JSONException {
        JSONObject out = new JSONObject();
        for (String key : before.keySet()) if (!key.startsWith("peak_"))
            out.put(key, after.get(key).doubleValue() - before.get(key).doubleValue());
        return out;
    }
    private void touch(long down, long at, int action, float x, float y) throws Exception {
        host.ui(() -> {
            MotionEvent event = MotionEvent.obtain(down, at, action, x, y, 0);
            try { canvas.dispatchTouchEvent(event); } finally { event.recycle(); } return null;
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
    private void saveSummary() throws Exception {
        StringBuilder summary = new StringBuilder("Rendering comparison — protocol 2\nAction/frame breakdowns: actions.jsonl. Sustained checkpoints: checkpoints.jsonl.\n\n");
        StringBuilder csv = new StringBuilder("policy,fixture,mode,repeat,target_viewports_per_second,status,ready_p95_ms,exact_missing_area_ms,decode_ms,frame_misses,frame_count,shared_cap_bytes,peak_shared_bytes\n");
        for (JSONObject result : results) {
            JSONObject m = result.getJSONObject("metrics"), f = result.optJSONObject("frames");
            summary.append(result.getString("case_id")).append(": ").append(result.getString("status"))
                    .append("; ready p95 ").append(result.opt("ready_p95_ms")).append(" ms; exact-gap area ")
                    .append(m.opt("exact_missing_area_ms")).append(" ms\n");
            csv.append(result.getString("policy")).append(',').append(result.getString("fixture")).append(',')
                    .append(result.getString("mode")).append(',').append(result.getInt("repeat")).append(',')
                    .append(result.optInt("target_viewports_per_second", 0)).append(',')
                    .append(result.getString("status")).append(',').append(result.opt("ready_p95_ms")).append(',')
                    .append(m.opt("exact_missing_area_ms")).append(',').append(m.opt("decode_ms")).append(',')
                    .append(f == null ? "null" : f.opt("misses")).append(',').append(f == null ? "null" : f.opt("count")).append(',')
                    .append(result.opt("shared_cap_bytes")).append(',').append(m.opt("peak_shared_bytes")).append('\n');
        }
        files.write("summary.txt", summary.toString()); files.write("results.csv", csv.toString());
    }
    private interface Timed { Double run() throws Exception; }
    private static final class Window { long start, end; JSONObject value; }
    private static final class ReadinessTimeout extends Exception {
        ReadinessTimeout(String message) { super(message); }
    }
}
