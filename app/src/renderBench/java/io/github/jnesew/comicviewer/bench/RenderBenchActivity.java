package io.github.jnesew.comicviewer.bench;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.*;
import android.view.*;
import android.widget.*;
import io.github.jnesew.comicviewer.R;
import io.github.jnesew.comicviewer.render.ComicCanvasView;
import io.github.jnesew.comicviewer.render.SpeculativeTileCache;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Standalone test variant: no instrumentation, shell, Google services or permissions required. */
public final class RenderBenchActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private volatile boolean destroyed;
    private volatile Frames frameStats;
    private volatile SpeculativeTileCache activeCache;
    private volatile String interruptionReason = "";
    private HandlerThread frameThread;
    private Handler frameHandler;
    private Spinner suites;
    private EditText osLabel;
    private TextView status;
    private File lastRun;
    private boolean exportFixtures;
    private String selectedOs = "";

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        frameThread = new HandlerThread("bench-frame-metrics"); frameThread.start();
        frameHandler = new Handler(frameThread.getLooper());
        getWindow().addOnFrameMetricsAvailableListener((window, metrics, dropped) -> {
            Frames active = frameStats;
            if (active != null) active.add(metrics, dropped);
        }, frameHandler);
        lastRun = BenchFiles.latest(this);
        home(lastRun == null ? getString(R.string.bench_ready) : getString(R.string.bench_incomplete));
        // Explicit emulator automation entry. Normal launcher use never starts tests automatically.
        String requested = getIntent().getStringExtra("suite");
        if ("smoke".equals(requested) || "smoke_all".equals(requested)) main.postDelayed(() -> start(requested), 600);
    }

    private void home(String message) {
        LinearLayout column = new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL);
        column.setFocusableInTouchMode(true);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        column.setPadding(padding, padding, padding, padding);
        ScrollView scroll = new ScrollView(this); scroll.addView(column); setContentView(scroll);
        safeInsets(scroll);
        TextView intro = new TextView(this); intro.setText(R.string.bench_intro); column.addView(intro);
        osLabel = new EditText(this); osLabel.setSingleLine(); osLabel.setHint(R.string.bench_os_hint); osLabel.setText(selectedOs); column.addView(osLabel);
        suites = new Spinner(this);
        suites.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{getString(R.string.bench_quick), getString(R.string.bench_full), getString(R.string.bench_soak)}));
        column.addView(suites);
        button(column, R.string.bench_run, () -> start(new String[]{"quick", "individual", "soak"}[suites.getSelectedItemPosition()]));
        button(column, R.string.bench_export, () -> export(false));
        button(column, R.string.bench_fixtures, () -> export(true));
        status = new TextView(this); status.setText(message); status.setTextIsSelectable(true); column.addView(status);
        TextView manual = new TextView(this); manual.setText(R.string.bench_manual); column.addView(manual);
        column.requestFocus();
    }
    private void button(LinearLayout parent, int label, Runnable action) {
        Button button = new Button(this); button.setText(label); button.setOnClickListener(v -> action.run()); parent.addView(button);
    }
    private void start(String suite) {
        if (running || destroyed) return;
        selectedOs = osLabel.getText().toString().trim();
        if (selectedOs.length() > 100) selectedOs = selectedOs.substring(0, 100);
        running = true; stopped.set(false); interruptionReason = "";
        ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(osLabel.getWindowToken(), 0);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        progress(suite);
        worker.execute(() -> {
            BenchFiles files = null; String outcome = "completed";
            try {
                files = new BenchFiles(this, suite, selectedOs); lastRun = files.directory;
                new BenchRunner(this, stopped, suite, files).run();
            } catch (InterruptedException interrupted) { outcome = "interrupted"; }
            catch (Exception | AssertionError | OutOfMemoryError failure) { outcome = "failed: " + failure; }
            final String end = outcome;
            try {
                if (files != null) files.write("state.json", new JSONObject().put("status", end)
                        .put("interruption_reason", interruptionReason).toString(2));
            } catch (Exception ignored) { }
            running = false;
            main.post(() -> {
                if (destroyed) return;
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                home(getString(R.string.bench_finished, end) + "\n" + summary());
            });
        });
    }
    void progress(String id) { main.post(() -> { if (!destroyed && status != null) status.setText(getString(R.string.bench_running, id)); }); }
    void showCanvas(ComicCanvasView canvas, String id) {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        Button stop = new Button(this); stop.setText(R.string.bench_stop); stop.setOnClickListener(v -> stopRun("user_stop"));
        root.addView(stop); root.addView(canvas, new LinearLayout.LayoutParams(-1, 0, 1)); setContentView(root);
        safeInsets(root);
    }
    private void safeInsets(View root) {
        if (Build.VERSION.SDK_INT < 35) return;
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        root.requestApplyInsets();
    }
    <T> T ui(Callable<T> action) throws Exception {
        if (destroyed) throw new InterruptedException("Activity destroyed");
        if (Looper.myLooper() == Looper.getMainLooper()) return action.call();
        FutureTask<T> task = new FutureTask<>(() -> { if (destroyed) throw new InterruptedException("Activity destroyed"); return action.call(); });
        main.post(task);
        try { return task.get(15, TimeUnit.SECONDS); }
        catch (TimeoutException timeout) { task.cancel(false); throw timeout; }
    }
    Frames beginFrames() throws Exception {
        return ui(() -> {
            Frames frames = new Frames(getWindowManager().getDefaultDisplay().getRefreshRate());
            frameStats = frames;
            frameHandler.post(new Runnable() {
                @Override public void run() {
                    if (frameStats != frames || destroyed) return;
                    frames.memorySample(); frameHandler.postDelayed(this, 500);
                }
            });
            return frames;
        });
    }
    JSONObject endFrames(Frames frames) throws Exception {
        frameStats = null;
        return frames.result();
    }
    void activeCache(SpeculativeTileCache cache) {
        activeCache = cache;
        if (cache != null && stopped.get()) cache.setForeground(false);
    }
    String interruptionReason() { return interruptionReason; }
    private void stopRun(String reason) {
        interruptionReason = reason; stopped.set(true);
        SpeculativeTileCache cache = activeCache;
        if (cache != null) cache.setForeground(false);
    }
    private String summary() {
        try {
            File file = new File(lastRun, "summary.txt");
            if (file.length() > 256 * 1024) return file.getName();
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (Exception ignored) { return getString(R.string.bench_incomplete); }
    }
    private void export(boolean fixtures) {
        if (running) return;
        if (!fixtures && lastRun == null) { status.setText(R.string.bench_no_results); return; }
        exportFixtures = fixtures;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip")
                .putExtra(Intent.EXTRA_TITLE, fixtures ? "comicviewer-synthetic-comics.zip" : "comicviewer-render-results-" + lastRun.getName() + ".zip");
        startActivityForResult(intent, 51);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 51 || result != RESULT_OK || data == null || data.getData() == null) return;
        android.net.Uri uri = data.getData(); boolean fixtures = exportFixtures; File run = lastRun;
        worker.execute(() -> {
            try {
                OutputStream output = getContentResolver().openOutputStream(uri);
                if (output == null) throw new IOException("Cannot open export destination");
                BenchFiles.export(this, run, output, fixtures);
                main.post(() -> { if (!destroyed) status.setText(R.string.bench_saved); });
            } catch (Exception error) { main.post(() -> { if (!destroyed) status.setText(getString(R.string.bench_error, error.toString())); }); }
        });
    }
    @Override protected void onPause() { if (running) stopRun("backgrounded"); super.onPause(); }
    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config); if (running) stopRun("configuration_changed");
    }
    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (!running) return;
        if (level == android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            stopRun("ui_hidden");
        } else if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE) {
            SpeculativeTileCache cache = activeCache;
            if (cache != null) cache.onMemoryPressure();
            stopRun("system_trim_" + level);
        }
    }
    @Override public void onLowMemory() {
        super.onLowMemory();
        if (running) {
            SpeculativeTileCache cache = activeCache;
            if (cache != null) cache.onMemoryPressure();
            stopRun("system_low_memory");
        }
    }
    @Override protected void onDestroy() {
        stopRun("activity_destroyed"); destroyed = true; frameStats = null;
        worker.shutdown(); frameThread.quitSafely(); super.onDestroy();
    }
    static final class Frames {
        private final long start = System.nanoTime();
        private final long approximateDeadline;
        private final long[] bins = new long[1001];
        private long count, misses, dropped, nanos;
        private long peakJava, peakNative, memorySamples;
        Frames(float hz) { approximateDeadline = (long) (1e9 / Math.max(1, hz)); }
        synchronized void memorySample() {
            Runtime runtime = Runtime.getRuntime();
            peakJava = Math.max(peakJava, runtime.totalMemory() - runtime.freeMemory());
            peakNative = Math.max(peakNative, Debug.getNativeHeapAllocatedSize()); memorySamples++;
        }
        synchronized void add(FrameMetrics frame, int lost) {
            if (frame.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP) < start || frame.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1) return;
            long duration = frame.getMetric(FrameMetrics.TOTAL_DURATION);
            if (duration < 0) return;
            long deadline = Build.VERSION.SDK_INT >= 31 ? frame.getMetric(FrameMetrics.DEADLINE) : approximateDeadline;
            if (deadline <= 0) deadline = approximateDeadline;
            count++; nanos += duration; dropped += lost;
            if (duration >= deadline) misses++;
            bins[(int) Math.min(1000, duration / 1_000_000)]++;
        }
        synchronized JSONObject result() throws Exception {
            long goal = (long) Math.ceil(count * .95), cumulative = 0; int p95 = 0;
            for (int i = 0; i < bins.length; i++) { cumulative += bins[i]; if (cumulative >= goal) { p95 = i; break; } }
            return new JSONObject().put("count", count).put("misses", misses).put("dropped_reports", dropped)
                    .put("duration_mean_ms", count == 0 ? JSONObject.NULL : nanos / 1e6 / count)
                    .put("duration_p95_bin_ms", count == 0 ? JSONObject.NULL : p95)
                    .put("histogram_bin_ms", 1).put("histogram_overflow_ms", 1000)
                    .put("memory_sample_interval_ms", 500).put("memory_samples", memorySamples)
                    .put("peak_sampled_java_bytes", memorySamples == 0 ? JSONObject.NULL : peakJava)
                    .put("peak_sampled_native_bytes", memorySamples == 0 ? JSONObject.NULL : peakNative)
                    .put("deadline_source", Build.VERSION.SDK_INT >= 31 ? "platform_when_available" : "initial_display_refresh_approximation");
        }
    }
}
