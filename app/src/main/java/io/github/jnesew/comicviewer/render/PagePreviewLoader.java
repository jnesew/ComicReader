package io.github.jnesew.comicviewer.render;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import io.github.jnesew.comicviewer.document.ComicDocument;
import io.github.jnesew.comicviewer.util.InputLimits;
import io.github.jnesew.comicviewer.util.PreviewSizing;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Decodes small slider thumbnails on demand. Rapid scrub events are coalesced so the single
 * worker always moves to the newest requested page instead of building an unbounded queue.
 */
public final class PagePreviewLoader implements AutoCloseable {
    public interface Callback {
        void onPreviewReady(int page, Bitmap bitmap);
        void onPreviewUnavailable(int page);
    }

    private final ComicDocument document;
    private final int maxWidth;
    private final int maxHeight;
    private final Callback callback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "comic-page-preview");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    private final LruCache<Integer, Bitmap> cache;
    private final Object requestLock = new Object();

    private boolean workerRunning;
    private int pendingPage = -1;
    private long pendingVersion;
    private long requestVersion;
    private int activePage = -1;
    private volatile boolean closed;

    public PagePreviewLoader(
            ComicDocument document,
            int maxWidth,
            int maxHeight,
            Callback callback) {
        if (maxWidth <= 0 || maxHeight <= 0) {
            throw new IllegalArgumentException("Preview bounds must be positive.");
        }
        this.document = document;
        this.maxWidth = maxWidth;
        this.maxHeight = maxHeight;
        this.callback = callback;

        long heapKb = Runtime.getRuntime().maxMemory() / 1024L;
        int cacheKb = (int) Math.min(12L * 1024L, Math.max(4L * 1024L, heapKb / 32L));
        cache = new LruCache<>(cacheKb) {
            @Override
            protected int sizeOf(Integer page, Bitmap bitmap) {
                return Math.max(1, bitmap.getAllocationByteCount() / 1024);
            }
        };
    }

    public void request(int requestedPage) {
        int page = Math.max(0, Math.min(requestedPage, document.count() - 1));
        Bitmap cached = cache.get(page);
        long version;
        synchronized (requestLock) {
            if (closed) return;
            version = ++requestVersion;
            activePage = page;
            if (cached != null && !cached.isRecycled()) {
                pendingPage = -1;
            } else {
                pendingPage = page;
                pendingVersion = version;
                startWorkerLocked();
                return;
            }
        }
        postReady(page, cached, version);
    }

    public void cancel() {
        synchronized (requestLock) {
            requestVersion++;
            activePage = -1;
            pendingPage = -1;
        }
    }

    public void trimMemory() {
        cache.evictAll();
    }

    @Override
    public void close() {
        synchronized (requestLock) {
            if (closed) return;
            closed = true;
            requestVersion++;
            activePage = -1;
            pendingPage = -1;
        }
        executor.shutdownNow();
        cache.evictAll();
    }

    private void startWorkerLocked() {
        if (workerRunning) return;
        workerRunning = true;
        try {
            executor.execute(this::drainRequests);
        } catch (RejectedExecutionException ignored) {
            workerRunning = false;
        }
    }

    private void drainRequests() {
        while (true) {
            int page;
            long version;
            synchronized (requestLock) {
                if (closed || pendingPage < 0) {
                    workerRunning = false;
                    return;
                }
                page = pendingPage;
                version = pendingVersion;
                pendingPage = -1;
            }

            Bitmap bitmap = cache.get(page);
            boolean available = bitmap != null && !bitmap.isRecycled();
            if (!available) {
                try {
                    bitmap = decode(page);
                    available = bitmap != null;
                    if (available && !closed) cache.put(page, bitmap);
                } catch (IOException | RuntimeException error) {
                    available = false;
                } catch (OutOfMemoryError error) {
                    cache.evictAll();
                    available = false;
                }
            }
            if (available) postReady(page, bitmap, version);
            else postUnavailable(page, version);
        }
    }

    private Bitmap decode(int page) throws IOException {
        if (closed || Thread.currentThread().isInterrupted()) {
            throw new IOException("Preview request was cancelled.");
        }
        if (document.supportsRenderedTiles()) {
            return document.renderPreview(page, maxWidth, maxHeight);
        }

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream stream = document.openPage(page)) {
            BitmapFactory.decodeStream(stream, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("Android could not inspect this preview image.");
        }
        InputLimits.validateImageDimensions(bounds.outWidth, bounds.outHeight);

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = PreviewSizing.decodeSample(
                bounds.outWidth, bounds.outHeight, maxWidth, maxHeight);
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap decoded;
        try (InputStream stream = document.openPage(page)) {
            decoded = BitmapFactory.decodeStream(stream, null, options);
        }
        if (decoded == null) throw new IOException("Android could not decode this preview image.");

        PreviewSizing.Size target = PreviewSizing.fitInside(
                decoded.getWidth(), decoded.getHeight(), maxWidth, maxHeight, false);
        if (target.width == decoded.getWidth() && target.height == decoded.getHeight()) {
            return decoded;
        }
        try {
            Bitmap scaled = Bitmap.createScaledBitmap(
                    decoded, target.width, target.height, true);
            decoded.recycle();
            return scaled;
        } catch (RuntimeException | OutOfMemoryError error) {
            decoded.recycle();
            throw error;
        }
    }

    private void postReady(int page, Bitmap bitmap, long version) {
        mainHandler.post(() -> {
            if (isCurrent(page, version) && bitmap != null && !bitmap.isRecycled()) {
                callback.onPreviewReady(page, bitmap);
            }
        });
    }

    private void postUnavailable(int page, long version) {
        mainHandler.post(() -> {
            if (isCurrent(page, version)) callback.onPreviewUnavailable(page);
        });
    }

    private boolean isCurrent(int page, long version) {
        synchronized (requestLock) {
            return !closed && requestVersion == version && activePage == page;
        }
    }
}
