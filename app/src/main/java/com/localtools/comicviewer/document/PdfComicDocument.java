package com.localtools.comicviewer.document;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import com.localtools.comicviewer.R;
import com.localtools.comicviewer.model.PageInfo;
import com.localtools.comicviewer.util.CacheKey;
import com.localtools.comicviewer.util.InputLimits;
import com.localtools.comicviewer.util.InputLimits.LimitExceededException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.AbstractList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** Seekable PDF session backed only by Android's platform PdfRenderer. */
public final class PdfComicDocument implements ComicDocument {
    private static final int MAX_TILE_EDGE = 2048;
    private static final int MAX_TILE_PIXELS = 4 * 1024 * 1024;

    private final Uri uri;
    private final String key;
    private final String title;
    private final long documentSize;
    private final long documentModified;
    private final ParcelFileDescriptor descriptor;
    private final PdfRenderer renderer;
    private final File cachedDocument;
    private final AtomicReferenceArray<PageInfo> pageInfo;
    private final boolean[] indexed;
    private final List<PageInfo> pagesView;
    private volatile int indexedPages;
    private volatile boolean indexComplete;
    private volatile boolean closed;

    private PdfComicDocument(
            Uri uri,
            DocumentInfo document,
            ParcelFileDescriptor descriptor,
            PdfRenderer renderer,
            File cachedDocument,
            List<PageInfo> initialPages,
            boolean[] indexed,
            int indexedPages,
            boolean indexComplete) {
        this.uri = uri;
        this.key = uri.toString();
        this.title = document.title;
        this.documentSize = document.size;
        this.documentModified = document.modified;
        this.descriptor = descriptor;
        this.renderer = renderer;
        this.cachedDocument = cachedDocument;
        this.pageInfo = new AtomicReferenceArray<>(initialPages.size());
        for (int index = 0; index < initialPages.size(); index++) {
            this.pageInfo.set(index, initialPages.get(index));
        }
        this.indexed = indexed;
        this.indexedPages = indexedPages;
        this.indexComplete = indexComplete;
        this.pagesView = Collections.unmodifiableList(new AbstractList<>() {
            @Override
            public PageInfo get(int index) {
                return PdfComicDocument.this.pageInfo.get(index);
            }

            @Override
            public int size() {
                return PdfComicDocument.this.pageInfo.length();
            }
        });
    }

    public static PdfComicDocument open(
            Context context,
            Uri uri,
            DocumentInfo document,
            int preferredPage,
            List<PageInfo> cachedPages,
            long cachedDocumentSize,
            long cachedDocumentModified,
            ProgressCallback callback) throws IOException {
        update(callback, context.getString(R.string.reader_opening_named, document.title));
        ParcelFileDescriptor descriptor = null;
        PdfRenderer renderer = null;
        File cachedDocument = null;
        Throwable directFailure = null;
        try {
            InputLimits.validateDocumentSize(document.size);
            try {
                descriptor = context.getContentResolver().openFileDescriptor(uri, "r");
                if (descriptor != null) {
                    long descriptorSize = descriptor.getStatSize();
                    InputLimits.validateDocumentSize(descriptorSize);
                    if (descriptorSize < 0L) {
                        descriptor.close();
                        descriptor = null;
                    } else {
                        renderer = new PdfRenderer(descriptor);
                    }
                }
            } catch (LimitExceededException exception) {
                throw exception;
            } catch (IOException | IllegalArgumentException exception) {
                directFailure = exception;
                closeQuietly(renderer, descriptor);
                renderer = null;
                descriptor = null;
            }

            if (renderer == null) {
                update(callback, context.getString(R.string.reader_preparing_pdf));
                File cacheDirectory = new File(context.getCacheDir(), "pdf_sessions");
                if (!cacheDirectory.exists() && !cacheDirectory.mkdirs()) {
                    throw new IOException(context.getString(R.string.error_pdf_cache_create));
                }
                cachedDocument = new File(
                        cacheDirectory, CacheKey.digest(uri.toString()) + ".pdf");
                try (InputStream source = context.getContentResolver().openInputStream(uri)) {
                    if (source == null) {
                        throw new IOException(context.getString(R.string.error_document_unreadable));
                    }
                    InputLimits.copyAtomically(
                            source, cachedDocument, InputLimits.MAX_DOCUMENT_BYTES,
                            InputLimits.Reason.DOCUMENT_BYTES,
                            context.getString(R.string.error_pdf_cache_replace),
                            context.getString(R.string.error_pdf_cache_finish));
                }
                descriptor = ParcelFileDescriptor.open(
                        cachedDocument, ParcelFileDescriptor.MODE_READ_ONLY);
                renderer = new PdfRenderer(descriptor);
            }

            int pageCount = renderer.getPageCount();
            if (pageCount <= 0) throw new IOException(context.getString(R.string.error_pdf_no_pages));
            InputLimits.validatePageCount(pageCount);
            boolean cacheValid = fingerprintMatches(
                    document, cachedDocumentSize, cachedDocumentModified) &&
                    cachedIndexMatches(cachedPages, pageCount);
            ArrayList<PageInfo> initial = new ArrayList<>(pageCount);
            boolean[] indexed = new boolean[pageCount];
            int indexedCount;
            if (cacheValid) {
                initial.addAll(cachedPages);
                Arrays.fill(indexed, true);
                indexedCount = pageCount;
            } else {
                int target = clamp(preferredPage, 0, pageCount - 1);
                PageInfo targetInfo = pageInfo(renderer, target);
                for (int index = 0; index < pageCount; index++) {
                    initial.add(new PageInfo(pageName(index), targetInfo.width, targetInfo.height));
                }
                initial.set(target, targetInfo);
                indexed[target] = true;
                indexedCount = 1;
            }
            update(callback, context.getString(R.string.reader_ready));
            return new PdfComicDocument(uri, document, descriptor, renderer, cachedDocument,
                    initial, indexed, indexedCount, cacheValid);
        } catch (SecurityException exception) {
            closeQuietly(renderer, descriptor);
            deleteQuietly(cachedDocument);
            throw new IOException(context.getString(R.string.error_pdf_encrypted), exception);
        } catch (LimitExceededException exception) {
            closeQuietly(renderer, descriptor);
            deleteQuietly(cachedDocument);
            int message = exception.reason == InputLimits.Reason.PAGE_COUNT
                    ? R.string.error_too_many_pages : R.string.error_document_too_large;
            throw new IOException(context.getString(message), exception);
        } catch (IOException | RuntimeException exception) {
            closeQuietly(renderer, descriptor);
            deleteQuietly(cachedDocument);
            if (exception instanceof IOException ioException && directFailure == null) {
                throw ioException;
            }
            throw new IOException(context.getString(R.string.error_invalid_pdf), exception);
        }
    }

    @Override
    public void buildPageIndex(
            Context context, ProgressCallback progress, IndexCallback callback) throws IOException {
        if (Thread.currentThread().isInterrupted() || callback.isCancelled()) return;
        if (indexComplete) {
            callback.onComplete(snapshotPages());
            return;
        }
        for (int index = 0; index < count(); index++) {
            if (Thread.currentThread().isInterrupted() || callback.isCancelled()) return;
            synchronized (this) {
                ensureOpen();
                if (!indexed[index]) {
                    pageInfo.set(index, pageInfo(renderer, index));
                    indexed[index] = true;
                    indexedPages++;
                }
            }
            if (index == 0 || index % 12 == 0 || index == count() - 1) {
                update(progress, context.getString(
                        R.string.reader_reading_page_information, index + 1, count()));
                callback.onProgress(indexedPages, count());
                callback.onPagesUpdated();
            }
        }
        if (Thread.currentThread().isInterrupted() || callback.isCancelled()) return;
        indexComplete = true;
        callback.onComplete(snapshotPages());
    }

    @Override
    public Uri uri() {
        return uri;
    }

    @Override
    public String key() {
        return key;
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    public long documentSize() {
        return documentSize;
    }

    @Override
    public long documentModified() {
        return documentModified;
    }

    @Override
    public int count() {
        return pageInfo.length();
    }

    @Override
    public int indexedPages() {
        return indexedPages;
    }

    @Override
    public boolean isIndexComplete() {
        return indexComplete;
    }

    @Override
    public boolean suggestedRightToLeft() {
        return false;
    }

    @Override
    public List<PageInfo> pages() {
        return pagesView;
    }

    @Override
    public List<PageInfo> snapshotPages() {
        ArrayList<PageInfo> result = new ArrayList<>(count());
        for (int index = 0; index < count(); index++) result.add(pageInfo.get(index));
        return result;
    }

    @Override
    public PageInfo page(int index) {
        return pageInfo.get(index);
    }

    @Override
    public boolean supportsRenderedTiles() {
        return true;
    }

    @Override
    public synchronized Bitmap renderTile(int pageIndex, Rect source, float renderScale)
            throws IOException {
        ensureOpen();
        float scale = Math.max(0.01f, renderScale);
        int width = Math.max(1, Math.round(source.width() * scale));
        int height = Math.max(1, Math.round(source.height() * scale));
        validateBitmapSize(width, height);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(android.graphics.Color.WHITE);
        Matrix matrix = new Matrix();
        matrix.setValues(new float[]{
                scale, 0f, -source.left * scale,
                0f, scale, -source.top * scale,
                0f, 0f, 1f
        });
        try (PdfRenderer.Page page = renderer.openPage(pageIndex)) {
            page.render(bitmap, new Rect(0, 0, width, height), matrix,
                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            return bitmap;
        } catch (RuntimeException exception) {
            bitmap.recycle();
            throw new IOException("Android could not render this PDF page.", exception);
        }
    }

    @Override
    public synchronized Bitmap renderCover(int maxWidth, int maxHeight) throws IOException {
        return renderPreview(0, maxWidth, maxHeight);
    }

    @Override
    public synchronized Bitmap renderPreview(int pageIndex, int maxWidth, int maxHeight)
            throws IOException {
        ensureOpen();
        try (PdfRenderer.Page page = renderer.openPage(pageIndex)) {
            InputLimits.validateImageDimensions(page.getWidth(), page.getHeight());
            float scale = Math.min((float) maxWidth / Math.max(1, page.getWidth()),
                    (float) maxHeight / Math.max(1, page.getHeight()));
            scale = Math.max(0.01f, scale);
            int width = Math.max(1, Math.round(page.getWidth() * scale));
            int height = Math.max(1, Math.round(page.getHeight() * scale));
            validateBitmapSize(width, height);
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(android.graphics.Color.WHITE);
            try {
                Matrix matrix = new Matrix();
                matrix.setScale(scale, scale);
                page.render(bitmap, new Rect(0, 0, width, height), matrix,
                        PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                return bitmap;
            } catch (RuntimeException exception) {
                bitmap.recycle();
                throw exception;
            }
        } catch (RuntimeException exception) {
            throw new IOException("Android could not render this PDF page preview.", exception);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        closeQuietly(renderer, descriptor);
        deleteQuietly(cachedDocument);
    }

    private synchronized void ensureOpen() throws IOException {
        if (closed) throw new IOException("The PDF has been closed.");
    }

    private static PageInfo pageInfo(PdfRenderer renderer, int index) throws IOException {
        try (PdfRenderer.Page page = renderer.openPage(index)) {
            InputLimits.validateImageDimensions(page.getWidth(), page.getHeight());
            return new PageInfo(pageName(index), page.getWidth(), page.getHeight());
        } catch (RuntimeException exception) {
            throw new IOException("Android could not inspect this PDF page.", exception);
        }
    }

    private static boolean cachedIndexMatches(List<PageInfo> pages, int count) {
        if (pages == null || pages.size() != count || pages.isEmpty()) return false;
        for (int index = 0; index < count; index++) {
            PageInfo page = pages.get(index);
            if (!pageName(index).equals(page.name)) return false;
            try {
                InputLimits.validateImageDimensions(page.width, page.height);
            } catch (LimitExceededException exception) {
                return false;
            }
        }
        return true;
    }

    private static boolean fingerprintMatches(DocumentInfo current, long oldSize, long oldModified) {
        if (current.size >= 0L && oldSize >= 0L && current.size != oldSize) return false;
        return current.modified < 0L || oldModified < 0L || current.modified == oldModified;
    }

    private static String pageName(int index) {
        return String.format(Locale.ROOT, "pdf-page-%05d", index + 1);
    }

    private static void validateBitmapSize(int width, int height) throws IOException {
        if (width > MAX_TILE_EDGE || height > MAX_TILE_EDGE ||
                (long) width * height > MAX_TILE_PIXELS) {
            throw new IOException("The requested PDF tile is too large.");
        }
    }

    private static void update(ProgressCallback callback, String message) {
        if (callback != null) callback.update(message);
    }

    private static void closeQuietly(PdfRenderer renderer, ParcelFileDescriptor descriptor) {
        try {
            if (renderer != null) renderer.close();
        } catch (RuntimeException ignored) {
        }
        try {
            if (descriptor != null) descriptor.close();
        } catch (IOException ignored) {
        }
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists()) file.delete();
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
