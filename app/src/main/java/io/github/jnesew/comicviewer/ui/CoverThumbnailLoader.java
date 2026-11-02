package io.github.jnesew.comicviewer.ui;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import io.github.jnesew.comicviewer.model.ReadingProgress;
import io.github.jnesew.comicviewer.util.Ui;
import java.io.File;
import java.util.Map;
import java.util.HashMap;
import java.util.WeakHashMap;
import java.util.function.Function;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
/** Bounded cover decoding. UI binding and cache publication happen on the main thread. */
public final class CoverThumbnailLoader implements AutoCloseable {
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean closed;
    private volatile boolean foreground = true;
    private volatile long generation;
    private final ThreadPoolExecutor coverLoader = new ThreadPoolExecutor(2, 2, 0L,
            TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), runnable -> {
        Thread thread = new Thread(runnable, "comic-cover-loader");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    private final Map<String, Request> inFlight = new HashMap<>();
    private final Map<ImageView, Binding> bindings = new WeakHashMap<>();
    private final Function<File, Bitmap> decoder;
    private final LruCache<String, Bitmap> coverCache;

    private static final class Binding {
        final String key;
        final File file;
        Request request;
        Binding(String key, File file) { this.key = key; this.file = file; }
    }
    private final class Request implements Runnable {
        final String key;
        final File file;
        final long epoch = generation;
        final Map<ImageView, Binding> subscribers = new WeakHashMap<>();
        Request(String key, File file) { this.key = key; this.file = file; }
        synchronized boolean hasSubscribers() { return !subscribers.isEmpty(); }
        synchronized void subscribe(ImageView image, Binding binding) {
            subscribers.put(image, binding);
            binding.request = this;
        }
        synchronized void unsubscribe(ImageView image, Binding binding) {
            subscribers.remove(image, binding);
            if (subscribers.isEmpty() && coverLoader.remove(this)) inFlight.remove(key, this);
        }
        @Override public void run() {
            Bitmap bitmap = null;
            boolean skipped = !foreground || closed || generation != epoch || !hasSubscribers();
            if (!skipped) {
                try { bitmap = decoder.apply(file); }
                catch (RuntimeException | OutOfMemoryError ignored) { }
            }
            Bitmap ready = bitmap;
            if (closed || !foreground || generation != epoch) {
                if (ready != null) ready.recycle();
                return;
            }
            mainHandler.post(() -> finish(ready, skipped));
        }
        private synchronized void finish(Bitmap ready, boolean skipped) {
            if (!inFlight.remove(key, this) || closed || !foreground || generation != epoch || subscribers.isEmpty()) {
                if (ready != null) ready.recycle();
                return;
            }
            if (ready != null && !ready.isRecycled()) coverCache.put(key, ready);
            for (Map.Entry<ImageView, Binding> subscriber : subscribers.entrySet()) {
                ImageView image = subscriber.getKey();
                Binding binding = subscriber.getValue();
                if (binding.request == this) binding.request = null;
                if (image.getTag() != binding) continue;
                if (ready != null && !ready.isRecycled()) image.setImageBitmap(ready);
            }
            if (skipped) resumeBindings();
        }
    }

    public CoverThumbnailLoader() {
        this(CoverThumbnailLoader::decodeCover);
    }

    CoverThumbnailLoader(Function<File, Bitmap> decoder) {
        this.decoder = decoder;
        long heapKb = Runtime.getRuntime().maxMemory() / 1024L;
        int cacheKb = (int) Math.min(24L * 1024L, Math.max(8L * 1024L, heapKb / 16L));
        coverCache = new LruCache<>(cacheKb) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return Math.max(1, bitmap.getAllocationByteCount() / 1024);
            }
        };

    }
    public void trimMemory() {
        cancelRequests();
        coverCache.trimToSize(Math.max(1, coverCache.maxSize() / 3));
        long epoch = generation;
        mainHandler.postDelayed(() -> {
            if (!closed && foreground && generation == epoch) resumeBindings();
        }, 5_000L);
    }
    public void setForeground(boolean foreground) {
        if (closed || this.foreground == foreground) return;
        this.foreground = foreground;
        if (foreground) resumeBindings(); else cancelRequests();
    }
    private void cancelRequests() {
        generation++;
        for (Request request : inFlight.values()) {
            coverLoader.remove(request);
            synchronized (request) {
                for (Binding binding : request.subscribers.values()) binding.request = null;
                request.subscribers.clear();
            }
        }
        inFlight.clear();
    }
    private void resumeBindings() {
        for (Map.Entry<ImageView, Binding> entry : bindings.entrySet()) {
            if (entry.getKey().getTag() == entry.getValue() && entry.getKey().getDrawable() == null) {
                request(entry.getKey(), entry.getValue());
            }
        }
    }
    @Override public void close() {
        if (closed) return;
        closed = true;
        cancelRequests();
        coverLoader.shutdownNow();
        bindings.clear();
        mainHandler.removeCallbacksAndMessages(null);
        coverCache.evictAll();
    }
    public void bindCover(ImageView image, ReadingProgress item) {
        File file = item.coverPath == null ? null : new File(item.coverPath);
        String key = item.uri + '\n' + item.coverPath + ':' +
                (file != null && file.isFile() ? file.lastModified() + ":" + file.length() : "missing");
        if (closed) return;
        Binding old = bindings.get(image);
        if (old != null && old.request != null) old.request.unsubscribe(image, old);
        Binding binding = new Binding(key, item.coverPath == null || item.coverPath.isEmpty() ? null : file);
        bindings.put(image, binding);
        image.setTag(binding);
        image.setImageDrawable(null);
        image.setBackground(Ui.rounded(Ui.surface(image.getContext()), Ui.dp(image.getContext(), 12), Ui.surfaceHigh(image.getContext()),
                Ui.dp(image.getContext(), 1)));
        request(image, binding);
    }
    private void request(ImageView image, Binding binding) {
        String key = binding.key;
        Bitmap cached = coverCache.get(key);
        if (cached != null && !cached.isRecycled()) {
            image.setImageBitmap(cached);
            return;
        }
        if (!foreground || closed || binding.file == null || !binding.file.isFile()) return;
        inFlight.entrySet().removeIf(entry -> !entry.getValue().hasSubscribers() && coverLoader.remove(entry.getValue()));
        Request existing = inFlight.get(key);
        if (existing != null) {
            existing.subscribe(image, binding);
            return;
        }
        Request request = new Request(key, binding.file);
        request.subscribe(image, binding);
        inFlight.put(key, request);
        try {
            coverLoader.execute(request);
        } catch (RejectedExecutionException ignored) {
            inFlight.remove(key, request);
        }
    }

    private static Bitmap decodeCover(File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        while (sample < 16 && (bounds.outWidth / (sample * 2) >= 360 ||
                bounds.outHeight / (sample * 2) >= 540)) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

}
