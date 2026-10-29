package io.github.jnesew.comicviewer.bench;

import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class BenchFrameSamplesTest {
    private static long[] phases(long duration) {
        long[] out = new long[10]; out[0] = duration; out[5] = duration / 2; out[9] = -1; return out;
    }
    @Test public void lateReportsUseIntendedTimeAndDoNotBleedIntoTheNextAction() {
        BenchFrameSamples samples = new BenchFrameSamples(100, 10);
        samples.add(250, 16_000_000, true, false, 0, phases(20_000_000));
        samples.cutoff(400);
        samples.add(150, 16_000_000, true, false, 0, phases(10_000_000)); // Delivered late.
        samples.add(400, 16_000_000, true, false, 0, phases(99_000_000)); // Outside case.
        Map<String, Object> first = samples.result(100, 200), second = samples.result(200, 400);
        assertEquals(1L, first.get("count")); assertEquals(10, first.get("duration_p95_bin_ms"));
        assertEquals(1L, second.get("count")); assertEquals(20, second.get("duration_p95_bin_ms"));
        assertEquals(1L, second.get("misses"));
        samples.seal(); samples.add(175, 16_000_000, true, false, 0, phases(99_000_000));
        assertEquals(2L, samples.result().get("count"));
    }
    @Test public void unsupportedPhasesAreUnavailableRatherThanFreeWork() {
        BenchFrameSamples samples = new BenchFrameSamples(100, 10);
        samples.add(150, 16_000_000, false, false, 0, phases(12_900_000));
        Map<?, ?> phases = (Map<?, ?>) samples.result().get("phases");
        Map<?, ?> gpu = (Map<?, ?>) phases.get("gpu"), draw = (Map<?, ?>) phases.get("draw");
        assertEquals(0L, gpu.get("count")); assertNull(gpu.get("mean_ms"));
        assertEquals(1L, draw.get("count")); assertEquals(6, draw.get("p95_bin_ms"));
        assertEquals(1L, samples.result().get("approximate_deadline_frames"));
    }
    @Test public void overflowAndDroppedReportsInvalidateTheDiagnostic() {
        BenchFrameSamples samples = new BenchFrameSamples(100, 1);
        samples.add(101, 16, true, true, 0, phases(10));
        samples.add(150, 16, true, false, 0, phases(10));
        samples.add(151, 16, true, false, 2, phases(10));
        Map<String, Object> values = samples.result();
        assertEquals(1, values.get("first_draw_frames_excluded"));
        assertEquals(1, values.get("frame_buffer_overflow")); assertEquals(2L, values.get("dropped_reports"));
        assertEquals(false, values.get("valid"));
    }
}
