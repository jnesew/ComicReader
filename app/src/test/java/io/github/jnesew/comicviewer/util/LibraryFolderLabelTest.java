package io.github.jnesew.comicviewer.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class LibraryFolderLabelTest {
    @Test
    public void providerFallbackBecomesFolderName() {
        assertEquals("Comics", LibraryFolderLabel.compact("primary:comics"));
    }

    @Test
    public void nestedFallbackUsesLeafFolder() {
        assertEquals("Volumes", LibraryFolderLabel.compact("primary:Comics/Volumes"));
    }

    @Test
    public void ordinaryDisplayNameIsPreserved() {
        assertEquals("Little Nemo", LibraryFolderLabel.compact("Little Nemo"));
    }
}
