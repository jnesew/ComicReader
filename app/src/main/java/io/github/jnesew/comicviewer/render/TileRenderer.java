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
import android.graphics.Region;
import android.graphics.RegionIterator;
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
    private final LruCache<TileKey, Bitmap> tiles;
    private final SpeculativeTileCache speculativeTiles;
    private final RenderTuning tuning;
    private final RenderMetrics metrics;
    private volatile java.util.List<PageRequest> refillRequests = java.util.Collections.emptyList();
    private long refillSignature;
    private int refillMaxTiles;
    private final AtomicBoolean refillPosted = new AtomicBoolean();
    private final java.util.Set<TileKey> refusedPrefetch = new java.util.HashSet<>();
    private final Map<TileKey, Integer> preparedPrefetch = new HashMap<>();
    private final Map<TileKey, TileTask> pending = new HashMap<>();
    private volatile boolean retainEvictedTiles = true;
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
        final int frame;
        final boolean reverse;

        public PageRequest(int page, RectF destination, RectF clip) {
            this(page, destination, clip, 0);
        }
        public PageRequest(int page, RectF destination, RectF clip, int frame) {
            this(page, destination, clip, frame, false);
        }
        public PageRequest(int page, RectF destination, RectF clip, int frame, boolean reverse) {
            this.page = page;
            this.destination = new RectF(destination);
            this.clip = new RectF(clip);
            this.frame = frame;
            this.reverse = reverse;
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
        this(context, archive, invalidator, errorListener, speculativeTiles,
                (int) (BufferingPolicy.visibleBytesPerRenderer(
                        Runtime.getRuntime().maxMemory()) / 1024L));
    }

    /** Deterministic cache-size seam for real Android rendering regression tests. */
    TileRenderer(Context context, ComicDocument archive, Runnable invalidator,
            ErrorListener errorListener, SpeculativeTileCache speculativeTiles, int cacheKb) {
        this.archive = archive;
        this.context = context.getApplicationContext();
        this.invalidator = invalidator;
        this.errorListener = errorListener;
        this.speculativeTiles = speculativeTiles;
        this.tuning = speculativeTiles == null ? RenderTuning.DEFAULT_READER : speculativeTiles.tuning();
        this.metrics = speculativeTiles == null ? null : speculativeTiles.metrics();
        this.pageCacheDirectory = new File(
                context.getCacheDir(), "page_tiles/session-" + Long.toUnsignedString(System.nanoTime()));
        if (!pageCacheDirectory.mkdirs() && !pageCacheDirectory.isDirectory()) {
            throw new IllegalStateException("Could not create the page tile cache.");
        }
        placeholderPaint.setColor(Color.rgb(24, 26, 31));

        tiles = new LruCache<>(cacheKb) {
            @Override
            protected int sizeOf(TileKey key, Bitmap bitmap) {
                return Math.max(1, bitmap.getAllocationByteCount() / 1024);
            }

            @Override
            protected void entryRemoved(boolean evicted, TileKey key, Bitmap oldValue,
                    Bitmap newValue) {
                // Recently visible pages compete with ahead-of-reader tiles within the same
                // existing speculative ceiling, rather than disappearing on a backward turn.
                if (evicted && retainEvictedTiles && !closed && speculativeTiles != null) {
                    speculativeTiles.putHistory(TileRenderer.this, key, oldValue);
                }
            }
        };
        if (speculativeTiles != null) {
            speculativeTiles.registerOwner(this, cacheKb * 1024L,
                    archive.supportsRenderedTiles() ? 0 : cacheKb * 1024L);
            if (tuning.refill) speculativeTiles.onWorkAvailable(this, this::scheduleRefill);
        }
    }

    private void residentBytes() {
        if (speculativeTiles == null) return;
        long backing = 0;
        for (DecoderHolder holder : decoders.values()) {
            if (holder.fallback != null) backing += holder.fallback.getAllocationByteCount();
        }
        speculativeTiles.residentBytes(this, tiles.size() * 1024L, backing);
    }

    private void scheduleRefill() {
        if (closed || !tuning.refill || !refillPosted.compareAndSet(false, true)) return;
        mainHandler.post(() -> {
            refillPosted.set(false);
            if (!closed && !refillRequests.isEmpty()) {
                prefetchPages(refillRequests, refillSignature, refillMaxTiles);
            }
        });
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
        java.util.List<Map.Entry<TileKey, Bitmap>> rasterFallbacks = null;

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
                TileKey key = key(pageIndex, page, sample, renderScale, tileX, tileY);
                Bitmap bitmap = tiles.get(key);
                if (bitmap == null && speculativeTiles != null) {
                    bitmap = speculativeTiles.take(this, key);
                    if (bitmap != null) tiles.put(key, bitmap);
                }
                boolean missing = bitmap == null || bitmap.isRecycled();
                double fallbackArea = 0;
                if (!missing) {
                    canvas.drawBitmap(bitmap, null, tileDestination, imagePaint);
                } else {
                    if (rendered) {
                        if (drawRenderedFallback(canvas, pageIndex, page, source, tileDestination, renderScale)
                                && metrics != null) {
                            RectF covered = new RectF(tileDestination);
                            if (covered.intersect(visible)) fallbackArea = covered.width() * (double) covered.height();
                        }
                    } else {
                        if (rasterFallbacks == null) {
                            rasterFallbacks = rasterFallbacks(pageIndex, sample);
                        }
                        fallbackArea = drawRasterFallback(canvas, page, destination, visible,
                                tileDestination, source, rasterFallbacks);
                    }
                    requestTile(key, pageIndex, sample, renderScale, source, false);
                }
                if (metrics != null) {
                    RectF measured = new RectF(tileDestination);
                    if (measured.intersect(visible)) {
                        double area = measured.width() * (double) measured.height();
                        metrics.area(area, missing, missing ? Math.max(0, area - fallbackArea) : 0);
                    }
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
        discardVisibleTiles();
        for (DecoderHolder holder : decoders.values()) holder.close();
        decoders.clear();
        residentBytes();
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        prefetchGeneration.close();
        cancelPrefetch();
        executor.shutdownNow();
        discardVisibleTiles();
        synchronized (pending) {
            for (TileTask task : new ArrayList<>(pending.values())) completeTask(task);
        }
        if (speculativeTiles != null) speculativeTiles.removeOwner(this);
        for (DecoderHolder holder : decoders.values()) holder.close();
        decoders.clear();
        if (speculativeTiles != null) speculativeTiles.unregisterOwner(this);
        deleteTree(pageCacheDirectory);
    }

    /** Offscreen requests have their own budget and never enter frame scale selection. */
    public void prefetchPages(java.util.List<PageRequest> requests, long signature, int maxTiles) {
        long started = metrics == null ? 0 : System.nanoTime();
        try {
            planPrefetchPages(requests, signature, maxTiles);
        } finally {
            if (metrics != null) metrics.planning(System.nanoTime() - started);
        }
    }

    private void planPrefetchPages(java.util.List<PageRequest> requests, long signature, int maxTiles) {
        if (closed || archive.isUnavailable() || speculativeTiles == null ||
                !speculativeTiles.enabled() || maxTiles <= 0) return;
        if (prefetchGeneration.begin(signature)) {
            dropQueuedPrefetch();
            synchronized (pending) { refusedPrefetch.clear(); preparedPrefetch.clear(); }
        }
        if (tuning.refill) {
            refillRequests = new ArrayList<>(requests);
            refillSignature = signature;
            refillMaxTiles = maxTiles;
        }
        Map<PageRequest, Float> predictedScales = tuning.predictedFrame
                ? new java.util.IdentityHashMap<>() : java.util.Collections.emptyMap();
        if (tuning.predictedFrame) {
            Map<Integer, java.util.List<PageRequest>> frames = new LinkedHashMap<>();
            for (PageRequest request : requests) {
                if (request.destination.width() > 0 && request.destination.height() > 0 &&
                        RectF.intersects(request.destination, request.clip)) {
                    frames.computeIfAbsent(request.frame, ignored -> new ArrayList<>()).add(request);
                }
            }
            for (java.util.List<PageRequest> frame : frames.values()) {
                java.util.List<RenderedTilePolicy.Region> regions = new ArrayList<>();
                for (PageRequest request : frame) {
                    RectF area = new RectF(request.destination);
                    area.intersect(request.clip);
                    PageInfo info = archive.page(request.page);
                    float scale = request.destination.width() / info.width;
                    regions.add(new RenderedTilePolicy.Region(
                            clamp((int) Math.floor((area.left - request.destination.left) / scale), 0, info.width - 1),
                            clamp((int) Math.floor((area.top - request.destination.top) / scale), 0, info.height - 1),
                            clamp((int) Math.ceil((area.right - request.destination.left) / scale), 1, info.width),
                            clamp((int) Math.ceil((area.bottom - request.destination.top) / scale), 1, info.height),
                            info.width, info.height, scale));
                }
                float[] scales = archive.supportsRenderedTiles()
                        ? RenderedTilePolicy.frameScales(regions, tiles.maxSize() * 1024L * 3L / 4L)
                        : RenderedTilePolicy.rasterFrameScales(regions, tiles.maxSize() * 1024L * 3L / 4L);
                for (int i = 0; i < frame.size(); i++) predictedScales.put(frame.get(i), scales[i]);
            }
        }
        java.util.Set<TileKey> plannedKeys = tuning.boundedAdmission()
                ? new java.util.HashSet<>() : java.util.Collections.emptySet();
        long planBytes = 0;
        int considered = 0;
        java.util.List<PrefetchTile> candidates = new ArrayList<>();
        Map<TileKey, Integer> priorities = new LinkedHashMap<>();
        requestsLoop:
        for (PageRequest request : requests) {
            if ((!tuning.prioritized && considered >= maxTiles) || request.destination.width() <= 0f ||
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
            if (predictedScales.containsKey(request)) {
                planned = predictedScales.get(request);
            } else if (!Float.isNaN(visibleRenderScale)) {
                planned = Math.min(planned, visibleRenderScale);
            }
            boolean rendered = archive.supportsRenderedTiles();
            int sample = rendered ? 1 : Math.max(1, Math.round(1f / planned));
            float renderScale = rendered ? planned : 1f / sample;
            int sourceTile = rendered ? RenderedTilePolicy.sourceTileSize(renderScale)
                    : RASTER_TILE_SIZE * sample;
            int firstRow = top / sourceTile, lastRow = (bottom - 1) / sourceTile;
            for (int row = 0; row <= lastRow - firstRow; row++) {
                int tileY = request.reverse ? lastRow - row : firstRow + row;
                for (int tileX = left / sourceTile; tileX <= (right - 1) / sourceTile; tileX++) {
                    if (!tuning.prioritized && considered >= maxTiles) return;
                    if (tuning.prioritized && candidates.size() >= 128) break requestsLoop;
                    TileKey key = key(request.page, page, sample, renderScale, tileX, tileY);
                    int x = tileX * sourceTile;
                    int y = tileY * sourceTile;
                    Rect source = new Rect(x, y, Math.min(page.width, x + sourceTile),
                            Math.min(page.height, y + sourceTile));
                    long estimated = estimateBytes(source, renderScale);
                    if (tuning.boundedAdmission() && !plannedKeys.add(key)) continue;
                    if (tuning.boundedAdmission()) {
                        planBytes += estimated;
                        if (planBytes > speculativeTiles.limitBytes()) {
                            if (tuning.prioritized) break requestsLoop;
                            return;
                        }
                    }
                    if (tuning.prioritized) {
                        priorities.put(key, request.frame);
                        candidates.add(new PrefetchTile(key, request.page, sample, renderScale, source));
                        continue;
                    }
                    if (tiles.get(key) != null || speculativeTiles.contains(this, key)) continue;
                    synchronized (pending) { if (tuning.boundedAdmission() && refusedPrefetch.contains(key)) continue; }
                    considered++;
                    requestTile(key, request.page, sample, renderScale,
                            source, true);
                }
            }
        }
        if (tuning.prioritized) {
            speculativeTiles.plan(this, priorities);
            synchronized (pending) {
                for (TileTask task : new ArrayList<>(pending.values())) {
                    if (task.speculative && !priorities.containsKey(task.key) && executor.remove(task)) {
                        completeTask(task);
                    }
                }
            }
            int queued = 0;
            for (PrefetchTile tile : candidates) {
                if (tiles.get(tile.key) != null || speculativeTiles.contains(this, tile.key)) continue;
                synchronized (pending) {
                    if (pending.containsKey(tile.key) || refusedPrefetch.contains(tile.key)) continue;
                    if (preparedAlready(tile.key, speculativeTiles.priority(this, tile.key))) continue;
                }
                if (queued++ >= maxTiles) break;
                requestTile(tile.key, tile.page, tile.sample, tile.scale, tile.source, true);
            }
        }
    }

    private record PrefetchTile(TileKey key, int page, int sample, float scale, Rect source) { }

    /** A replacement may not repeatedly decode the same optional output in one window.
     * A strictly nearer use can retry once at its new rank; visible work is never suppressed. */
    private boolean preparedAlready(TileKey key, int priority) {
        Integer prepared = preparedPrefetch.get(key);
        boolean skip = prepared != null ? prepared <= priority : preparedPrefetch.size() >= 128;
        if (skip && metrics != null) metrics.repeatPrefetchSkipped();
        return skip;
    }

    public void cancelPrefetch() {
        refillRequests = java.util.Collections.emptyList();
        prefetchGeneration.cancel();
        dropQueuedPrefetch();
        if (speculativeTiles != null && tuning.prioritized) speculativeTiles.cancelPlan(this);
    }

    private void dropQueuedPrefetch() {
        synchronized (pending) {
            for (TileTask task : new ArrayList<>(pending.values())) {
                if (task.speculative && executor.remove(task)) completeTask(task);
            }
        }
    }

    private void requestTile(TileKey key, int pageIndex, int sample,
            float renderScale, Rect source, boolean speculative) {
        synchronized (pending) {
            // Refill the nearest tile on completion. Farther queued reservations must
            // not hold the allowance when a new near-frame plan arrives.
            int speculativeLimit = tuning.prioritized ? tuning.speculativePipeline : 16;
            if (closed || (speculative && speculativeQueued >= speculativeLimit)) return;
            TileTask existing = pending.get(key);
            if (existing != null) {
                if (!speculative && existing.speculative) {
                    existing.seenVisible = true;
                    if (executor.remove(existing)) completeTask(existing);
                    else return; // The running tile will invalidate when finished.
                } else return;
            }
            long reservation = speculative && tuning.boundedAdmission() ? estimateBytes(source, renderScale) : 0L;
            int priority = speculative && tuning.prioritized ? speculativeTiles.priority(this, key) : Integer.MAX_VALUE;
            if (speculative && tuning.prioritized && preparedAlready(key, priority)) return;
            if (reservation > 0 && !(tuning.prioritized
                    ? speculativeTiles.reserve(this, key, reservation)
                    : speculativeTiles.reserve(reservation))) return;
            TileTask task = new TileTask(key, pageIndex, sample,
                    renderScale, new Rect(source), speculative, prefetchGeneration.current(),
                    taskOrder.incrementAndGet(), reservation, priority);
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

    private static long estimateBytes(Rect source, float scale) {
        return 4L * (long) Math.ceil(source.width() * (double) scale)
                * (long) Math.ceil(source.height() * (double) scale);
    }

    private void completeTask(TileTask task) {
        if (!task.finished.compareAndSet(false, true)) return;
        task.releaseReservation();
        synchronized (pending) {
            pending.remove(task.key, task);
            if (task.speculative) speculativeQueued--;
        }
        if (!task.speculative && speculativeTiles != null) speculativeTiles.visibleFinished();
    }

    private final class TileTask implements Runnable, Comparable<TileTask> {
        final TileKey key;
        final int pageIndex;
        final int sample;
        final float renderScale;
        final Rect source;
        final boolean speculative;
        final long epoch;
        final long order;
        final long reservation;
        final int priority;
        final long queuedAt;
        final AtomicBoolean finished = new AtomicBoolean();
        final AtomicBoolean reservationReleased = new AtomicBoolean();
        volatile boolean seenVisible;

        TileTask(TileKey key, int pageIndex, int sample, float renderScale,
                Rect source, boolean speculative, long epoch, long order, long reservation, int priority) {
            this.key = key;
            this.pageIndex = pageIndex;
            this.sample = sample;
            this.renderScale = renderScale;
            this.source = source;
            this.speculative = speculative;
            this.epoch = epoch;
            this.order = order;
            this.reservation = reservation;
            this.priority = priority;
            this.queuedAt = metrics == null ? 0 : android.os.SystemClock.elapsedRealtimeNanos();
        }

        @Override public int compareTo(TileTask other) {
            return BufferingPolicy.compareTasks(speculative, order, other.speculative, other.order);
        }

        void releaseReservation() {
            if (reservation > 0 && reservationReleased.compareAndSet(false, true)) {
                speculativeTiles.release(reservation);
            }
        }

        boolean publishReserved(Bitmap bitmap) {
            return reservationReleased.compareAndSet(false, true) &&
                    speculativeTiles.publishReserved(TileRenderer.this, key, bitmap, reservation);
        }
        void rememberPrepared() {
            if (!tuning.prioritized) return;
            synchronized (pending) {
                if (prefetchGeneration.isCurrent(epoch)) {
                    preparedPrefetch.merge(key, priority, Math::min);
                }
            }
        }

        @Override public void run() {
            boolean slot = false;
            long started = metrics == null ? 0 : android.os.SystemClock.elapsedRealtimeNanos();
            try {
                if (closed || !matchesPageDimensions(key)) return;
                if (speculative) {
                    if (!prefetchGeneration.isCurrent(epoch) ||
                            !speculativeTiles.beginSpeculativeDecode()) return;
                    slot = true;
                }
                Bitmap decoded = decodeTile(pageIndex, sample, renderScale, source);
                if (metrics != null) metrics.decode(speculative,
                        android.os.SystemClock.elapsedRealtimeNanos() - started, started - queuedAt);
                synchronized (TileRenderer.this) {
                    residentBytes();
                    boolean owned;
                    synchronized (pending) { owned = pending.get(key) == this && !finished.get(); }
                    if (decoded != null && !closed && owned && matchesPageDimensions(key)) {
                        // A running prefetch that became visible must satisfy that visible request,
                        // even when navigation has already advanced the offscreen generation.
                        if (speculative && !(tuning.prioritized && seenVisible)) {
                            final boolean[] adopted = {false};
                            boolean current = prefetchGeneration.publishIfCurrent(epoch,
                                    () -> {
                                        if (tuning.prioritized) adopted[0] = publishReserved(decoded);
                                        else {
                                            releaseReservation();
                                            adopted[0] = speculativeTiles.offer(TileRenderer.this, key, decoded, false);
                                        }
                                    });
                            if (!current || !adopted[0]) {
                                if (metrics != null && !current) metrics.stale();
                                if (current && tuning.boundedAdmission() && (!tuning.prioritized ||
                                        speculativeTiles.planned(TileRenderer.this, key))) synchronized (pending) {
                                    if (prefetchGeneration.isCurrent(epoch)) refusedPrefetch.add(key);
                                }
                                decoded.recycle();
                            } else {
                                rememberPrepared();
                                if (metrics != null) metrics.published(decoded.getAllocationByteCount());
                            }
                        } else {
                            releaseReservation();
                            tiles.put(key, decoded);
                            if (metrics != null) metrics.published(decoded.getAllocationByteCount());
                            residentBytes();
                            mainHandler.post(invalidator);
                        }
                    } else if (decoded != null) decoded.recycle();
                }
            } catch (OutOfMemoryError error) {
                if (metrics != null) metrics.failure();
                if (speculativeTiles != null) speculativeTiles.onMemoryPressure();
                trimMemory();
                if (!speculative) reportError(context.getString(R.string.error_tile_memory));
            } catch (IOException | RuntimeException error) {
                if (metrics != null) metrics.failure();
                if (speculative && tuning.boundedAdmission()) {
                    synchronized (pending) {
                        if (prefetchGeneration.isCurrent(epoch)) refusedPrefetch.add(key);
                    }
                }
                if (!speculative) reportError(context.getString(R.string.error_render_page, pageIndex + 1));
            } finally {
                if (slot) speculativeTiles.endSpeculativeDecode();
                if (speculative && seenVisible && !closed) mainHandler.post(invalidator);
                completeTask(this);
                if (tuning.refill && speculativeTiles != null && (slot || !speculative)) {
                    speculativeTiles.workFinished();
                }
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

        int fallbackSample = InputLimits.boundedBitmapSample(
                holder.width, holder.height, sample, tiles.maxSize() * 1024L / 4L);
        if (holder.fallback == null || holder.fallbackSample != fallbackSample) {
            // Keep one full-decoder backing image, bounded by this renderer's existing
            // visible-cache budget. Four decoder holders must not retain four 128 MiB images.
            for (DecoderHolder cached : decoders.values()) cached.clearFallback();
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

    private boolean matchesPageDimensions(TileKey key) {
        PageInfo page = archive.page(key.page());
        return page.width == key.width() && page.height == key.height();
    }

    private DecoderHolder decoderFor(int pageIndex) throws IOException {
        DecoderHolder existing = decoders.get(pageIndex);
        if (existing != null) return existing;

        PageInfo page = archive.page(pageIndex);
        InputLimits.validateImageDimensions(page.width, page.height);
        String extension = extension(page.name);
        File file = new File(pageCacheDirectory, String.format(java.util.Locale.ROOT,
                "page-%05d%s", pageIndex, extension));
        if (!file.isFile()) {
            long start = metrics == null ? 0 : android.os.SystemClock.elapsedRealtimeNanos();
            archive.extractPage(pageIndex, file);
            if (metrics != null) metrics.extracted(android.os.SystemClock.elapsedRealtimeNanos() - start);
        }

        // The document's page index can still contain provisional dimensions. Validate
        // the actual file before constructing a decoder or planning a full-image allocation.
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        InputLimits.validateImageDimensions(bounds.outWidth, bounds.outHeight);

        BitmapRegionDecoder regionDecoder = null;
        try {
            regionDecoder = BitmapRegionDecoder.newInstance(file.getAbsolutePath(), false);
        } catch (IOException | RuntimeException ignored) {
            // Animated GIF and a few older bitmap encodings need the full-decoder fallback.
        }
        DecoderHolder created = new DecoderHolder(file, regionDecoder, bounds.outWidth, bounds.outHeight);
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

    private synchronized void discardVisibleTiles() {
        // Trimming/closure/OOM must release pixels, not transfer them to another cache.
        retainEvictedTiles = false;
        try {
            tiles.evictAll();
        } finally {
            retainEvictedTiles = true;
        }
    }

    private java.util.List<Map.Entry<TileKey, Bitmap>> rasterFallbacks(int page, int sample) {
        Map<TileKey, Bitmap> cached = speculativeTiles == null
                ? new LinkedHashMap<>() : speculativeTiles.snapshot(this);
        cached.putAll(tiles.snapshot());
        java.util.List<Map.Entry<TileKey, Bitmap>> result = new ArrayList<>();
        for (Map.Entry<TileKey, Bitmap> entry : cached.entrySet()) {
            if (entry.getKey().page() == page && matchesPageDimensions(entry.getKey()) &&
                    entry.getKey().sample() != sample &&
                    !entry.getValue().isRecycled()) result.add(entry);
        }
        // Coarse pixels fill gaps first; finer cached tiles can then replace them.
        result.sort((first, second) ->
                Integer.compare(second.getKey().sample(), first.getKey().sample()));
        return result;
    }

    private double drawRasterFallback(Canvas canvas, PageInfo page, RectF pageDestination,
            RectF visible, RectF requestedDestination, Rect requestedSource,
            java.util.List<Map.Entry<TileKey, Bitmap>> cached) {
        if (cached.isEmpty()) return 0;
        // Diagnostics only. Union in integer source coordinates prevents double-counting
        // overlapping coarse/fine fallback tiles; project and clip to the actual viewport.
        Region coverage = metrics == null ? null : new Region();
        int saved = canvas.save();
        canvas.clipRect(visible);
        canvas.clipRect(requestedDestination);
        float scale = pageDestination.width() / page.width;
        for (Map.Entry<TileKey, Bitmap> entry : cached) {
            TileKey address = entry.getKey();
            int edge = RASTER_TILE_SIZE * address.sample();
            int left = address.x() * edge;
            int top = address.y() * edge;
            RectF target = new RectF(pageDestination.left + left * scale,
                    pageDestination.top + top * scale,
                    pageDestination.left + Math.min(page.width, left + edge) * scale,
                    pageDestination.top + Math.min(page.height, top + edge) * scale);
            if (RectF.intersects(target, requestedDestination) && RectF.intersects(target, visible)) {
                canvas.drawBitmap(entry.getValue(), null, target, imagePaint);
                if (coverage != null) {
                    Rect covered = new Rect(left, top, Math.min(page.width, left + edge),
                            Math.min(page.height, top + edge));
                    if (covered.intersect(requestedSource)) coverage.op(covered, Region.Op.UNION);
                }
            }
        }
        canvas.restoreToCount(saved);
        double area = 0;
        if (coverage != null) {
            RegionIterator iterator = new RegionIterator(coverage);
            Rect rect = new Rect(); RectF projected = new RectF();
            while (iterator.next(rect)) {
                projected.set(pageDestination.left + rect.left * scale, pageDestination.top + rect.top * scale,
                        pageDestination.left + rect.right * scale, pageDestination.top + rect.bottom * scale);
                if (projected.intersect(visible) && projected.intersect(requestedDestination))
                    area += projected.width() * (double) projected.height();
            }
        }
        return area;
    }

    private boolean drawRenderedFallback(
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
            Bitmap fallback = tiles.get(key(pageIndex, page, 1, fallbackScale, tileX, tileY));
            if (fallback == null && tuning.historyFallback && speculativeTiles != null) {
                fallback = speculativeTiles.borrow(this, key(pageIndex, page, 1, fallbackScale, tileX, tileY));
            }
            if (fallback != null && !fallback.isRecycled()) {
                canvas.drawBitmap(
                        fallback,
                        bitmapSourceRect(requestedSource, fallbackSource, fallback),
                        destination,
                        imagePaint);
                return true;
            }
            fallbackScale = RenderedTilePolicy.nextCoarserScale(fallbackScale);
        }
        return false;
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

    private static TileKey key(int pageIndex, PageInfo page, int sample, float renderScale, int x, int y) {
        return new TileKey(pageIndex, sample, renderScale, x, y, page.width, page.height);
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
        private final int width;
        private final int height;
        private Bitmap fallback;
        private int fallbackSample;

        private DecoderHolder(File file, BitmapRegionDecoder regionDecoder, int width, int height) {
            this.file = file;
            this.regionDecoder = regionDecoder;
            this.width = width;
            this.height = height;
        }

        private void close() {
            if (regionDecoder != null && !regionDecoder.isRecycled()) regionDecoder.recycle();
            clearFallback();
            file.delete();
        }

        private void clearFallback() {
            if (fallback != null && !fallback.isRecycled()) fallback.recycle();
            fallback = null;
            fallbackSample = 0;
        }
    }
}
