package io.github.jnesew.comicviewer.render;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.graphics.RegionIterator;
import android.os.Handler;
import android.os.Looper;

import io.github.jnesew.comicviewer.R;
import io.github.jnesew.comicviewer.document.ComicDocument;
import io.github.jnesew.comicviewer.model.PageInfo;
import io.github.jnesew.comicviewer.util.RenderedTilePolicy;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
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
    private static final int MAX_VISIBLE_ATTEMPTS = 3;

    private final Context context;
    private final ComicDocument archive;
    private final File pageCacheDirectory;
    private final RenderScheduler scheduler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable invalidator;
    private final ErrorListener errorListener;
    private final RenderCoordinator.VisibleTiles tiles;
    private final RenderCoordinator renderCoordinator;
    private final RenderTuning tuning;
    private final RenderMetrics metrics;
    private volatile java.util.List<PageRequest> refillRequests = java.util.Collections.emptyList();
    private volatile PrefetchPlan cachedPrefetchPlan;
    private long refillSignature;
    private int refillMaxTiles;
    private final AtomicBoolean refillPosted = new AtomicBoolean();
    private final java.util.Set<TileKey> refusedPrefetch = new java.util.HashSet<>();
    private final java.util.Set<TileKey> deferredDecoderPrefetch = new java.util.HashSet<>();
    private final Map<TileKey, Integer> preparedPrefetch = new HashMap<>();
    private final Map<TileKey, TileTask> pending = new HashMap<>();
    private java.util.Set<TileKey> visibleKeys = java.util.Collections.emptySet();
    private java.util.Set<Integer> visiblePageIndexes = java.util.Collections.emptySet();
    private final Map<TileKey, VisibleFailure> visibleFailures = new HashMap<>();
    private final Map<Integer, MemoryFailure> memoryFailures = new HashMap<>();
    private final Map<Integer, RenderLevel> renderLevels = new HashMap<>();
    private long lifecycleGeneration;
    private long pressureGeneration;
    private final AtomicBoolean cleanupPosted = new AtomicBoolean();
    private final java.util.concurrent.CountDownLatch cleanupDone = new java.util.concurrent.CountDownLatch(1);
    private final java.util.List<Runnable> closeCallbacks = new ArrayList<>();
    private final RasterPageDecoder rasterDecoder;
    private int speculativeQueued;
    private final PrefetchGeneration prefetchGeneration = new PrefetchGeneration();
    private float visibleRenderScale = Float.NaN;
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
        drawFrame(canvas, java.util.Collections.singletonMap(this, requests));
    }

    /** Plan all visible documents together before publishing any owner's demand. */
    static void drawFrame(Canvas canvas, Map<TileRenderer, java.util.List<PageRequest>> requests) {
        java.util.List<FramePage> frame = new ArrayList<>();
        java.util.List<RenderedTilePolicy.Region> regions = new ArrayList<>();
        long budget = Long.MAX_VALUE;
        for (Map.Entry<TileRenderer, java.util.List<PageRequest>> entry : requests.entrySet()) {
            TileRenderer renderer = entry.getKey();
            if (!renderer.canRender()) continue;
            if (renderer.archive.isUnavailable()) {
                for (PageRequest request : entry.getValue()) renderer.drawUnavailable(canvas, request);
                continue;
            }
            budget = Math.min(budget, renderer.tiles.maxSize() * 1024L * 3L / 4L);
            for (PageRequest request : entry.getValue()) {
                if (request.destination.width() <= 0 || request.destination.height() <= 0) continue;
                RectF visible = new RectF(request.destination);
                if (!visible.intersect(request.clip)) continue;
                PageInfo info = renderer.archive.page(request.page);
                float scale = request.destination.width() / info.width;
                RenderedTilePolicy.Region region = new RenderedTilePolicy.Region(
                        clamp((int) Math.floor((visible.left - request.destination.left) / scale), 0, info.width - 1),
                        clamp((int) Math.floor((visible.top - request.destination.top) / scale), 0, info.height - 1),
                        clamp((int) Math.ceil((visible.right - request.destination.left) / scale), 1, info.width),
                        clamp((int) Math.ceil((visible.bottom - request.destination.top) / scale), 1, info.height),
                        info.width, info.height, scale);
                regions.add(region);
                frame.add(new FramePage(renderer, request, info, region));
            }
        }
        boolean[] raster = new boolean[frame.size()];
        for (int i = 0; i < raster.length; i++) raster[i] = !frame.get(i).renderer.usesRenderedTiles();
        float[] scales = RenderedTilePolicy.mixedFrameScales(regions, raster, budget);
        float[] targets = RenderedTilePolicy.mixedFrameScales(regions, raster, Long.MAX_VALUE);
        float[] previous = new float[frame.size()];
        for (int i = 0; i < frame.size(); i++) {
            FramePage page = frame.get(i);
            synchronized (page.renderer.pending) {
                RenderLevel old = page.renderer.renderLevels.get(page.request.page);
                if (old != null && old.width == page.info.width && old.height == page.info.height &&
                        old.target == targets[i]) previous[i] = old.scale;
            }
        }
        RenderedTilePolicy.retainCoarserLevels(regions, raster, scales, previous, budget);
        Map<TileRenderer, java.util.Set<TileKey>> demand = new LinkedHashMap<>();
        for (TileRenderer renderer : requests.keySet()) demand.put(renderer, new java.util.HashSet<>());
        for (int i = 0; i < frame.size(); i++) {
            FramePage page = frame.get(i);
            TileRenderer renderer = page.renderer;
            synchronized (renderer.pending) {
                MemoryFailure failure = renderer.memoryFailures.get(page.request.page);
                if (failure != null && failure.width == page.info.width && failure.height == page.info.height) {
                    scales[i] = Math.min(scales[i], failure.scale);
                } else if (failure != null) {
                    renderer.memoryFailures.remove(page.request.page);
                }
            }
            synchronized (renderer.pending) {
                renderer.renderLevels.put(page.request.page, new RenderLevel(
                        page.info.width, page.info.height, targets[i], scales[i]));
            }
            renderer.visibleRenderScale = scales[i];
            renderer.addDemand(demand.get(renderer), page, scales[i]);
            if (renderer.prepareCoarse(page.request.page, page.region, scales[i])) {
                renderer.addDemand(demand.get(renderer), page, renderer.coarseScale(scales[i]));
            }
        }
        for (Map.Entry<TileRenderer, java.util.Set<TileKey>> entry : demand.entrySet()) {
            entry.getKey().replaceVisibleDemand(entry.getValue());
        }
        for (int i = 0; i < frame.size(); i++) {
            FramePage page = frame.get(i);
            page.renderer.drawPage(canvas, page.request.page, page.request.destination,
                    page.request.clip, scales[i]);
        }
    }

    private record RenderLevel(int width, int height, float target, float scale) { }

    private record FramePage(TileRenderer renderer, PageRequest request, PageInfo info,
            RenderedTilePolicy.Region region) { }

    private void addDemand(java.util.Set<TileKey> demand, FramePage page, float scale) {
        boolean rendered = archive.supportsRenderedTiles();
        int sample = rendered ? 1 : Math.round(1f / scale);
        int edge = rendered ? RenderedTilePolicy.sourceTileSize(scale) : RASTER_TILE_SIZE * sample;
        for (int y = page.region.top / edge; y <= (page.region.bottom - 1) / edge; y++) {
            for (int x = page.region.left / edge; x <= (page.region.right - 1) / edge; x++) {
                demand.add(key(page.request.page, page.info, sample, scale, x, y));
            }
        }
    }

    private float coarseScale(float scale) {
        return Math.max(archive.supportsRenderedTiles() ? RenderedTilePolicy.MIN_RENDER_SCALE : 1f / 64f,
                scale / 4f);
    }

    private boolean prepareCoarse(int page, RenderedTilePolicy.Region region, float scale) {
        synchronized (pending) { if (memoryFailures.containsKey(page)) return false; }
        long bytes = archive.supportsRenderedTiles() ? RenderedTilePolicy.tileBytes(region, scale)
                : RenderedTilePolicy.rasterTileBytes(region, scale);
        return tuning.prioritized && bytes > 4L * 1024 * 1024 && coarseScale(scale) < scale;
    }

    public boolean isUnavailable() { return archive.isUnavailable(); }

    private boolean canRender() {
        return !closed && renderCoordinator.isForeground();
    }

    public void clearVisibleRequests() { replaceVisibleDemand(java.util.Collections.emptySet()); }

    private void replaceVisibleDemand(java.util.Set<TileKey> demand) {
        synchronized (pending) {
            if (visibleKeys.equals(demand)) return;
            java.util.Set<Integer> pages = visiblePages(demand);
            if (!visiblePageIndexes.equals(pages)) {
                refusedPrefetch.removeAll(deferredDecoderPrefetch);
                deferredDecoderPrefetch.clear();
            }
            visibleKeys = demand;
            renderCoordinator.visibleDemand(this, demand);
            visiblePageIndexes = pages;
            visibleFailures.keySet().retainAll(demand);
            memoryFailures.keySet().retainAll(pages);
            renderLevels.keySet().retainAll(pages);
            for (TileTask task : new ArrayList<>(pending.values())) {
                if (!task.speculative && !demand.contains(task.key) && scheduler.remove(task)) completeTask(task);
            }
        }
    }

    private static java.util.Set<Integer> visiblePages(java.util.Set<TileKey> keys) {
        java.util.Set<Integer> pages = new java.util.HashSet<>();
        for (TileKey key : keys) pages.add(key.page());
        return pages;
    }

    /** Invalidate queued/running visible demand without waiting for native decoding. */
    public void suspend() {
        clearVisibleRequests();
        cancelPrefetch();
    }

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
            RenderCoordinator renderCoordinator) {
        this(context, archive, invalidator, errorListener, renderCoordinator,
                (int) (BufferingPolicy.visibleSessionBytes(
                        Runtime.getRuntime().maxMemory()) / 1024L));
    }

    /** Deterministic cache-size seam for real Android rendering regression tests. */
    TileRenderer(Context context, ComicDocument archive, Runnable invalidator,
            ErrorListener errorListener, RenderCoordinator renderCoordinator, int cacheKb) {
        this.archive = archive;
        this.context = context.getApplicationContext();
        this.invalidator = invalidator;
        this.errorListener = errorListener;
        if (renderCoordinator == null) renderCoordinator = new RenderCoordinator(
                Runtime.getRuntime().maxMemory(), BufferingPolicy.STANDARD, () -> true, () -> 0);
        this.renderCoordinator = renderCoordinator;
        this.scheduler = renderCoordinator.scheduler();
        this.tuning = renderCoordinator.tuning();
        this.metrics = renderCoordinator.metrics();
        this.pageCacheDirectory = new File(
                context.getCacheDir(), "page_tiles/session-" + Long.toUnsignedString(System.nanoTime()));
        if (!pageCacheDirectory.mkdirs() && !pageCacheDirectory.isDirectory()) {
            throw new IllegalStateException("Could not create the page tile cache.");
        }
        rasterDecoder = new RasterPageDecoder(archive, pageCacheDirectory, metrics, renderCoordinator, this);
        placeholderPaint.setColor(Color.rgb(24, 26, 31));

        tiles = renderCoordinator.visibleTiles(this, cacheKb);
        renderCoordinator.registerOwner(this);
        if (tuning.refill) renderCoordinator.onWorkAvailable(this, this::scheduleRefill);
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
        RenderedTilePolicy.Region region = new RenderedTilePolicy.Region(visibleLeft, visibleTop,
                visibleRight, visibleBottom, page.width, page.height, scale);
        boolean missingDetail = false;
        for (int y = firstY; y <= lastY && !missingDetail; y++) {
            for (int x = firstX; x <= lastX; x++) {
                if (tiles.get(key(pageIndex, page, sample, renderScale, x, y)) == null) {
                    missingDetail = true; break;
                }
            }
        }
        if (missingDetail && prepareCoarse(pageIndex, region, renderScale)) {
            float coarse = coarseScale(renderScale);
            int coarseSample = rendered ? 1 : Math.round(1f / coarse);
            int edge = rendered ? RenderedTilePolicy.sourceTileSize(coarse) : RASTER_TILE_SIZE * coarseSample;
            for (int y = visibleTop / edge; y <= (visibleBottom - 1) / edge; y++) {
                for (int x = visibleLeft / edge; x <= (visibleRight - 1) / edge; x++) {
                    requestTile(key(pageIndex, page, coarseSample, coarse, x, y), pageIndex,
                            coarseSample, coarse, new Rect(x * edge, y * edge,
                                    Math.min(page.width, (x + 1) * edge), Math.min(page.height, (y + 1) * edge)), false, true);
                }
            }
        }


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
                if (bitmap == null) bitmap = tiles.adoptOptional(key);
                boolean missing = bitmap == null || bitmap.isRecycled();
                double fallbackArea = 0;
                if (!missing) {
                    canvas.drawBitmap(bitmap, null, tileDestination, imagePaint);
                } else {
                    if (rendered && !tuning.prioritized) {
                        if (drawRenderedFallback(canvas, pageIndex, page, source, tileDestination, renderScale)
                                && metrics != null) {
                            RectF covered = new RectF(tileDestination);
                            if (covered.intersect(visible)) fallbackArea = covered.width() * (double) covered.height();
                        }
                    } else {
                        if (rasterFallbacks == null) {
                            rasterFallbacks = cachedFallbacks(pageIndex, sample, renderScale, rendered);
                        }
                        fallbackArea = drawCachedFallback(canvas, page, destination, visible,
                                tileDestination, source, rasterFallbacks, rendered);
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

    public void trimMemory() { trimMemory(true); }

    private void trimMemory(boolean clearFailures) {
        synchronized (pending) {
            if (closed) return;
            lifecycleGeneration++;
            cancelPrefetch();
            if (clearFailures) { visibleFailures.clear(); memoryFailures.clear(); renderLevels.clear(); }
            for (TileTask task : new ArrayList<>(pending.values())) {
                scheduler.remove(task);
                completeTask(task);
            }
            renderCoordinator.removeOwner(this);
            renderCoordinator.visibleDemand(this, visibleKeys);
            discardVisibleTiles();
            queueCleanup();
        }
    }

    @Override
    public void close() { closeAfter(null); }

    /** The document owner runs its final close after this worker has released its decoders. */
    public void closeAfter(Runnable afterClose) {
        synchronized (pending) {
            if (afterClose != null) {
                if (cleanupDone.getCount() == 0) {
                    new Thread(afterClose, "comic-document-close").start();
                    return;
                }
                closeCallbacks.add(afterClose);
            }
            if (closed) return;
            closed = true;
            lifecycleGeneration++;
            prefetchGeneration.close();
            cancelPrefetch();
            visibleKeys = java.util.Collections.emptySet();
            visiblePageIndexes = java.util.Collections.emptySet();
            visibleFailures.clear();
            memoryFailures.clear();
            discardVisibleTiles();
            for (TileTask task : new ArrayList<>(pending.values())) {
                scheduler.remove(task);
                completeTask(task);
            }
            renderCoordinator.removeOwner(this);
            queueCleanup();
            scheduler.interrupt(this);
        }
        if (Looper.myLooper() != Looper.getMainLooper() && !scheduler.isWorker(this)) {
            boolean interrupted = false;
            for (;;) {
                try { awaitStopped(); break; }
                catch (InterruptedException ignored) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    public void awaitStopped() throws InterruptedException { cleanupDone.await(); }

    private void queueCleanup() {
        if (!cleanupPosted.compareAndSet(false, true)) return;
        scheduler.execute(this, new RenderScheduler.Work(0) {
            @Override public void run() {
                cleanupPosted.set(false);
                rasterDecoder.trimMemory();
                if (!closed) return;
                renderCoordinator.unregisterOwner(TileRenderer.this);
                try {
                    deleteTree(pageCacheDirectory);
                    for (;;) {
                        java.util.List<Runnable> callbacks;
                        synchronized (pending) {
                            if (closeCallbacks.isEmpty()) { cleanupDone.countDown(); break; }
                            callbacks = new ArrayList<>(closeCallbacks);
                            closeCallbacks.clear();
                        }
                        for (Runnable callback : callbacks) callback.run();
                    }
                } finally { cleanupDone.countDown(); }
            }
        });
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
        if (closed || archive.isUnavailable() || !renderCoordinator.enabled() || maxTiles <= 0) return;
        if (prefetchGeneration.begin(signature)) {
            dropQueuedPrefetch();
            synchronized (pending) { refusedPrefetch.clear(); preparedPrefetch.clear(); deferredDecoderPrefetch.clear(); }
        }
        synchronized (pending) {
            long currentPressure = renderCoordinator.pressureGeneration();
            if (pressureGeneration != currentPressure) {
                pressureGeneration = currentPressure;
                refusedPrefetch.clear();
                deferredDecoderPrefetch.clear();
                preparedPrefetch.clear();
            }
        }
        if (tuning.refill) {
            refillRequests = new ArrayList<>(requests);
            refillSignature = signature;
            refillMaxTiles = maxTiles;
        }
        PrefetchPlan cached = cachedPrefetchPlan;
        if (tuning.prioritized && cached != null && cached.matches(requests)) {
            queuePrefetchCandidates(cached.candidates, cached.priorities, maxTiles);
            return;
        }
        Map<PageRequest, PageInfo> planningPages = new java.util.IdentityHashMap<>();
        for (PageRequest request : requests) planningPages.put(request, archive.page(request.page));
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
                    PageInfo info = planningPages.get(request);
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
            PageInfo page = planningPages.get(request);
            float scale = request.destination.width() / page.width;
            int left = clamp((int) Math.floor((area.left - request.destination.left) / scale), 0, page.width - 1);
            int top = clamp((int) Math.floor((area.top - request.destination.top) / scale), 0, page.height - 1);
            int right = clamp((int) Math.ceil((area.right - request.destination.left) / scale), 1, page.width);
            int bottom = clamp((int) Math.ceil((area.bottom - request.destination.top) / scale), 1, page.height);
            float planned;
            if (predictedScales.containsKey(request)) {
                planned = predictedScales.get(request);
            } else {
                RenderedTilePolicy.Region region = new RenderedTilePolicy.Region(
                        left, top, right, bottom, page.width, page.height, scale);
                java.util.List<RenderedTilePolicy.Region> single = java.util.Collections.singletonList(region);
                planned = archive.supportsRenderedTiles()
                        ? RenderedTilePolicy.frameScales(single, tiles.maxSize() * 1024L * 3L / 4L)[0]
                        : RenderedTilePolicy.rasterFrameScales(single, tiles.maxSize() * 1024L * 3L / 4L)[0];
                if (!Float.isNaN(visibleRenderScale)) planned = Math.min(planned, visibleRenderScale);
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
                        if (planBytes > renderCoordinator.limitBytes()) {
                            if (tuning.prioritized) break requestsLoop;
                            return;
                        }
                    }
                    if (tuning.prioritized) {
                        priorities.put(key, request.frame);
                        candidates.add(new PrefetchTile(key, request.page, sample, renderScale, source));
                        continue;
                    }
                    if (tiles.get(key) != null || renderCoordinator.contains(this, key)) continue;
                    synchronized (pending) { if (tuning.boundedAdmission() && refusedPrefetch.contains(key)) continue; }
                    considered++;
                    requestTile(key, request.page, sample, renderScale,
                            source, true);
                }
            }
        }
        if (tuning.prioritized) {
            cachedPrefetchPlan = new PrefetchPlan(requests, planningPages, candidates, priorities);
            queuePrefetchCandidates(candidates, priorities, maxTiles);
        }
    }

    private void queuePrefetchCandidates(java.util.List<PrefetchTile> candidates,
            Map<TileKey, Integer> priorities, int maxTiles) {
        renderCoordinator.plan(this, priorities);
        synchronized (pending) {
            for (TileTask task : new ArrayList<>(pending.values())) {
                if (task.speculative && !priorities.containsKey(task.key) && scheduler.remove(task)) {
                    completeTask(task);
                }
            }
        }
        int queued = 0;
        for (PrefetchTile tile : candidates) {
            if (tiles.get(tile.key) != null || renderCoordinator.contains(this, tile.key)) continue;
            synchronized (pending) {
                if (pending.containsKey(tile.key) || refusedPrefetch.contains(tile.key)) continue;
                if (preparedAlready(tile.key, renderCoordinator.priority(this, tile.key))) continue;
            }
            if (queued++ >= maxTiles) break;
            requestTile(tile.key, tile.page, tile.sample, tile.scale, tile.source, true);
        }
    }

    private record PrefetchTile(TileKey key, int page, int sample, float scale, Rect source) { }

    private final class PrefetchPlan {
        final java.util.List<PageRequest> requests = new ArrayList<>();
        final java.util.List<PageInfo> pages = new ArrayList<>();
        final java.util.List<PrefetchTile> candidates;
        final Map<TileKey, Integer> priorities;
        final float visibleScale = visibleRenderScale;
        final long byteLimit = renderCoordinator.limitBytes();
        PrefetchPlan(java.util.List<PageRequest> source, Map<PageRequest, PageInfo> planningPages,
                java.util.List<PrefetchTile> candidates,
                Map<TileKey, Integer> priorities) {
            for (PageRequest request : source) {
                requests.add(new PageRequest(request.page, request.destination, request.clip, request.frame, request.reverse));
                pages.add(planningPages.get(request));
            }
            this.candidates = candidates;
            this.priorities = priorities;
        }
        boolean matches(java.util.List<PageRequest> next) {
            if (requests.size() != next.size() || Float.compare(visibleScale, visibleRenderScale) != 0 ||
                    byteLimit != renderCoordinator.limitBytes()) return false;
            for (int i = 0; i < next.size(); i++) {
                PageRequest old = requests.get(i), request = next.get(i);
                PageInfo previous = pages.get(i), page = archive.page(request.page);
                if (old.page != request.page || old.frame != request.frame || old.reverse != request.reverse ||
                        !old.destination.equals(request.destination) || !old.clip.equals(request.clip) ||
                        previous.width != page.width || previous.height != page.height) return false;
            }
            return true;
        }
    }

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
        cachedPrefetchPlan = null;
        prefetchGeneration.cancel();
        dropQueuedPrefetch();
        if (tuning.prioritized) renderCoordinator.cancelPlan(this);
    }

    private void dropQueuedPrefetch() {
        synchronized (pending) {
            for (TileTask task : new ArrayList<>(pending.values())) {
                if (task.speculative && scheduler.remove(task)) completeTask(task);
            }
        }
    }

    private void requestTile(TileKey key, int pageIndex, int sample,
            float renderScale, Rect source, boolean speculative) {
        requestTile(key, pageIndex, sample, renderScale, source, speculative, false);
    }

    private void requestTile(TileKey key, int pageIndex, int sample,
            float renderScale, Rect source, boolean speculative, boolean coarse) {
        synchronized (pending) {
            // Refill the nearest tile on completion. Farther queued reservations must
            // not hold the allowance when a new near-frame plan arrives.
            int speculativeLimit = tuning.prioritized ? 1 : 16;
            if (!canRender() || (speculative && speculativeQueued >= speculativeLimit)) return;
            // Publication can win between the draw/planner's lookup and this admission lock.
            Bitmap ready = tiles.get(key);
            if ((ready != null && !ready.isRecycled()) ||
                    (renderCoordinator.contains(this, key))) {
                if (!speculative) mainHandler.post(invalidator);
                return;
            }
            MemoryFailure memoryFailure = memoryFailures.get(pageIndex);
            if (memoryFailure != null && (memoryFailure.width != key.width() || memoryFailure.height != key.height())) {
                memoryFailures.remove(pageIndex);
                memoryFailure = null;
            }
            if (!speculative && memoryFailure != null &&
                    (memoryFailure.attempts >= MAX_VISIBLE_ATTEMPTS ||
                            android.os.SystemClock.uptimeMillis() < memoryFailure.retryAt)) return;
            VisibleFailure failure = visibleFailures.get(key);
            if (!speculative && failure != null && (failure.attempts >= MAX_VISIBLE_ATTEMPTS ||
                    android.os.SystemClock.uptimeMillis() < failure.retryAt)) return;
            TileTask existing = pending.get(key);
            if (existing != null) {
                if (!speculative && existing.speculative) {
                    existing.seenVisible = true;
                    if (scheduler.remove(existing)) completeTask(existing);
                    else return; // The running tile will invalidate when finished.
                } else return;
            }
            long reservation = speculative && tuning.boundedAdmission() ? estimateBytes(source, renderScale) : 0L;
            int priority = speculative && tuning.prioritized ? renderCoordinator.priority(this, key) : Integer.MAX_VALUE;
            if (speculative && tuning.prioritized && preparedAlready(key, priority)) return;
            if (reservation > 0 && !(tuning.prioritized
                    ? renderCoordinator.reserve(this, key, reservation)
                    : renderCoordinator.reserve(reservation))) return;
            TileTask task = new TileTask(key, pageIndex, sample,
                    renderScale, new Rect(source), speculative, prefetchGeneration.current(),
                    reservation, priority, coarse);
            pending.put(key, task);
            if (speculative) speculativeQueued++;
            else renderCoordinator.visibleQueued();
            try {
                scheduler.execute(this, task);
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
        if (!task.running) task.releaseReservation();
        synchronized (pending) {
            pending.remove(task.key, task);
            if (task.speculative) speculativeQueued--;
        }
        if (!task.speculative && renderCoordinator != null) renderCoordinator.visibleFinished();
    }

    private final class TileTask extends RenderScheduler.Work {
        final TileKey key;
        final int pageIndex;
        final int sample;
        final float renderScale;
        final Rect source;
        final boolean speculative;
        final long epoch;
        final long lifecycle;
        final long reservation;
        final int priority;
        final long queuedAt;
        final AtomicBoolean finished = new AtomicBoolean();
        final AtomicBoolean reservationReleased = new AtomicBoolean();
        volatile boolean seenVisible;
        volatile boolean running;

        TileTask(TileKey key, int pageIndex, int sample, float renderScale,
                Rect source, boolean speculative, long epoch, long reservation, int priority, boolean coarse) {
            super(speculative ? 3 : coarse ? 1 : 2);
            this.key = key;
            this.pageIndex = pageIndex;
            this.sample = sample;
            this.renderScale = renderScale;
            this.source = source;
            this.speculative = speculative;
            this.epoch = epoch;
            this.lifecycle = lifecycleGeneration;
            this.reservation = reservation;
            this.priority = priority;
            this.queuedAt = metrics == null ? 0 : android.os.SystemClock.elapsedRealtimeNanos();
        }

        void releaseReservation() {
            if (reservation > 0 && reservationReleased.compareAndSet(false, true)) {
                renderCoordinator.release(reservation);
            }
        }

        boolean publishReserved(Bitmap bitmap) {
            return reservationReleased.compareAndSet(false, true) &&
                    renderCoordinator.publishReserved(TileRenderer.this, key, bitmap, reservation);
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
            long outputReservation = 0;
            long started = metrics == null ? 0 : android.os.SystemClock.elapsedRealtimeNanos();
            try {
                synchronized (pending) {
                    if (!current() || (!speculative && !visibleKeys.contains(key))) return;
                    running = true;
                }
                if (speculative) {
                    if (!prefetchGeneration.isCurrent(epoch) ||
                            !renderCoordinator.beginSpeculativeDecode()) return;
                    slot = true;
                }
                if (speculative && !seenVisible && !archive.supportsRenderedTiles() &&
                        rasterDecoder.wouldDisplaceVisible(pageIndex, visiblePageIndexes)) {
                    synchronized (pending) {
                        if (rasterDecoder.wouldDisplaceVisible(pageIndex, visiblePageIndexes)) {
                            if (prefetchGeneration.isCurrent(epoch)) {
                                refusedPrefetch.add(key);
                                deferredDecoderPrefetch.add(key);
                            }
                            if (metrics != null) metrics.decoderDenied();
                            return;
                        }
                    }
                }
                if (!speculative) {
                    outputReservation = estimateBytes(source, renderScale);
                    if (!renderCoordinator.reserveDecode(outputReservation)) {
                        outputReservation = 0;
                        throw new OutOfMemoryError("Session pixel budget exhausted");
                    }
                }
                Bitmap decoded = decodeTile(pageIndex, sample, renderScale, source);
                if (decoded == null) throw new IOException("Decoder returned no bitmap.");
                if (metrics != null) metrics.decode(speculative,
                        android.os.SystemClock.elapsedRealtimeNanos() - started, started - queuedAt);
                synchronized (pending) {
                    if (current() && (speculative || visibleKeys.contains(key))) {
                        // A running prefetch that became visible must satisfy that visible request,
                        // even when navigation has already advanced the offscreen generation.
                        if (speculative && !(tuning.prioritized && seenVisible)) {
                            final boolean[] adopted = {false};
                            boolean current = prefetchGeneration.publishIfCurrent(epoch,
                                    () -> {
                                        if (tuning.prioritized) adopted[0] = publishReserved(decoded);
                                        else {
                                            releaseReservation();
                                            adopted[0] = renderCoordinator.offer(TileRenderer.this, key, decoded, false);
                                        }
                                    });
                            if (!current || !adopted[0]) {
                                if (metrics != null && !current) metrics.stale();
                                if (current && tuning.boundedAdmission() && (!tuning.prioritized ||
                                        renderCoordinator.planned(TileRenderer.this, key))) synchronized (pending) {
                                    if (prefetchGeneration.isCurrent(epoch)) refusedPrefetch.add(key);
                                }
                                decoded.recycle();
                            } else {
                                rememberPrepared();
                                if (metrics != null) metrics.published(decoded.getAllocationByteCount());
                            }
                        } else {
                            releaseReservation();
                            long reserved = outputReservation;
                            outputReservation = 0;
                            tiles.put(key, decoded, reserved);
                            visibleFailures.remove(key);
                            errorReported = false;
                            if (metrics != null) metrics.published(decoded.getAllocationByteCount());
                            mainHandler.post(invalidator);
                        }
                    } else if (decoded != null) decoded.recycle();
                }
            } catch (OutOfMemoryError error) {
                if (metrics != null) metrics.failure();
                synchronized (pending) {
                    if (current()) {
                        renderCoordinator.onMemoryPressure();
                        trimMemory(false);
                        if (!speculative || seenVisible) {
                            RenderLevel requested = renderLevels.get(pageIndex);
                            retryAfterMemoryFailure(key, requested == null ? renderScale : requested.scale);
                        }
                        // Trimming cancels all visible work. Recover the remaining demand even
                        // when this tile has exhausted its retries or was only speculative.
                        long generation = lifecycleGeneration;
                        mainHandler.post(() -> {
                            synchronized (pending) {
                                if (canRender() && lifecycleGeneration == generation && !visibleKeys.isEmpty()) {
                                    invalidator.run();
                                }
                            }
                        });
                    }
                }
            } catch (IOException | RuntimeException error) {
                if (metrics != null) metrics.failure();
                if (speculative && tuning.boundedAdmission()) {
                    synchronized (pending) {
                        if (prefetchGeneration.isCurrent(epoch)) refusedPrefetch.add(key);
                    }
                }
                synchronized (pending) {
                    if (current() && (!speculative || seenVisible)) {
                        retryVisible(key);
                        if (!speculative) reportError(key, context.getString(R.string.error_render_page, pageIndex + 1));
                    }
                }
            } finally {
                if (outputReservation != 0) renderCoordinator.releaseDecode(outputReservation);
                releaseReservation();
                if (slot) renderCoordinator.endSpeculativeDecode();
                if (speculative && seenVisible && !closed) mainHandler.post(invalidator);
                completeTask(this);
                if (tuning.refill && (slot || !speculative)) {
                    renderCoordinator.workFinished();
                }
            }
        }

        private boolean current() {
            return canRender() && lifecycleGeneration == lifecycle && pending.get(key) == this &&
                    !finished.get() && matchesPageDimensions(key);
        }
    }

    private record MemoryFailure(int width, int height, int attempts, float scale, long retryAt) { }

    private void retryAfterMemoryFailure(TileKey key, float scale) {
        if (!visiblePageIndexes.contains(key.page())) return;
        MemoryFailure old = memoryFailures.get(key.page());
        int attempts = old == null ? 1 : old.attempts + 1;
        float cheaper = Math.max(archive.supportsRenderedTiles() ? RenderedTilePolicy.MIN_RENDER_SCALE
                : 1f / 64f, scale / 2f);
        MemoryFailure failure = new MemoryFailure(key.width(), key.height(), attempts, cheaper,
                android.os.SystemClock.uptimeMillis() + 250);
        memoryFailures.put(key.page(), failure);
        if (attempts >= MAX_VISIBLE_ATTEMPTS) {
            reportError(key, context.getString(R.string.error_tile_memory));
            return;
        }
        long generation = lifecycleGeneration;
        mainHandler.postDelayed(() -> {
            synchronized (pending) {
                if (canRender() && lifecycleGeneration == generation &&
                        memoryFailures.get(key.page()) == failure) invalidator.run();
            }
        }, 250);
    }

    private record VisibleFailure(int attempts, long retryAt) { }

    private void retryVisible(TileKey key) {
        if (!canRender() || !visibleKeys.contains(key)) return;
        VisibleFailure previous = visibleFailures.get(key);
        int attempts = previous == null ? 1 : previous.attempts + 1;
        long delay = attempts == 1 ? 250L : 1_000L;
        VisibleFailure failure = new VisibleFailure(attempts, android.os.SystemClock.uptimeMillis() + delay);
        visibleFailures.put(key, failure);
        if (attempts >= MAX_VISIBLE_ATTEMPTS) return;
        long generation = lifecycleGeneration;
        mainHandler.postDelayed(() -> {
            synchronized (pending) {
                if (canRender() && lifecycleGeneration == generation && visibleKeys.contains(key) &&
                        visibleFailures.get(key) == failure) invalidator.run();
            }
        }, delay);
    }

    private Bitmap decodeTile(int pageIndex, int sample, float renderScale, Rect source)
            throws IOException {
        if (closed) throw new IOException("Renderer is closed.");
        if (archive.supportsRenderedTiles()) return archive.renderTile(pageIndex, source, renderScale);
        java.util.Set<Integer> visible;
        synchronized (pending) { visible = new java.util.HashSet<>(visiblePageIndexes); }
        return rasterDecoder.decode(pageIndex, sample, source, visible, tiles.maxSize() * 1024L);
    }

    private boolean matchesPageDimensions(TileKey key) {
        PageInfo page = archive.page(key.page());
        return page.width == key.width() && page.height == key.height();
    }

    private void reportError(TileKey key, String message) {
        if (errorReported || closed) return;
        errorReported = true;
        long generation = lifecycleGeneration;
        mainHandler.post(() -> {
            synchronized (pending) {
                if (canRender() && lifecycleGeneration == generation && visibleKeys.contains(key)) {
                    errorListener.onRenderError(message);
                }
            }
        });
    }

    private void discardVisibleTiles() { tiles.evictAll(); }

    private java.util.List<Map.Entry<TileKey, Bitmap>> cachedFallbacks(int page, int sample,
            float renderScale, boolean rendered) {
        Map<TileKey, Bitmap> cached = renderCoordinator.snapshot(this);
        cached.putAll(tiles.snapshot());
        java.util.List<Map.Entry<TileKey, Bitmap>> result = new ArrayList<>();
        for (Map.Entry<TileKey, Bitmap> entry : cached.entrySet()) {
            if (entry.getKey().page() == page && matchesPageDimensions(entry.getKey()) &&
                    (rendered ? entry.getKey().renderScale() != renderScale : entry.getKey().sample() != sample) &&
                    !entry.getValue().isRecycled()) result.add(entry);
        }
        // Coarse pixels fill gaps first; finer cached tiles can then replace them.
        result.sort((first, second) -> rendered
                ? Float.compare(first.getKey().renderScale(), second.getKey().renderScale())
                : Integer.compare(second.getKey().sample(), first.getKey().sample()));
        return result;
    }

    private double drawCachedFallback(Canvas canvas, PageInfo page, RectF pageDestination,
            RectF visible, RectF requestedDestination, Rect requestedSource,
            java.util.List<Map.Entry<TileKey, Bitmap>> cached, boolean rendered) {
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
            int edge = rendered ? RenderedTilePolicy.sourceTileSize(address.renderScale())
                    : RASTER_TILE_SIZE * address.sample();
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
            if (fallback == null && tuning.historyFallback && renderCoordinator != null) {
                fallback = renderCoordinator.borrow(this, key(pageIndex, page, 1, fallbackScale, tileX, tileY));
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

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }

}
