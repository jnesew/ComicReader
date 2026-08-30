package io.github.jnesew.comicviewer.render;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import io.github.jnesew.comicviewer.R;
import io.github.jnesew.comicviewer.document.ComicDocument;
import io.github.jnesew.comicviewer.model.PageInfo;
import io.github.jnesew.comicviewer.util.InputLimits;
import io.github.jnesew.comicviewer.util.RenderedTilePolicy;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Decodes only visible image regions into a bounded LRU. Raster tiles are normally ~2.25 MiB;
 * directly rendered PDF tiles target ~1 MiB. Neither a normal comic page nor a very tall strip
 * is ever required to exist as one bitmap.
 */
public final class TileRenderer implements AutoCloseable {
    public interface ErrorListener {
        void onRenderError(String message);
    }

    private static final int RASTER_TILE_SIZE = 768;
    private static final int MAX_OPEN_DECODERS = 4;

    private final Context context;
    private final ComicDocument archive;
    private final File pageCacheDirectory;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "comic-tile-decoder");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable invalidator;
    private final ErrorListener errorListener;
    private final LruCache<String, Bitmap> tiles;
    private final Set<String> inFlight = Collections.synchronizedSet(new java.util.HashSet<>());
    private final LinkedHashMap<Integer, DecoderHolder> decoders =
            new LinkedHashMap<>(8, 0.75f, true);
    private final Paint imagePaint = createImagePaint();
    private final Paint placeholderPaint = new Paint();
    private volatile boolean closed;
    private volatile boolean errorReported;

    public TileRenderer(
            Context context,
            ComicDocument archive,
            Runnable invalidator,
            ErrorListener errorListener) {
        this.archive = archive;
        this.context = context.getApplicationContext();
        this.invalidator = invalidator;
        this.errorListener = errorListener;
        this.pageCacheDirectory = new File(
                context.getCacheDir(), "page_tiles/session-" + Long.toUnsignedString(System.nanoTime()));
        if (!pageCacheDirectory.mkdirs() && !pageCacheDirectory.isDirectory()) {
            throw new IllegalStateException("Could not create the page tile cache.");
        }
        placeholderPaint.setColor(Color.rgb(24, 26, 31));

        long heapKb = Runtime.getRuntime().maxMemory() / 1024L;
        int cacheKb = (int) Math.min(64L * 1024L, Math.max(24L * 1024L, heapKb / 8L));
        tiles = new LruCache<>(cacheKb) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return Math.max(1, bitmap.getAllocationByteCount() / 1024);
            }
        };
    }

    private static Paint createImagePaint() {
        Paint paint = new Paint();
        // Tiles share exact axis-aligned edges. Geometric edge antialiasing can blend a
        // fractional shared edge with the placeholder and expose it as a one-pixel seam.
        paint.setAntiAlias(false);
        paint.setFilterBitmap(true);
        return paint;
    }

    public void drawPage(Canvas canvas, int pageIndex, RectF destination, RectF requestedClip) {
        if (closed || destination.width() <= 0f || destination.height() <= 0f) return;
        PageInfo page = archive.page(pageIndex);
        RectF visible = new RectF(destination);
        if (!visible.intersect(requestedClip)) return;

        canvas.drawRect(visible, placeholderPaint);
        float scale = destination.width() / page.width;
        boolean rendered = archive.supportsRenderedTiles();
        int sample = rendered ? 1 : chooseSample(scale);
        float renderScale = rendered
                ? RenderedTilePolicy.chooseRenderScale(scale)
                : 1f / sample;
        int sourceTile = rendered
                ? RenderedTilePolicy.sourceTileSize(renderScale)
                : RASTER_TILE_SIZE * sample;

        int visibleLeft = clamp((int) Math.floor((visible.left - destination.left) / scale), 0, page.width - 1);
        int visibleTop = clamp((int) Math.floor((visible.top - destination.top) / scale), 0, page.height - 1);
        int visibleRight = clamp((int) Math.ceil((visible.right - destination.left) / scale), 1, page.width);
        int visibleBottom = clamp((int) Math.ceil((visible.bottom - destination.top) / scale), 1, page.height);

        int firstX = visibleLeft / sourceTile;
        int lastX = Math.max(firstX, (visibleRight - 1) / sourceTile);
        int firstY = visibleTop / sourceTile;
        int lastY = Math.max(firstY, (visibleBottom - 1) / sourceTile);

        for (int tileY = firstY; tileY <= lastY; tileY++) {
            for (int tileX = firstX; tileX <= lastX; tileX++) {
                int sourceLeft = tileX * sourceTile;
                int sourceTop = tileY * sourceTile;
                Rect source = new Rect(
                        sourceLeft,
                        sourceTop,
                        Math.min(page.width, sourceLeft + sourceTile),
                        Math.min(page.height, sourceTop + sourceTile));
                RectF tileDestination = new RectF(
                        destination.left + source.left * scale,
                        destination.top + source.top * scale,
                        destination.left + source.right * scale,
                        destination.top + source.bottom * scale);
                String key = key(pageIndex, sample, renderScale, tileX, tileY);
                Bitmap bitmap = tiles.get(key);
                if (bitmap != null && !bitmap.isRecycled()) {
                    canvas.drawBitmap(bitmap, null, tileDestination, imagePaint);
                } else {
                    if (rendered) {
                        drawRenderedFallback(
                                canvas, pageIndex, page, source, tileDestination, renderScale);
                    }
                    requestTile(key, pageIndex, sample, renderScale, source);
                }
            }
        }
    }

    public boolean usesRenderedTiles() {
        return archive.supportsRenderedTiles();
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        executor.shutdownNow();
        tiles.evictAll();
        inFlight.clear();
        for (DecoderHolder holder : decoders.values()) holder.close();
        decoders.clear();
        deleteTree(pageCacheDirectory);
    }

    private void requestTile(
            String key, int pageIndex, int sample, float renderScale, Rect source) {
        if (closed || !inFlight.add(key)) return;
        try {
            executor.execute(() -> {
                try {
                    if (closed) return;
                    Bitmap decoded = decodeTile(pageIndex, sample, renderScale, source);
                    if (decoded != null && !closed) {
                        tiles.put(key, decoded);
                        mainHandler.post(invalidator);
                    }
                } catch (OutOfMemoryError error) {
                    tiles.evictAll();
                    reportError(context.getString(R.string.error_tile_memory));
                } catch (IOException | RuntimeException error) {
                    reportError(context.getString(R.string.error_render_page, pageIndex + 1));
                } finally {
                    inFlight.remove(key);
                }
            });
        } catch (RejectedExecutionException ignored) {
            inFlight.remove(key);
        }
    }

    private synchronized Bitmap decodeTile(
            int pageIndex, int sample, float renderScale, Rect source) throws IOException {
        if (closed) throw new IOException("Renderer is closed.");
        if (archive.supportsRenderedTiles()) {
            return archive.renderTile(pageIndex, source, renderScale);
        }
        DecoderHolder holder = decoderFor(pageIndex);
        if (holder.regionDecoder != null) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap result = holder.regionDecoder.decodeRegion(source, options);
            if (result == null) throw new IOException("Region decoder returned no bitmap.");
            return result;
        }

        PageInfo pageInfo = archive.page(pageIndex);
        int fallbackSample = InputLimits.boundedBitmapSample(
                pageInfo.width, pageInfo.height, sample);
        if (holder.fallback == null || holder.fallbackSample != fallbackSample) {
            if (holder.fallback != null) holder.fallback.recycle();
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = fallbackSample;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try (InputStream stream = archive.openPage(pageIndex)) {
                holder.fallback = BitmapFactory.decodeStream(stream, null, options);
            }
            holder.fallbackSample = fallbackSample;
            if (holder.fallback == null) throw new IOException("Bitmap decoder returned no image.");
        }

        int left = clamp(source.left / fallbackSample, 0, holder.fallback.getWidth() - 1);
        int top = clamp(source.top / fallbackSample, 0, holder.fallback.getHeight() - 1);
        int right = clamp((source.right + fallbackSample - 1) / fallbackSample,
                left + 1, holder.fallback.getWidth());
        int bottom = clamp((source.bottom + fallbackSample - 1) / fallbackSample,
                top + 1, holder.fallback.getHeight());
        return Bitmap.createBitmap(holder.fallback, left, top, right - left, bottom - top);
    }

    private DecoderHolder decoderFor(int pageIndex) throws IOException {
        DecoderHolder existing = decoders.get(pageIndex);
        if (existing != null) return existing;

        PageInfo page = archive.page(pageIndex);
        InputLimits.validateImageDimensions(page.width, page.height);
        String extension = extension(page.name);
        File file = new File(pageCacheDirectory, String.format(java.util.Locale.ROOT,
                "page-%05d%s", pageIndex, extension));
        if (!file.isFile()) archive.extractPage(pageIndex, file);

        BitmapRegionDecoder regionDecoder = null;
        try {
            regionDecoder = BitmapRegionDecoder.newInstance(file.getAbsolutePath(), false);
        } catch (IOException | RuntimeException ignored) {
            // Animated GIF and a few older bitmap encodings need the full-decoder fallback.
        }
        DecoderHolder created = new DecoderHolder(file, regionDecoder);
        decoders.put(pageIndex, created);
        trimDecoders();
        return created;
    }

    private void trimDecoders() {
        while (decoders.size() > MAX_OPEN_DECODERS) {
            Map.Entry<Integer, DecoderHolder> eldest = decoders.entrySet().iterator().next();
            decoders.remove(eldest.getKey());
            eldest.getValue().close();
        }
    }

    private void reportError(String message) {
        if (errorReported || closed) return;
        errorReported = true;
        mainHandler.post(() -> errorListener.onRenderError(message));
    }

    private static int chooseSample(float scale) {
        if (scale >= 1f) return 1;
        float inverse = 1f / Math.max(0.0001f, scale);
        int sample = 1;
        while (sample < 64 && sample * 2f <= inverse) sample *= 2;
        return sample;
    }

    private void drawRenderedFallback(
            Canvas canvas,
            int pageIndex,
            PageInfo page,
            Rect requestedSource,
            RectF destination,
            float renderScale) {
        float fallbackScale = RenderedTilePolicy.nextCoarserScale(renderScale);
        while (fallbackScale > 0f) {
            int fallbackSourceTile = RenderedTilePolicy.sourceTileSize(fallbackScale);
            int tileX = requestedSource.left / fallbackSourceTile;
            int tileY = requestedSource.top / fallbackSourceTile;
            int sourceLeft = tileX * fallbackSourceTile;
            int sourceTop = tileY * fallbackSourceTile;
            Rect fallbackSource = new Rect(
                    sourceLeft,
                    sourceTop,
                    Math.min(page.width, sourceLeft + fallbackSourceTile),
                    Math.min(page.height, sourceTop + fallbackSourceTile));
            Bitmap fallback = tiles.get(key(pageIndex, 1, fallbackScale, tileX, tileY));
            if (fallback != null && !fallback.isRecycled()) {
                canvas.drawBitmap(
                        fallback,
                        bitmapSourceRect(requestedSource, fallbackSource, fallback),
                        destination,
                        imagePaint);
                return;
            }
            fallbackScale = RenderedTilePolicy.nextCoarserScale(fallbackScale);
        }
    }

    private static Rect bitmapSourceRect(Rect requested, Rect cachedSource, Bitmap bitmap) {
        float scaleX = bitmap.getWidth() / (float) cachedSource.width();
        float scaleY = bitmap.getHeight() / (float) cachedSource.height();
        int left = clamp(
                (int) Math.floor((requested.left - cachedSource.left) * scaleX),
                0,
                bitmap.getWidth() - 1);
        int top = clamp(
                (int) Math.floor((requested.top - cachedSource.top) * scaleY),
                0,
                bitmap.getHeight() - 1);
        int right = clamp(
                (int) Math.ceil((requested.right - cachedSource.left) * scaleX),
                left + 1,
                bitmap.getWidth());
        int bottom = clamp(
                (int) Math.ceil((requested.bottom - cachedSource.top) * scaleY),
                top + 1,
                bitmap.getHeight());
        return new Rect(left, top, right, bottom);
    }

    private static String key(int page, int sample, float renderScale, int x, int y) {
        return page + ":" + sample + ":" + Float.floatToIntBits(renderScale) +
                ":" + x + ":" + y;
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot < name.lastIndexOf('/')) return ".img";
        String value = name.substring(dot).toLowerCase(java.util.Locale.ROOT);
        return value.length() <= 6 ? value : ".img";
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }

    private static final class DecoderHolder {
        private final File file;
        private final BitmapRegionDecoder regionDecoder;
        private Bitmap fallback;
        private int fallbackSample;

        private DecoderHolder(File file, BitmapRegionDecoder regionDecoder) {
            this.file = file;
            this.regionDecoder = regionDecoder;
        }

        private void close() {
            if (regionDecoder != null && !regionDecoder.isRecycled()) regionDecoder.recycle();
            if (fallback != null && !fallback.isRecycled()) fallback.recycle();
            file.delete();
        }
    }
}
