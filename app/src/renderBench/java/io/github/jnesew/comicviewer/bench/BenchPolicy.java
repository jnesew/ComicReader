package io.github.jnesew.comicviewer.bench;

import io.github.jnesew.comicviewer.render.RenderTuning;
import java.util.List;

final class BenchPolicy {
    final String id;
    final RenderTuning tuning;
    BenchPolicy(String id, boolean bytes, boolean refill, boolean frame, boolean history, int mib) {
        this.id = id;
        tuning = new RenderTuning(bytes, refill, frame, history, mib);
    }
    static List<BenchPolicy> policies(String suite) {
        BenchPolicy baseline = new BenchPolicy("baseline_high", false, false, false, false, 0);
        BenchPolicy combined = new BenchPolicy("combined_original_cap", true, true, true, true, 0);
        BenchPolicy cap = new BenchPolicy("combined_96_mib_requested", true, true, true, true, 96);
        if ("smoke".equals(suite)) return List.of(baseline, combined);
        if (!"individual".equals(suite)) return List.of(baseline, combined, cap);
        return List.of(baseline,
                new BenchPolicy("lookahead_only", true, false, false, false, 0),
                new BenchPolicy("refill_only", false, true, false, false, 0),
                new BenchPolicy("future_frame_only", false, false, true, false, 0),
                new BenchPolicy("history_fallback_only", false, false, false, true, 0),
                new BenchPolicy("capacity_admission_64", false, false, false, false, 64),
                new BenchPolicy("capacity_admission_96", false, false, false, false, 96),
                combined, cap);
    }
}
