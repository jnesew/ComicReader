package io.github.jnesew.comicviewer.bench;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Versioned workload definitions; no device-name policy branches. */
final class BenchPlan {
    static final String PROTOCOL = "render-bench-2";
    static final String[] SUITES = {"scrolling", "candidate", "candidate_sustained", "focused", "sustained", "quick", "individual", "soak"};
    final String suite;
    BenchPlan(String suite) { this.suite = suite; }
    boolean shortRun() { return suite.startsWith("smoke"); }
    boolean candidate() { return suite.equals("candidate") || suite.equals("candidate_sustained") || suite.startsWith("smoke_candidate"); }
    boolean scrolling() { return suite.equals("scrolling") || suite.equals("smoke_scrolling"); }
    boolean roundTwo() { return scrolling() || candidate() || suite.equals("focused") || suite.equals("sustained") || suite.startsWith("smoke_v2"); }
    boolean sustained() { return suite.equals("candidate_sustained") || suite.equals("smoke_candidate_session") || suite.equals("sustained") || suite.equals("smoke_v2_session"); }
    int repeats() { return shortRun() || sustained() ? 1 : suite.equals("soak") ? 8 : 2; }
    int cycles() { return sustained() ? (shortRun() ? 2 : 5) : 1; }
    int[] speeds(int repeat) {
        int[] speeds = !scrolling() ? new int[]{0} : shortRun() ? new int[]{2, 8} : new int[]{1, 2, 4, 8};
        if ((repeat & 1) != 0) for (int i = 0; i < speeds.length / 2; i++) {
            int swap = speeds[i]; speeds[i] = speeds[speeds.length - 1 - i]; speeds[speeds.length - 1 - i] = swap;
        }
        return speeds;
    }

    String[][] scenarios() {
        if (scrolling()) return new String[][]{{"raster.cbz", "continuous"}, {"strips.cbz", "continuous"}, {"issue-a.cbz", "series"}};
        if (sustained()) return new String[][]{{"raster.cbz", "continuous"}, {"images.epub", "continuous"}, {"issue-a.cbz", "series"}};
        if (roundTwo()) return new String[][]{{"raster.cbz", "single"}, {"raster.cbz", "spread"},
                {"raster.cbz", "continuous"}, {"strips.cbz", "continuous"}, {"vector.pdf", "spread"},
                {"images.epub", "continuous"}, {"fallback.cbz", "single"}, {"issue-a.cbz", "series"}};
        if (shortRun()) return new String[][]{{"raster.cbz", "single"}, {"vector.pdf", "spread"},
                {"strips.cbz", "continuous"}, {"images.epub", "continuous"}, {"fallback.cbz", "single"}, {"issue-a.cbz", "series"}};
        if (suite.equals("individual")) return new String[][]{{"raster.cbz", "single"}, {"raster.cbz", "spread"},
                {"strips.cbz", "continuous"}, {"vector.pdf", "spread"}, {"images.epub", "continuous"},
                {"fallback.cbz", "single"}, {"issue-a.cbz", "series"}};
        return new String[][]{{"raster.cbz", "single"}, {"strips.cbz", "continuous"}, {"vector.pdf", "spread"}};
    }
    List<BenchPolicy> policies(String fixture) {
        if (!roundTwo()) return BenchPolicy.policies(suite);
        BenchPolicy baseline = new BenchPolicy("baseline_high", false, false, false, false, 0);
        if (scrolling()) return List.of(baseline,
                new BenchPolicy("reader_candidate", io.github.jnesew.comicviewer.render.RenderTuning.READER),
                new BenchPolicy("scroll_pipeline_2", io.github.jnesew.comicviewer.render.RenderTuning.scrolling(2, 1)),
                new BenchPolicy("scroll_tall_bands_2", io.github.jnesew.comicviewer.render.RenderTuning.scrolling(1, 2)),
                new BenchPolicy("scroll_pipeline_and_bands_2", io.github.jnesew.comicviewer.render.RenderTuning.scrolling(2, 2)));
        if (candidate()) return List.of(baseline,
                new BenchPolicy("reader_candidate", io.github.jnesew.comicviewer.render.RenderTuning.READER),
                new BenchPolicy("reader_candidate_64", io.github.jnesew.comicviewer.render.RenderTuning.reader(64)));
        BenchPolicy capacity = new BenchPolicy("capacity_admission_64", false, false, false, false, 64);
        BenchPolicy refill = new BenchPolicy("refill_64", false, true, false, false, 64);
        if (sustained()) return List.of(baseline, capacity, refill);
        List<BenchPolicy> result = new ArrayList<>(List.of(baseline,
                new BenchPolicy("admission_only", false, false, false, false, 0, true),
                new BenchPolicy("refill_only", false, true, false, false, 0), capacity, refill,
                new BenchPolicy("combined_96_mib_requested", true, true, true, true, 96)));
        if (fixture.equals("vector.pdf")) {
            result.add(new BenchPolicy("future_frame_only", false, false, true, false, 0));
            result.add(new BenchPolicy("frame_refill", false, true, true, false, 0));
            result.add(new BenchPolicy("lookahead_only", true, false, false, false, 0));
        }
        return result;
    }
    List<BenchPolicy> order(String fixture, int scenario, int repeat) {
        List<BenchPolicy> result = new ArrayList<>(policies(fixture));
        // Rotate by workload and reverse the second repeat; order is exported per case.
        if (roundTwo()) Collections.rotate(result, -(scenario % result.size()));
        if ((repeat & 1) != 0) Collections.reverse(result);
        return result;
    }
}
