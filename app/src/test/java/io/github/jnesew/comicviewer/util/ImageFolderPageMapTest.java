package io.github.jnesew.comicviewer.util;

import io.github.jnesew.comicviewer.model.PageInfo;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class ImageFolderPageMapTest {
    private static PageInfo page(String identity) { return new PageInfo(identity, 100, 200); }
    @Test public void insertionReorderingAndRemovalFollowDocumentIdentity() {
        List<PageInfo> before = List.of(page("a"), page("b"), page("c"));
        ImageFolderPageMap reordered = new ImageFolderPageMap(before,
                List.of(page("new"), page("c"), page("a")));
        assertEquals(2, reordered.remap(0));
        assertEquals(-1, reordered.remap(1));
        assertEquals(1, reordered.remap(2));
        assertEquals(-1, reordered.remap(-1));
        assertEquals(-1, reordered.remap(3));
    }
}
