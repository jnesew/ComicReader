package io.github.jnesew.comicviewer.render;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class ContinuousPageMapTest {
    private static ContinuousPageMap map(ContinuousPageMap.Issue... issues) {
        return new ContinuousPageMap(List.of(issues));
    }
    private static ContinuousPageMap.Issue issue(String key, int count) {
        return new ContinuousPageMap.Issue(key, count);
    }
    @Test public void everyPageRoundTripsAcrossIssueBoundaries() {
        var map = map(issue("a", 3), issue("notice", 1), issue("b", 7));
        String[] keys = {"a", "notice", "b"};
        assertEquals(11, map.size());
        for (int global = 0; global < map.size(); global++) {
            int issue = map.issueFor(global);
            assertEquals(global, map.globalPageFor(keys[issue], map.localPageFor(global), 0));
        }
        assertEquals(3, map.start(1)); assertEquals(3, map.end(1));
        assertEquals(4, map.start(2)); assertEquals(10, map.end(2));
    }
    @Test public void prependAndEvictionPreserveDocumentLocalAnchor() {
        var anchor = new ContinuousPageMap.Anchor("b", 4, .37f);
        assertEquals(new ContinuousPageMap.Position(7, .37f),
                map(issue("a", 3), issue("b", 8)).restore(anchor, 0));
        assertEquals(new ContinuousPageMap.Position(4, .37f),
                map(issue("b", 8), issue("c", 2)).restore(anchor, 0));
    }
    @Test public void indexCountChangeClampsOnlyPageAndPreservesRatio() {
        var anchor = new ContinuousPageMap.Anchor("b", 7, .8f);
        assertEquals(new ContinuousPageMap.Position(4, .8f),
                map(issue("a", 3), issue("b", 2)).restore(anchor, 0));
    }
    @Test public void missingAnchorUsesIssueAtFallbackPosition() {
        var map = map(issue("a", 3), issue("b", 4));
        assertEquals(4, map.globalPageFor("removed", 1, 5));
        assertEquals(1, map.globalPageFor("removed", 1, 100));
        assertEquals(3, map.globalPageFor("b", -10, 0));
        assertEquals(6, map.globalPageFor("b", 100, 0));
    }
    @Test public void emptyMapAndOutOfBoundsHaveStableFallbacks() {
        var map = map();
        assertEquals(0, map.size()); assertEquals(-1, map.issueFor(0));
        assertEquals(0, map.localPageFor(-5)); assertEquals(0, map.globalPageFor("none", 100, 0));
    }
}
