package io.github.jnesew.comicviewer.document;

import android.content.Context;
import android.net.Uri;

import io.github.jnesew.comicviewer.model.PageInfo;
import io.github.jnesew.comicviewer.model.ReadingProgress;
import io.github.jnesew.comicviewer.model.ReadingDirection;

import java.util.Collections;
import java.util.List;

/** A reader-only notice at the real missing issue's position, without source or database writes. */
public final class UnavailableComicDocument implements ComicDocument {
    private final String key;
    private final String title;
    private final boolean rightToLeft;
    private final List<PageInfo> layout = Collections.singletonList(
            new PageInfo("unavailable", 1000, 1000));

    public UnavailableComicDocument(ReadingProgress item) {
        key = item.uri;
        title = item.title;
        rightToLeft = ReadingDirection.RIGHT_TO_LEFT.equals(item.readingDirection);
    }

    @Override public boolean isUnavailable() { return true; }
    @Override public Uri uri() { return Uri.parse(key); }
    @Override public String key() { return key; }
    @Override public String title() { return title; }
    @Override public long documentSize() { return -1L; }
    @Override public long documentModified() { return -1L; }
    @Override public int count() { return 1; }
    @Override public int indexedPages() { return 0; }
    @Override public boolean isIndexComplete() { return true; }
    @Override public boolean suggestedRightToLeft() { return rightToLeft; }
    @Override public List<PageInfo> pages() { return layout; }
    @Override public List<PageInfo> snapshotPages() { return layout; }
    @Override public PageInfo page(int index) { return layout.get(index); }
    @Override public void buildPageIndex(Context context, ProgressCallback progress,
            IndexCallback callback) { /* No real pages to index. */ }
    @Override public void close() { }
}
