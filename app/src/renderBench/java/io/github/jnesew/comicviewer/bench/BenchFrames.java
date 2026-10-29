package io.github.jnesew.comicviewer.bench;

import android.os.Build;
import android.os.Debug;
import android.view.Display;
import android.view.FrameMetrics;
import org.json.JSONObject;
import java.util.Map;

/** Frame callback adapter. Samples are copied immediately; no per-frame event/JSON allocation. */
final class BenchFrames {
    private static final int[] IDS = {FrameMetrics.TOTAL_DURATION, FrameMetrics.UNKNOWN_DELAY_DURATION,
            FrameMetrics.INPUT_HANDLING_DURATION, FrameMetrics.ANIMATION_DURATION, FrameMetrics.LAYOUT_MEASURE_DURATION,
            FrameMetrics.DRAW_DURATION, FrameMetrics.SYNC_DURATION, FrameMetrics.COMMAND_ISSUE_DURATION,
            FrameMetrics.SWAP_BUFFERS_DURATION};
    private final Display display;
    private final BenchFrameSamples samples;
    private final long[] scratch = new long[BenchFrameSamples.PHASES.length];
    private long peakJava, peakNative, memorySamples;
    private float refreshMin = Float.MAX_VALUE, refreshMax;
    private boolean measuring = true;

    BenchFrames(Display display) {
        this.display = display; samples = new BenchFrameSamples(System.nanoTime());
        refresh();
    }
    private float refresh() {
        float hz = Math.max(1, display.getRefreshRate());
        refreshMin = Math.min(refreshMin, hz); refreshMax = Math.max(refreshMax, hz); return hz;
    }
    synchronized void memorySample() {
        if (!measuring) return;
        Runtime runtime = Runtime.getRuntime(); peakJava = Math.max(peakJava, runtime.totalMemory() - runtime.freeMemory());
        peakNative = Math.max(peakNative, Debug.getNativeHeapAllocatedSize()); memorySamples++; refresh();
    }
    synchronized void add(FrameMetrics frame, int dropped) {
        for (int p = 0; p < IDS.length; p++) scratch[p] = frame.getMetric(IDS[p]);
        scratch[9] = Build.VERSION.SDK_INT >= 31 ? frame.getMetric(FrameMetrics.GPU_DURATION) : -1;
        long deadline = Build.VERSION.SDK_INT >= 31 ? frame.getMetric(FrameMetrics.DEADLINE) : -1;
        boolean platform = deadline > 0;
        if (!platform) deadline = (long) (1e9 / refresh());
        samples.add(frame.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP), deadline, platform,
                frame.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1, dropped, scratch);
    }
    synchronized void cutoff(long end) { measuring = false; samples.cutoff(end); }
    synchronized void seal() { measuring = false; samples.seal(); }
    JSONObject window(long from, long to) { return new JSONObject(samples.result(from, to)); }
    synchronized JSONObject result() throws Exception {
        Map<String, Object> out = samples.result();
        out.put("memory_sample_interval_ms", 500); out.put("memory_samples", memorySamples);
        out.put("peak_sampled_java_bytes", memorySamples == 0 ? null : peakJava);
        out.put("peak_sampled_native_bytes", memorySamples == 0 ? null : peakNative);
        out.put("refresh_min_hz", refreshMin); out.put("refresh_max_hz", refreshMax);
        out.put("deadline_source", Build.VERSION.SDK_INT >= 31 ? "platform_with_recorded_fallback" : "callback_display_refresh_approximation");
        out.put("attribution", "intended_vsync_timestamp; action windows are half-open; phases may overlap");
        out.put("drain_grace_ms", 250);
        return new JSONObject(out);
    }
}
