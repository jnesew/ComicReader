package com.localtools.comicviewer.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ReadingDirectionTest {
    @Test
    public void normalizesUnknownValuesToAuto() {
        assertEquals(ReadingDirection.AUTO, ReadingDirection.normalize(null));
        assertEquals(ReadingDirection.AUTO, ReadingDirection.normalize(""));
        assertEquals(ReadingDirection.AUTO, ReadingDirection.normalize("sideways"));
        assertEquals(ReadingDirection.LEFT_TO_RIGHT, ReadingDirection.normalize("ltr"));
        assertEquals(ReadingDirection.RIGHT_TO_LEFT, ReadingDirection.normalize("rtl"));
    }

    @Test
    public void autoUsesDocumentSuggestion() {
        assertFalse(ReadingDirection.isRightToLeft(ReadingDirection.AUTO, false));
        assertTrue(ReadingDirection.isRightToLeft(ReadingDirection.AUTO, true));
    }

    @Test
    public void explicitDirectionOverridesDocumentSuggestion() {
        assertFalse(ReadingDirection.isRightToLeft(ReadingDirection.LEFT_TO_RIGHT, true));
        assertTrue(ReadingDirection.isRightToLeft(ReadingDirection.RIGHT_TO_LEFT, false));
    }
}
