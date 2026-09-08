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
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
/** Bounded cover decoding. UI binding and cache publication happen on the main thread. */
public final class CoverThumbnailLoader implements AutoCloseable {
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean closed;
    private final ExecutorService coverLoader = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "comic-cover-loader");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    private final Set<String> inFlight = Collections.synchronizedSet(new HashSet<>());
    private final LruCache<String, Bitmap> coverCache;

    public CoverThumbnailLoader() {
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
        coverCache.trimToSize(Math.max(1, coverCache.maxSize() / 3));
    }
    @Override public void close() {
        if (closed) return;
        closed = true;
        coverLoader.shutdownNow();
        inFlight.clear();
        mainHandler.removeCallbacksAndMessages(null);
        coverCache.evictAll();
    }
    public void bindCover(ImageView image, ReadingProgress item) {
        File file = item.coverPath == null ? null : new File(item.coverPath);
        String key = item.uri + '\n' + item.coverPath + ':' +
                (file != null && file.isFile() ? file.lastModified() + ":" + file.length() : "missing");
        image.setTag(key);
        image.setImageDrawable(null);
        image.setBackground(Ui.rounded(Ui.SURFACE, Ui.dp(image.getContext(), 12), Ui.SURFACE_HIGH,
                Ui.dp(image.getContext(), 1)));
        Bitmap cached = coverCache.get(key);
        if (cached != null && !cached.isRecycled()) {
            image.setImageBitmap(cached);
            return;
        }
        if (item.coverPath == null || item.coverPath.isEmpty()) return;
        if (!file.isFile() || !inFlight.add(key)) return;
        try {
            coverLoader.execute(() -> {
                Bitmap bitmap = null;
                try {
                    bitmap = decodeCover(file);
                } finally {
                    inFlight.remove(key);
                }
                Bitmap ready = bitmap;
                if (ready != null && !closed) {
                    mainHandler.post(() -> {
                        if (closed || ready.isRecycled()) return;
                        coverCache.put(key, ready);
                        if (key.equals(image.getTag())) {
                            image.setImageBitmap(ready);
                        }
                    });
                }
            });
        } catch (RejectedExecutionException ignored) {
            inFlight.remove(key);
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
