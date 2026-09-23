package io.github.jnesew.comicviewer.model;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class ReaderDefaultsTest {
    @Test public void inheritedValuesFollowChangedGlobalDefaults() {
        assertEquals(ReaderDefaults.CONTINUOUS,
                ReaderDefaults.layout(ReaderDefaults.SINGLE, false, ReaderDefaults.CONTINUOUS));
        assertEquals(ReaderDefaults.SPREAD,
                ReaderDefaults.layout(ReaderDefaults.SINGLE, false, ReaderDefaults.SPREAD));
        assertEquals(ReadingDirection.RIGHT_TO_LEFT,
                ReaderDefaults.direction(ReadingDirection.AUTO, false,
                        ReadingDirection.RIGHT_TO_LEFT));
    }

    @Test public void explicitAutoAndLayoutOverrideGlobalSettings() {
        assertEquals(ReaderDefaults.SINGLE,
                ReaderDefaults.layout(ReaderDefaults.SINGLE, true, ReaderDefaults.CONTINUOUS));
        assertEquals(ReadingDirection.AUTO,
                ReaderDefaults.direction(ReadingDirection.AUTO, true,
                        ReadingDirection.RIGHT_TO_LEFT));
        assertEquals(ReadingDirection.LEFT_TO_RIGHT,
                ReaderDefaults.direction(ReadingDirection.LEFT_TO_RIGHT, true,
                        ReadingDirection.RIGHT_TO_LEFT));
    }

    @Test public void malformedSettingsUseBuiltInFallback() {
        assertEquals(ReaderDefaults.SINGLE, ReaderDefaults.layout(null, false, "broken"));
        assertEquals(ReadingDirection.AUTO,
                ReaderDefaults.direction(null, false, "broken"));
    }
}
