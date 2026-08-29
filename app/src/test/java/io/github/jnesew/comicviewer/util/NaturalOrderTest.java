package io.github.jnesew.comicviewer.util;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class NaturalOrderTest {
    @Test
    public void sortsNumericPageNamesNaturally() {
        ArrayList<String> names = new ArrayList<>(List.of(
                "page10.jpg", "page2.jpg", "page001.jpg", "Page1.jpg", "page20.jpg"));
        names.sort(NaturalOrder.INSTANCE);
        assertEquals(List.of("Page1.jpg", "page001.jpg", "page2.jpg", "page10.jpg", "page20.jpg"), names);
    }

    @Test
    public void handlesNumbersLargerThanLong() {
        String smaller = "p999999999999999999999999999999.jpg";
        String larger = "p1000000000000000000000000000000.jpg";
        org.junit.Assert.assertTrue(NaturalOrder.INSTANCE.compare(smaller, larger) < 0);
    }
}
