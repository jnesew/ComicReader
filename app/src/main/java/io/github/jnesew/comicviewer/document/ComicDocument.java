package io.github.jnesew.comicviewer.document;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;

import io.github.jnesew.comicviewer.model.PageInfo;
import io.github.jnesew.comicviewer.model.SeriesMetadata;
import io.github.jnesew.comicviewer.util.PreviewSizing;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** Common random-access surface used by archive and platform-rendered comic documents. */
public interface ComicDocument extends Closeable {
    interface ProgressCallback {
        void update(String message);
    }

    interface IndexCallback {
        default boolean isCancelled() {
            return false;
        }

        void onProgress(int indexedPages, int pageCount);
        void onPagesUpdated();
        void onComplete(List<PageInfo> pages);
    }

    Uri uri();
    String key();
    String title();
    long documentSize();
    long documentModified();
    int count();
    int indexedPages();
    boolean isIndexComplete();
    boolean suggestedRightToLeft();

    default SeriesMetadata seriesMetadata() {
        return SeriesMetadata.EMPTY;
    }

    List<PageInfo> pages();
    List<PageInfo> snapshotPages();
    PageInfo page(int index);

    void buildPageIndex(Context context, ProgressCallback progress, IndexCallback callback)
            throws IOException;

    /** True when tiles are rendered directly rather than decoded from a raster page stream. */
    default boolean supportsRenderedTiles() {
        return false;
    }

    /** Raster backends expose their original page data to Android's image decoders. */
    default InputStream openPage(int index) throws IOException {
        throw new IOException("This document does not expose raster page streams.");
    }

    default void extractPage(int index, File destination) throws IOException {
        throw new IOException("This document does not expose raster page files.");
    }

    default void extractCover(File destination) throws IOException {
        extractPage(0, destination);
    }

    /** Platform-rendered backends return a bitmap for the requested source-page rectangle. */
    default Bitmap renderTile(int pageIndex, Rect source, float renderScale) throws IOException {
        throw new IOException("This document does not support rendered tiles.");
    }

    /** Rendered backends can provide an accurate full-page thumbnail before indexing completes. */
    default Bitmap renderPreview(int pageIndex, int maxWidth, int maxHeight) throws IOException {
        PageInfo info = page(pageIndex);
        PreviewSizing.Size target = PreviewSizing.fitInside(
                info.width, info.height, maxWidth, maxHeight, true);
        float scale = Math.min(
                (float) target.width / info.width,
                (float) target.height / info.height);
        return renderTile(pageIndex, new Rect(0, 0, info.width, info.height), scale);
    }

    default Bitmap renderCover(int maxWidth, int maxHeight) throws IOException {
        throw new IOException("This document does not support rendered covers.");
    }

    @Override
    void close();
}
