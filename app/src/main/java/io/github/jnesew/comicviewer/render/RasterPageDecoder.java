package io.github.jnesew.comicviewer.render;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Rect;

import io.github.jnesew.comicviewer.document.ComicDocument;
import io.github.jnesew.comicviewer.model.PageInfo;
import io.github.jnesew.comicviewer.util.InputLimits;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Raster decoder state, confined to this document's serialized scheduler work. */
final class RasterPageDecoder {
    private static final int MAX_OPEN_DECODERS = 4;
    private static final long MAX_CACHED_PAGE_BYTES = InputLimits.MAX_PAGE_BYTES;
    private final LinkedHashMap<Integer, File> pageFiles = new LinkedHashMap<>(8, .75f, true);
    private long cachedPageBytes;
    private final ComicDocument archive;
    private final File pageCacheDirectory;
    private final RenderMetrics metrics;
    private final RenderCoordinator coordinator;
    private final Object owner;
    private final LinkedHashMap<Integer, DecoderHolder> decoders = new LinkedHashMap<>(8, .75f, true);
    private volatile long backingBytes;
    private Set<Integer> visiblePages = java.util.Collections.emptySet();
    private long backingLimit;

    RasterPageDecoder(ComicDocument archive, File directory, RenderMetrics metrics,
            RenderCoordinator coordinator, Object owner) {
        this.archive = archive;
        this.pageCacheDirectory = directory;
        this.metrics = metrics;
        this.coordinator = coordinator;
        this.owner = owner;
    }

    boolean wouldDisplaceVisible(int page, Set<Integer> visible) {
        return !decoders.containsKey(page) && decoders.size() >= MAX_OPEN_DECODERS &&
                visible.containsAll(decoders.keySet());
    }

    long backingBytes() { return backingBytes; }

    Bitmap decode(int pageIndex, int sample, Rect source, Set<Integer> visible, long limit)
            throws IOException {
        visiblePages = visible;
        backingLimit = Math.min(limit, coordinator.backingLimitBytes());
        return decodeTile(pageIndex, sample, source);
    }

    void trimMemory() {
        for (DecoderHolder holder : decoders.values()) holder.close();
        decoders.clear();
        backingBytes = 0;
        coordinator.claimBacking(owner, 0);
    }

    private Bitmap decodeTile(
            int pageIndex, int sample, Rect source) throws IOException {
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
                holder.width, holder.height, sample, backingLimit / 4L);
        if (holder.fallback == null || holder.requestedFallbackSample != fallbackSample) {
            // One backing image per document; reserve its pixels across the session before
            // allocating. A pressure-reduced image remains useful for subsequent tiles.
            for (DecoderHolder cached : decoders.values()) cached.clearFallback();
            backingBytes = 0;
            coordinator.claimBacking(owner, 0);
            int requestedSample = fallbackSample;
            while (!coordinator.claimBacking(owner, bitmapBytes(holder.width, holder.height, fallbackSample))) {
                if (fallbackSample >= 1 << 28) throw new OutOfMemoryError("No room for a sampled backing image");
                fallbackSample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = fallbackSample;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try {
                holder.fallback = BitmapFactory.decodeFile(holder.file.getAbsolutePath(), options);
            } catch (RuntimeException | OutOfMemoryError error) {
                coordinator.claimBacking(owner, 0);
                throw error;
            }
            holder.fallbackSample = fallbackSample;
            holder.requestedFallbackSample = requestedSample;
            if (holder.fallback == null) {
                coordinator.claimBacking(owner, 0);
                throw new IOException("Bitmap decoder returned no image.");
            }
            backingBytes = holder.fallback.getAllocationByteCount();
            if (!coordinator.claimBacking(owner, backingBytes)) {
                holder.clearFallback(); backingBytes = 0; coordinator.claimBacking(owner, 0);
                throw new OutOfMemoryError("Backing image exceeded its reservation");
            }
        }

        fallbackSample = holder.fallbackSample;
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
        if (existing != null) { pageFiles.get(pageIndex); return existing; }

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

        if (pageFiles.put(pageIndex, file) == null) cachedPageBytes += file.length();
        trimPageFiles(pageIndex);

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
        if (metrics != null) metrics.decoderOpened();
        trimDecoders(pageIndex, visiblePages);
        long retained = 0;
        for (DecoderHolder holder : decoders.values()) {
            if (holder.fallback != null) retained += holder.fallback.getAllocationByteCount();
        }
        backingBytes = retained;
        coordinator.claimBacking(owner, retained);
        return created;
    }

    private void trimDecoders(int requestedPage, java.util.Set<Integer> visiblePages) {
        while (decoders.size() > MAX_OPEN_DECODERS) {
            Map.Entry<Integer, DecoderHolder> eldest = decoders.entrySet().iterator().next();
            for (Map.Entry<Integer, DecoderHolder> candidate : decoders.entrySet()) {
                if (candidate.getKey() != requestedPage && !visiblePages.contains(candidate.getKey())) {
                    eldest = candidate;
                    break;
                }
            }
            decoders.remove(eldest.getKey());
            eldest.getValue().close();
            if (metrics != null) metrics.decoderEvicted();
        }
    }

    /** Encoded bytes can survive decoder eviction, but never grow past one maximum page. */
    private void trimPageFiles(int requestedPage) throws IOException {
        while (cachedPageBytes > MAX_CACHED_PAGE_BYTES && pageFiles.size() > 1) {
            Map.Entry<Integer, File> victim = pageFiles.entrySet().iterator().next();
            if (victim.getKey() == requestedPage) {
                for (Map.Entry<Integer, File> candidate : pageFiles.entrySet()) {
                    if (candidate.getKey() != requestedPage) { victim = candidate; break; }
                }
            }
            DecoderHolder decoder = decoders.remove(victim.getKey());
            if (decoder != null) {
                if (decoder.fallback != null) backingBytes -= decoder.fallback.getAllocationByteCount();
                decoder.close();
                coordinator.claimBacking(owner, backingBytes);
                if (metrics != null) metrics.decoderEvicted();
            }
            long bytes = victim.getValue().length();
            if (!victim.getValue().delete() && victim.getValue().exists()) {
                throw new IOException("Could not trim the extracted page cache.");
            }
            cachedPageBytes -= bytes;
            pageFiles.remove(victim.getKey());
        }
    }

    private static long bitmapBytes(int width, int height, int sample) {
        return 4L * ((width + (long) sample - 1) / sample) * ((height + (long) sample - 1) / sample);
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

    private static final class DecoderHolder {
        private final File file;
        private final BitmapRegionDecoder regionDecoder;
        private final int width;
        private final int height;
        private Bitmap fallback;
        private int fallbackSample;
        private int requestedFallbackSample;

        private DecoderHolder(File file, BitmapRegionDecoder regionDecoder, int width, int height) {
            this.file = file;
            this.regionDecoder = regionDecoder;
            this.width = width;
            this.height = height;
        }

        private void close() {
            if (regionDecoder != null && !regionDecoder.isRecycled()) regionDecoder.recycle();
            clearFallback();
        }

        private void clearFallback() {
            if (fallback != null && !fallback.isRecycled()) fallback.recycle();
            fallback = null;
            fallbackSample = 0;
            requestedFallbackSample = 0;
        }
    }
}
