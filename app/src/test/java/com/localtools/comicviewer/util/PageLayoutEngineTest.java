package com.localtools.comicviewer.util;

import com.localtools.comicviewer.model.PageInfo;

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
}
