package io.github.jnesew.comicviewer.model;

import android.net.Uri;
import android.provider.DocumentsContract;

/** One explicitly selected document tree. Provider identifiers remain opaque. */
public final class LibraryFolder {
    public final String uri;
    public final String label;
    public final String location;

    public LibraryFolder(String uri, String label, String location) {
        this.uri = uri;
        this.label = label;
        this.location = location;
    }

    public boolean sameTree(String other) {
        if (uri.equals(other)) return true;
        try {
            Uri left = Uri.parse(uri);
            Uri right = Uri.parse(other);
            return left.getAuthority() != null && left.getAuthority().equals(right.getAuthority()) &&
                    DocumentsContract.getTreeDocumentId(left).equals(
                            DocumentsContract.getTreeDocumentId(right));
        } catch (RuntimeException invalidTree) {
            return false;
        }
    }
}
