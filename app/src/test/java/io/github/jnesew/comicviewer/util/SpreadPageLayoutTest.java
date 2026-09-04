package io.github.jnesew.comicviewer.util;

import io.github.jnesew.comicviewer.model.PageInfo;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public final class SpreadPageLayoutTest {
    @Test
    public void keepsCoverAloneThenPairsPortraitPages() {
        SpreadPageLayout layout = layout(
                portrait("cover"), portrait("2"), portrait("3"), portrait("4"));

        assertSpread(layout, 0, 0, 0);
        assertSpread(layout, 1, 1, 2);
        assertSpread(layout, 2, 1, 2);
        assertSpread(layout, 3, 3, 3);
        assertEquals(1, layout.adjacentAnchor(0, 1));
        assertEquals(3, layout.adjacentAnchor(1, 1));
        assertEquals(1, layout.adjacentAnchor(3, -1));
    }

    @Test
    public void keepsLandscapePagesAndInterruptedPortraitsAlone() {
        SpreadPageLayout layout = layout(
                portrait("cover"), portrait("2"), landscape("spread"),
                portrait("4"), portrait("5"));

        assertSpread(layout, 1, 1, 1);
        assertSpread(layout, 2, 2, 2);
        assertSpread(layout, 3, 3, 4);
    }

    @Test
    public void reversesVisualSidesForRightToLeftReading() {
        SpreadPageLayout layout = layout(
                portrait("cover"), portrait("2"), portrait("3"));

        assertEquals(1, layout.leftPage(1, false));
        assertEquals(2, layout.rightPage(1, false));
        assertEquals(2, layout.leftPage(1, true));
        assertEquals(1, layout.rightPage(1, true));
    }

    private static SpreadPageLayout layout(PageInfo... pages) {
        SpreadPageLayout layout = new SpreadPageLayout();
        layout.calculate(List.of(pages));
        return layout;
    }

    private static PageInfo portrait(String name) {
        return new PageInfo(name, 100, 160);
    }

    private static PageInfo landscape(String name) {
        return new PageInfo(name, 180, 100);
    }

    private static void assertSpread(
            SpreadPageLayout layout, int page, int expectedAnchor, int expectedEnd) {
        assertEquals(expectedAnchor, layout.anchorFor(page));
        assertEquals(expectedEnd, layout.endFor(page));
    }
}
