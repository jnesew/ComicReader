package io.github.jnesew.comicviewer.render;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PrefetchGenerationTest {
    @Test public void queuedWorkSurvivesRepeatedFrameButNotJumpOrCancellation() {
        PrefetchGeneration state = new PrefetchGeneration();
        assertTrue(state.begin(100));
        long first = state.current();
        assertFalse(state.begin(100));
        assertTrue(state.isCurrent(first));
        assertTrue(state.begin(900));
        assertFalse(state.isCurrent(first));
        final int[] published = {0};
        assertFalse(state.publishIfCurrent(first, () -> published[0]++));
        assertTrue(state.publishIfCurrent(state.current(), () -> published[0]++));
        assertTrue(published[0] == 1);
        long second = state.current();
        state.cancel();
        assertFalse(state.isCurrent(second));
        assertTrue(state.begin(900));
        assertTrue(state.isCurrent(state.current()));
    }

    @Test public void closeRejectsLateCompletionsAndAllFutureRequests() {
        PrefetchGeneration state = new PrefetchGeneration();
        state.begin(1);
        long pending = state.current();
        state.close();
        assertFalse(state.isCurrent(pending));
        assertFalse(state.begin(2));
        assertFalse(state.isCurrent(state.current()));
    }
}
