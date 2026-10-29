package io.github.jnesew.comicviewer.bench;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded primitive storage. Attribution uses intended-vsync time, never callback arrival time. */
final class BenchFrameSamples {
    static final int MAX_FRAMES = 8192;
    static final String[] PHASES = {"total", "unknown_delay", "input", "animation", "layout_measure",
            "draw", "sync", "command_issue", "swap_buffers", "gpu"};
    private final long start;
    private final long[] timestamps;
    private final long[] deadlines;
    private final long[][] durations;
    private final int[] lost;
    private final boolean[] platform;
    private long cutoff = Long.MAX_VALUE;
    private boolean sealed;
    private int size, overflow, firstDraws;
    private long dropped;

    BenchFrameSamples(long start) { this(start, MAX_FRAMES); }
    BenchFrameSamples(long start, int capacity) {
        this.start = start; timestamps = new long[capacity]; deadlines = new long[capacity];
        durations = new long[PHASES.length][capacity]; lost = new int[capacity]; platform = new boolean[capacity];
    }
    synchronized void cutoff(long end) { cutoff = Math.min(cutoff, end); }
    synchronized void seal() { sealed = true; }
    synchronized void add(long intended, long deadline, boolean actualDeadline, boolean firstDraw,
            int lostReports, long[] phases) {
        if (sealed || intended < start || intended >= cutoff || phases[0] < 0) return;
        dropped += Math.max(0, lostReports);
        if (firstDraw) { firstDraws++; return; }
        if (size == timestamps.length) { overflow++; return; }
        timestamps[size] = intended; deadlines[size] = deadline; platform[size] = actualDeadline;
        lost[size] = Math.max(0, lostReports);
        for (int p = 0; p < PHASES.length; p++) durations[p][size] = phases[p];
        size++;
    }
    synchronized Map<String, Object> result(long from, long to) {
        Histogram[] phases = new Histogram[PHASES.length];
        for (int p = 0; p < phases.length; p++) phases[p] = new Histogram();
        long count = 0, misses = 0, associatedDrops = 0, real = 0, approximate = 0;
        for (int i = 0; i < size; i++) {
            if (timestamps[i] < from || timestamps[i] >= to) continue;
            count++; associatedDrops += lost[i];
            if (deadlines[i] > 0 && durations[0][i] >= deadlines[i]) misses++;
            if (platform[i]) real++; else approximate++;
            for (int p = 0; p < phases.length; p++) phases[p].add(durations[p][i]);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", count); result.put("misses", misses);
        result.put("dropped_reports_associated_with_callbacks", associatedDrops);
        result.put("platform_deadline_frames", real); result.put("approximate_deadline_frames", approximate);
        result.put("duration_mean_ms", phases[0].mean()); result.put("duration_p95_bin_ms", phases[0].percentile(.95));
        result.put("duration_p99_bin_ms", phases[0].percentile(.99)); result.put("duration_max_ms", phases[0].maximum());
        result.put("histogram_bin_ms", 1); result.put("histogram_overflow_ms", 1000);
        Map<String, Object> values = new LinkedHashMap<>();
        for (int p = 0; p < phases.length; p++) values.put(PHASES[p], phases[p].result());
        result.put("phases", values);
        result.put("frame_buffer_overflow", overflow);
        result.put("valid", overflow == 0 && dropped == 0);
        return result;
    }
    synchronized Map<String, Object> result() {
        Map<String, Object> result = result(start, cutoff);
        result.put("dropped_reports", dropped); result.put("first_draw_frames_excluded", firstDraws);
        result.put("frame_buffer_capacity", timestamps.length);
        return result;
    }
    private static final class Histogram {
        private final long[] bins = new long[1001];
        private long count, total, max;
        void add(long nanos) {
            if (nanos < 0) return; // Unsupported metrics are not zero-duration samples.
            count++; total += nanos; max = Math.max(max, nanos);
            bins[(int) Math.min(1000, nanos / 1_000_000)]++;
        }
        Object mean() { return count == 0 ? null : total / 1e6 / count; }
        Object maximum() { return count == 0 ? null : max / 1e6; }
        Object percentile(double p) {
            if (count == 0) return null;
            long goal = (long) Math.ceil(count * p), sum = 0;
            for (int i = 0; i < bins.length; i++) { sum += bins[i]; if (sum >= goal) return i; }
            return 1000;
        }
        Map<String, Object> result() {
            Map<String, Object> out = new LinkedHashMap<>(); out.put("count", count);
            out.put("mean_ms", mean()); out.put("p95_bin_ms", percentile(.95)); out.put("max_ms", maximum());
            return out;
        }
    }
}
