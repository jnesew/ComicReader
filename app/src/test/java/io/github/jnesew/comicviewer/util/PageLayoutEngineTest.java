package io.github.jnesew.comicviewer.util;

import io.github.jnesew.comicviewer.model.PageInfo;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class PageLayoutEngineTest {
    @Test
    public void mapsContinuousPositionToPageAndRatio() {
        PageLayoutEngine layout = new PageLayoutEngine();
        layout.calculate(List.of(
                new PageInfo("1.jpg", 100, 200),
                new PageInfo("2.jpg", 100, 100)), 100, 1, 10);

        assertEquals(10f, layout.top(0), 0.001f);
        assertEquals(220f, layout.top(1), 0.001f);
        assertEquals(1, layout.pageAt(270f));
        assertEquals(0.5f, layout.pageRatio(1, 270f), 0.001f);
        assertEquals(270f, layout.positionFor(1, 0.5f), 0.001f);
    }

    @Test
    public void includesIssueSeparatorsAndBoundarySpace() {
        PageLayoutEngine layout = new PageLayoutEngine();
        layout.calculate(List.of(
                new PageInfo("1.jpg", 100, 100),
                new PageInfo("2.jpg", 100, 100)),
                100, 1, 10, new float[]{40, 60}, 30);

        assertEquals(50f, layout.top(0), 0.001f);
        assertEquals(220f, layout.top(1), 0.001f);
        assertEquals(360f, layout.documentHeight(), 0.001f);
        assertEquals(0, layout.pageAt(219f));
        assertEquals(1, layout.pageAt(220f));
    }

    @Test
    public void shortNextIssueCanReachActivationPosition() {
        PageLayoutEngine layout = new PageLayoutEngine();
        layout.calculate(List.of(
                new PageInfo("issue-1.jpg", 100, 70),
                new PageInfo("issue-2.jpg", 100, 70)),
                100, 1, 8, new float[]{58, 72}, 58);

        // The old bottom-aligned limit leaves issue 1 active, so issue 3 never loads.
        float oldLimit = Math.max(0f, layout.documentHeight() - 200f);
        assertEquals(0, layout.pageAt(oldLimit + 1f));
        assertEquals(1, layout.pageAt(layout.maximumScroll(200f) + 1f));
    }

    @Test
    public void shortIssueAnchorsSurviveRepeatedWindowShifts() {
        // Simulate the bounded previous/current/next window in both directions, with
        // even three issues fitting inside the viewport at the smaller zoom levels.
        for (float zoom : new float[]{0.6f, 1f, 2f}) {
            for (float viewport : new float[]{200f, 1000f}) {
                for (int active : new int[]{0, 1, 2, 3, 4, 5, 4, 3, 2, 1, 0}) {
                    int first = Math.max(0, active - 1);
                    int last = Math.min(5, active + 1);
                    java.util.ArrayList<PageInfo> pages = new java.util.ArrayList<>();
                    float[] separators = new float[last - first + 1];
                    for (int issue = first; issue <= last; issue++) {
                        pages.add(new PageInfo("issue-" + issue, 100, 70));
                        separators[issue - first] = issue == first ? 58f : 72f;
                    }
                    PageLayoutEngine layout = new PageLayoutEngine();
                    layout.calculate(pages, 100, zoom, 8, separators, 58);
                    int anchor = active - first;
                    float restored = Math.min(layout.positionFor(anchor, 0f),
                            layout.maximumScroll(viewport));
                    assertEquals(anchor, layout.pageAt(restored + 1f));
                    assertEquals(last - first,
                            layout.pageAt(layout.maximumScroll(viewport) + 1f));
                }
            }
        }
    }

    @Test
    public void tallFinalPageRetainsBottomAlignedScrollLimit() {
        PageLayoutEngine layout = new PageLayoutEngine();
        layout.calculate(List.of(new PageInfo("tall.jpg", 100, 500)),
                100, 1, 8, new float[]{58}, 58);
        assertEquals(layout.documentHeight() - 200f, layout.maximumScroll(200f), 0.001f);
        layout.calculate(List.of(), 100, 1, 8);
        assertEquals(0f, layout.maximumScroll(200f), 0.001f);
    }
}
