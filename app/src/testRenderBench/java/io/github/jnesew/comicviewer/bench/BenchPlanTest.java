package io.github.jnesew.comicviewer.bench;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class BenchPlanTest {
    @Test public void candidateBatteryUsesMatchedBaselineAndTwoBoundedCapacities() {
        BenchPlan plan = new BenchPlan("candidate");
        assertTrue(plan.roundTwo()); assertEquals(8, plan.scenarios().length);
        List<BenchPolicy> policies = plan.policies("raster.cbz");
        assertEquals(3, policies.size());
        assertFalse(policies.get(0).tuning.prioritized);
        assertTrue(policies.get(1).tuning.prioritized);
        assertEquals(0, policies.get(1).tuning.cacheMiB);
        assertEquals(64, policies.get(2).tuning.cacheMiB);
        assertEquals(48, plan.scenarios().length * policies.size() * plan.repeats());
        BenchPlan sustained = new BenchPlan("candidate_sustained");
        assertTrue(sustained.sustained()); assertEquals(5, sustained.cycles());
        assertEquals(3, sustained.scenarios().length);
        assertEquals(2, new BenchPlan("smoke_candidate_session").cycles());
    }
    @Test public void focusedBatteryPairsOneComicAcrossThreeModesAndSeparatesRefill() {
        BenchPlan plan = new BenchPlan("focused"); Set<String> modes = new HashSet<>(); int cases = 0;
        for (String[] scenario : plan.scenarios()) {
            if (scenario[0].equals("raster.cbz")) modes.add(scenario[1]);
            List<BenchPolicy> policies = plan.policies(scenario[0]);
            assertEquals(policies.size(), policies.stream().map(p -> p.id).distinct().count());
            cases += policies.size() * plan.repeats();
        }
        assertEquals(Set.of("single", "spread", "continuous"), modes); assertEquals(102, cases);
        List<BenchPolicy> policies = plan.policies("raster.cbz");
        BenchPolicy admission = policies.stream().filter(p -> p.id.equals("admission_only")).findFirst().orElseThrow();
        assertTrue(admission.tuning.boundedAdmission()); assertFalse(admission.tuning.refill);
        assertEquals(0, admission.tuning.cacheMiB);
        BenchPolicy refill = policies.stream().filter(p -> p.id.equals("refill_64")).findFirst().orElseThrow();
        assertTrue(refill.tuning.refill); assertFalse(refill.tuning.byteLookahead); assertEquals(64, refill.tuning.cacheMiB);
    }
    @Test public void ordersReverseWithinAWorkloadAndRotateAcrossWorkloads() {
        BenchPlan plan = new BenchPlan("focused");
        List<String> first = new ArrayList<>(plan.order("raster.cbz", 2, 0).stream().map(p -> p.id).toList());
        Collections.reverse(first);
        assertEquals(first, plan.order("raster.cbz", 2, 1).stream().map(p -> p.id).toList());
        assertNotEquals(plan.order("raster.cbz", 0, 0).get(0).id, plan.order("raster.cbz", 1, 0).get(0).id);
    }
    @Test public void sustainedRunsUseMultipleCyclesWithoutPretendingToBeFreshRepeats() {
        BenchPlan plan = new BenchPlan("sustained");
        assertTrue(plan.sustained()); assertEquals(1, plan.repeats()); assertEquals(5, plan.cycles());
        assertEquals(3, plan.scenarios().length); assertEquals(3, plan.policies("images.epub").size());
        assertEquals(2, new BenchPlan("smoke_v2_session").cycles());
    }
}
