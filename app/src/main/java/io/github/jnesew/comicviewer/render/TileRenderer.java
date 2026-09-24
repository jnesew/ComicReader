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
import java.util.HashMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private static final AtomicLong taskOrder = new AtomicLong();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new PriorityBlockingQueue<>(), runnable -> {
        Thread thread = new Thread(runnable, "comic-tile-decoder");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable invalidator;
    private final ErrorListener errorListener;
    private final LruCache<String, Bitmap> tiles;
    private final SpeculativeTileCache speculativeTiles;
    private final Map<String, TileTask> pending = new HashMap<>();
    private int speculativeQueued;
    private final PrefetchGeneration prefetchGeneration = new PrefetchGeneration();
    private float visibleRenderScale = Float.NaN;
    private final LinkedHashMap<Integer, DecoderHolder> decoders =
            new LinkedHashMap<>(8, 0.75f, true);
    private final Paint imagePaint = createImagePaint();
    private final Paint placeholderPaint = new Paint();
    private volatile boolean closed;
    private volatile boolean errorReported;

    public static final class PageRequest {
        final int page;
        final RectF destination;
        final RectF clip;

        public PageRequest(int page, RectF destination, RectF clip) {
            this.page = page;
            this.destination = new RectF(destination);
            this.clip = new RectF(clip);
        }
    }

    public void drawPages(Canvas canvas, java.util.List<PageRequest> requests) {
        if (closed) return;
        if (archive.isUnavailable()) {
            for (PageRequest request : requests) drawUnavailable(canvas, request);
            return;
        }
        java.util.ArrayList<PageRequest> visibleRequests = new java.util.ArrayList<>();
        java.util.ArrayList<RenderedTilePolicy.Region> regions = new java.util.ArrayList<>();
        for (PageRequest request : requests) {
            if (request.destination.width() <= 0 || request.destination.height() <= 0) continue;
            RectF visible = new RectF(request.destination);
            if (!visible.intersect(request.clip)) continue;
            PageInfo info = archive.page(request.page);
            float scale = request.destination.width() / info.width;
            regions.add(new RenderedTilePolicy.Region(
                    clamp((int) Math.floor((visible.left - request.destination.left) / scale), 0, info.width - 1),
                    clamp((int) Math.floor((visible.top - request.destination.top) / scale), 0, info.height - 1),
                    clamp((int) Math.ceil((visible.right - request.destination.left) / scale), 1, info.width),
                    clamp((int) Math.ceil((visible.bottom - request.destination.top) / scale), 1, info.height),
                    info.width, info.height, scale));
            visibleRequests.add(request);
        }
        float[] scales = archive.supportsRenderedTiles()
                ? RenderedTilePolicy.frameScales(regions, tiles.maxSize() * 1024L * 3L / 4L)
                : RenderedTilePolicy.rasterFrameScales(regions, tiles.maxSize() * 1024L * 3L / 4L);
        if (scales.length != 0) visibleRenderScale = scales[0];
        for (int i = 0; i < visibleRequests.size(); i++) {
            PageRequest request = visibleRequests.get(i);
            drawPage(canvas, request.page, request.destination, request.clip, scales[i]);
        }
    }

    public boolean isUnavailable() { return archive.isUnavailable(); }

    public String documentKey() { return archive.key(); }

    public static boolean hitsNoticeRetry(float x, float y) {
        return x >= 300f && x <= 700f && y >= 750f && y <= 870f;
    }

    private void drawUnavailable(Canvas canvas, PageRequest request) {
        RectF visible = new RectF(request.destination);
        if (!visible.intersect(request.clip)) return;
        int saved = canvas.save();
        canvas.clipRect(visible);
        canvas.translate(request.destination.left, request.destination.top);
        canvas.scale(request.destination.width() / 1000f, request.destination.height() / 1000f);
        Paint background = new Paint();
        background.setColor(Color.rgb(35, 39, 47));
        canvas.drawRect(0, 0, 1000, 1000, background);
        android.text.TextPaint text = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.WHITE);
        text.setTextSize(44f);
        String message = context.getString(R.string.reader_unavailable_notice, archive.title());
        android.text.StaticLayout layout = android.text.StaticLayout.Builder
                .obtain(message, 0, message.length(), text, 840)
                .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER)
                .setMaxLines(9).setEllipsize(android.text.TextUtils.TruncateAt.END).build();
        canvas.save();
        canvas.translate(80, 120);
        layout.draw(canvas);
        canvas.restore();
        background.setColor(Color.rgb(60, 72, 96));
        canvas.drawRoundRect(new RectF(300, 750, 700, 870), 24, 24, background);
        text.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(context.getString(R.string.reader_retry), 500, 825, text);
        canvas.restoreToCount(saved);
    }

    public TileRenderer(
            Context context,
            ComicDocument archive,
            Runnable invalidator,
            ErrorListener errorListener,
            SpeculativeTileCache speculativeTiles) {
        this.archive = archive;
        this.context = context.getApplicationContext();
        this.invalidator = invalidator;
        this.errorListener = errorListener;
        this.speculativeTiles = speculativeTiles;
        this.pageCacheDirectory = new File(
                context.getCacheDir(), "page_tiles/session-" + Long.toUnsignedString(System.nanoTime()));
        if (!pageCacheDirectory.mkdirs() && !pageCacheDirectory.isDirectory()) {
            throw new IllegalStateException("Could not create the page tile cache.");
        }
        placeholderPaint.setColor(Color.rgb(24, 26, 31));

        int cacheKb = (int) (BufferingPolicy.visibleBytesPerRenderer(
                Runtime.getRuntime().maxMemory()) / 1024L);
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

    private void drawPage(Canvas canvas, int pageIndex, RectF destination, RectF requestedClip,
            float plannedRenderScale) {
        if (closed || destination.width() <= 0f || destination.height() <= 0f) return;
        PageInfo page = archive.page(pageIndex);
        RectF visible = new RectF(destination);
        if (!visible.intersect(requestedClip)) return;

        canvas.drawRect(visible, placeholderPaint);
        float scale = destination.width() / page.width;
        boolean rendered = archive.supportsRenderedTiles();
        int sample = rendered ? 1 : Math.round(1f / plannedRenderScale);
        float renderScale = rendered
                ? plannedRenderScale
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
                if (bitmap == null && speculativeTiles != null) {
                    bitmap = speculativeTiles.take(this, key);
                    if (bitmap != null) tiles.put(key, bitmap);
                }
                if (bitmap != null && !bitmap.isRecycled()) {
                    canvas.drawBitmap(bitmap, null, tileDestination, imagePaint);
                } else {
                    if (rendered) {
                        drawRenderedFallback(
                                canvas, pageIndex, page, source, tileDestination, renderScale);
                    }
                    requestTile(key, pageIndex, sample, renderScale, source, false);
                }
            }
        }
    }

    public boolean usesRenderedTiles() {
        return archive.supportsRenderedTiles();
    }

    public synchronized void trimMemory() {
        if (closed) return;
        cancelPrefetch();
        if (speculativeTiles != null) speculativeTiles.removeOwner(this);
        tiles.evictAll();
        for (DecoderHolder holder : decoders.values()) holder.close();
        decoders.clear();
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        prefetchGeneration.close();
        cancelPrefetch();
        executor.shutdownNow();
        tiles.evictAll();
        synchronized (pending) {
            for (TileTask task : new ArrayList<>(pending.values())) completeTask(task);
        }
        if (speculativeTiles != null) speculativeTiles.removeOwner(this);
        for (DecoderHolder holder : decoders.values()) holder.close();
        decoders.clear();
        deleteTree(pageCacheDirectory);
    }

    /** Offscreen requests have their own budget and never enter frame scale selection. */
    public void prefetchPages(java.util.List<PageRequest> requests, long signature, int maxTiles) {
        if (closed || archive.isUnavailable() || speculativeTiles == null ||
                !speculativeTiles.enabled() || maxTiles <= 0) return;
        if (prefetchGeneration.begin(signature)) dropQueuedPrefetch();
        int considered = 0;
        for (PageRequest request : requests) {
            if (considered >= maxTiles || request.destination.width() <= 0f ||
                    request.destination.height() <= 0f) break;
            RectF area = new RectF(request.destination);
            if (!area.intersect(request.clip)) continue;
            PageInfo page = archive.page(request.page);
            float scale = request.destination.width() / page.width;
            int left = clamp((int) Math.floor((area.left - request.destination.left) / scale), 0, page.width - 1);
            int top = clamp((int) Math.floor((area.top - request.destination.top) / scale), 0, page.height - 1);
            int right = clamp((int) Math.ceil((area.right - request.destination.left) / scale), 1, page.width);
            int bottom = clamp((int) Math.ceil((area.bottom - request.destination.top) / scale), 1, page.height);
            RenderedTilePolicy.Region region = new RenderedTilePolicy.Region(
                    left, top, right, bottom, page.width, page.height, scale);
            java.util.List<RenderedTilePolicy.Region> single = java.util.Collections.singletonList(region);
            float planned = archive.supportsRenderedTiles()
                    ? RenderedTilePolicy.frameScales(single, tiles.maxSize() * 1024L * 3L / 4L)[0]
                    : RenderedTilePolicy.rasterFrameScales(single, tiles.maxSize() * 1024L * 3L / 4L)[0];
            if (!Float.isNaN(visibleRenderScale)) {
                planned = Math.min(planned, visibleRenderScale);
            }
            boolean rendered = archive.supportsRenderedTiles();
            int sample = rendered ? 1 : Math.max(1, Math.round(1f / planned));
            float renderScale = rendered ? planned : 1f / sample;
            int sourceTile = rendered ? RenderedTilePolicy.sourceTileSize(renderScale)
                    : RASTER_TILE_SIZE * sample;
            for (int tileY = top / sourceTile; tileY <= (bottom - 1) / sourceTile; tileY++) {
                for (int tileX = left / sourceTile; tileX <= (right - 1) / sourceTile; tileX++) {
                    if (considered++ >= maxTiles) return;
                    String key = key(request.page, sample, renderScale, tileX, tileY);
                    if (tiles.get(key) != null || speculativeTiles.contains(this, key)) continue;
                    int x = tileX * sourceTile;
                    int y = tileY * sourceTile;
                    requestTile(key, request.page, sample, renderScale,
                            new Rect(x, y, Math.min(page.width, x + sourceTile),
                                    Math.min(page.height, y + sourceTile)), true);
                }
            }
        }
    }

    public void cancelPrefetch() {
        prefetchGeneration.cancel();
        dropQueuedPrefetch();
    }

    private void dropQueuedPrefetch() {
        synchronized (pending) {
            for (TileTask task : new ArrayList<>(pending.values())) {
                if (task.speculative && executor.remove(task)) completeTask(task);
            }
        }
    }

    private void requestTile(String key, int pageIndex, int sample,
            float renderScale, Rect source, boolean speculative) {
        synchronized (pending) {
            if (closed || (speculative && speculativeQueued >= 16)) return;
            TileTask existing = pending.get(key);
            if (existing != null) {
                if (!speculative && existing.speculative) {
                    existing.seenVisible = true;
                    if (executor.remove(existing)) completeTask(existing);
                    else return; // The running tile will invalidate when finished.
                } else return;
            }
            TileTask task = new TileTask(key, pageIndex, sample,
                    renderScale, new Rect(source), speculative, prefetchGeneration.current(),
                    taskOrder.incrementAndGet());
            pending.put(key, task);
            if (speculative) speculativeQueued++;
            else if (speculativeTiles != null) speculativeTiles.visibleQueued();
            try {
                executor.execute(task);
            } catch (RejectedExecutionException ignored) {
                completeTask(task);
            }
        }
    }

    private void completeTask(TileTask task) {
        if (!task.finished.compareAndSet(false, true)) return;
        synchronized (pending) {
            pending.remove(task.key, task);
            if (task.speculative) speculativeQueued--;
        }
        if (!task.speculative && speculativeTiles != null) speculativeTiles.visibleFinished();
    }

    private final class TileTask implements Runnable, Comparable<TileTask> {
        final String key;
        final int pageIndex;
        final int sample;
        final float renderScale;
        final Rect source;
        final boolean speculative;
        final long epoch;
        final long order;
        final AtomicBoolean finished = new AtomicBoolean();
        volatile boolean seenVisible;

        TileTask(String key, int pageIndex, int sample, float renderScale,
                Rect source, boolean speculative, long epoch, long order) {
            this.key = key;
            this.pageIndex = pageIndex;
            this.sample = sample;
            this.renderScale = renderScale;
            this.source = source;
            this.speculative = speculative;
            this.epoch = epoch;
            this.order = order;
        }

        @Override public int compareTo(TileTask other) {
            return BufferingPolicy.compareTasks(speculative, order, other.speculative, other.order);
        }

        @Override public void run() {
            boolean slot = false;
            try {
                if (closed) return;
                if (speculative) {
                    if (!prefetchGeneration.isCurrent(epoch) ||
                            !speculativeTiles.beginSpeculativeDecode()) return;
                    slot = true;
                }
                Bitmap decoded = decodeTile(pageIndex, sample, renderScale, source);
                synchronized (TileRenderer.this) {
                    if (decoded != null && !closed) {
                        if (speculative) {
                            prefetchGeneration.publishIfCurrent(epoch,
                                    () -> speculativeTiles.put(TileRenderer.this, key, decoded));
                            if (seenVisible) mainHandler.post(invalidator);
                        } else {
                            tiles.put(key, decoded);
                            mainHandler.post(invalidator);
                        }
                    }
                }
            } catch (OutOfMemoryError error) {
                if (speculativeTiles != null) speculativeTiles.clear();
                tiles.evictAll();
                if (!speculative) reportError(context.getString(R.string.error_tile_memory));
            } catch (IOException | RuntimeException error) {
                if (!speculative) reportError(context.getString(R.string.error_render_page, pageIndex + 1));
                else if (seenVisible) mainHandler.post(invalidator);
            } finally {
                if (slot) speculativeTiles.endSpeculativeDecode();
                if (speculative && seenVisible && !closed) mainHandler.post(invalidator);
                completeTask(this);
            }
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
        Bitmap tile = Bitmap.createBitmap(holder.fallback, left, top, right - left, bottom - top);
        // Android can return the source bitmap for a full-region crop. The decoder holder
        // recycles its fallback later, so cached tiles must own independent pixels.
        return tile == holder.fallback ? tile.copy(Bitmap.Config.ARGB_8888, false) : tile;
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
