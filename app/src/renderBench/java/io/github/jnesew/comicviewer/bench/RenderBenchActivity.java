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
    private volatile BenchFrames frameStats;
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
            BenchFrames active = frameStats;
            if (active != null) active.add(metrics, dropped);
        }, frameHandler);
        lastRun = BenchFiles.latest(this);
        home(lastRun == null ? getString(R.string.bench_ready) : getString(R.string.bench_incomplete));
        // Explicit emulator automation entry. Normal launcher use never starts tests automatically.
        String requested = getIntent().getStringExtra("suite");
        if ("smoke".equals(requested) || "smoke_all".equals(requested) || "smoke_v2".equals(requested) || "smoke_v2_session".equals(requested) ||
                "smoke_candidate".equals(requested) || "smoke_candidate_session".equals(requested)) main.postDelayed(() -> start(requested), 600);
    }

    private void home(String message) {
        LinearLayout column = new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL);
        column.setFocusableInTouchMode(true);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        column.setPadding(padding, padding, padding, padding);
        ScrollView scroll = new ScrollView(this); scroll.addView(column); setContentView(scroll);
        safeInsets(scroll);
        TextView intro = new TextView(this); intro.setText(R.string.bench_intro); column.addView(intro);
        TextView device = new TextView(this);
        device.setText(getString(R.string.bench_device, Runtime.getRuntime().maxMemory() / (1024 * 1024),
                getWindowManager().getDefaultDisplay().getRefreshRate())); column.addView(device);
        osLabel = new EditText(this); osLabel.setSingleLine(); osLabel.setHint(R.string.bench_os_hint); osLabel.setText(selectedOs); column.addView(osLabel);
        suites = new Spinner(this);
        suites.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{getString(R.string.bench_candidate), getString(R.string.bench_candidate_sustained), getString(R.string.bench_focused), getString(R.string.bench_sustained), getString(R.string.bench_quick), getString(R.string.bench_full), getString(R.string.bench_soak)}));
        column.addView(suites);
        button(column, R.string.bench_run, () -> start(BenchPlan.SUITES[suites.getSelectedItemPosition()]));
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
                outcome = new BenchRunner(this, stopped, suite, files).run();
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
        root.addView(stop);
        status = new TextView(this); status.setSingleLine(true); status.setTextSize(12);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END); status.setText(id); root.addView(status);
        root.addView(canvas, new LinearLayout.LayoutParams(-1, 0, 1)); setContentView(root);
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
    BenchFrames beginFrames() throws Exception {
        return ui(() -> {
            BenchFrames frames = new BenchFrames(getWindowManager().getDefaultDisplay());
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
    JSONObject endFrames(BenchFrames frames) throws Exception {
        // Keep the listener alive briefly for late reports from the measured interval.
        // Intended timestamps beyond the cutoff are excluded by the collector.
        Thread.sleep(250);
        FutureTask<Void> barrier = new FutureTask<>(() -> null);
        if (!frameHandler.post(barrier)) throw new InterruptedException("Frame listener stopped");
        barrier.get(5, TimeUnit.SECONDS);
        frameStats = null; frames.seal();
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
}
