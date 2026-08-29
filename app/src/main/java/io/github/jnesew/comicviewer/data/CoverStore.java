package io.github.jnesew.comicviewer.data;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Rect;

import io.github.jnesew.comicviewer.document.ComicDocument;
import io.github.jnesew.comicviewer.util.CacheKey;
import io.github.jnesew.comicviewer.util.InputLimits;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Creates bounded private cover thumbnails without decoding an entire long-strip page. */
public final class CoverStore {
    private static final int MAX_WIDTH = 720;
    private static final int MAX_HEIGHT = 1080;

    private CoverStore() {
    }

    public static String ensureCover(Context context, ComicDocument archive) throws IOException {
        File directory = directory(context);
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Could not create the cover cache.");
        }
        File destination = new File(directory, CacheKey.digest(archive.key()) + ".jpg");

        File sourceDirectory = new File(context.getCacheDir(), "cover_sources");
        if (!sourceDirectory.exists() && !sourceDirectory.mkdirs()) {
            throw new IOException("Could not prepare a cover.");
        }
        File source = new File(sourceDirectory, CacheKey.digest(archive.key()) + ".image");
        File partial = new File(directory, destination.getName() + ".partial");
        Bitmap decoded = null;
        Bitmap scaled = null;
        BitmapRegionDecoder regionDecoder = null;
        try {
            if (archive.supportsRenderedTiles()) {
                decoded = archive.renderCover(MAX_WIDTH, MAX_HEIGHT);
            } else {
                archive.extractCover(source);
                regionDecoder = BitmapRegionDecoder.newInstance(source.getAbsolutePath(), false);
                int width = regionDecoder.getWidth();
                int height = regionDecoder.getHeight();
                InputLimits.validateImageDimensions(width, height);
                Rect region = coverRegion(width, height);
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = sampleFor(region.width(), region.height());
                options.inPreferredConfig = Bitmap.Config.RGB_565;
                decoded = regionDecoder.decodeRegion(region, options);
            }
            if (decoded == null) throw new IOException("Android could not decode the cover page.");

            float scale = Math.min(1f, Math.min(
                    (float) MAX_WIDTH / decoded.getWidth(),
                    (float) MAX_HEIGHT / decoded.getHeight()));
            if (scale < 0.999f) {
                scaled = Bitmap.createScaledBitmap(decoded,
                        Math.max(1, Math.round(decoded.getWidth() * scale)),
                        Math.max(1, Math.round(decoded.getHeight() * scale)), true);
            } else {
                scaled = decoded;
            }

            try (BufferedOutputStream output = new BufferedOutputStream(
                    new FileOutputStream(partial), 64 * 1024)) {
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, 86, output)) {
                    throw new IOException("Could not encode the cover.");
                }
            }
            if (destination.exists() && !destination.delete()) {
                throw new IOException("Could not replace the cached cover.");
            }
            if (!partial.renameTo(destination)) {
                throw new IOException("Could not finish caching the cover.");
            }
            return destination.getAbsolutePath();
        } finally {
            if (regionDecoder != null && !regionDecoder.isRecycled()) regionDecoder.recycle();
            source.delete();
            if (partial.exists()) partial.delete();
            if (scaled != null && scaled != decoded && !scaled.isRecycled()) scaled.recycle();
            if (decoded != null && !decoded.isRecycled()) decoded.recycle();
        }
    }

    public static void delete(Context context, String path) {
        if (path == null || path.isEmpty()) return;
        try {
            File expectedDirectory = directory(context).getCanonicalFile();
            File target = new File(path).getCanonicalFile();
            if (expectedDirectory.equals(target.getParentFile())) target.delete();
        } catch (IOException ignored) {
        }
    }

    public static boolean exists(String path) {
        return path != null && !path.isEmpty() && new File(path).isFile();
    }

    private static File directory(Context context) {
        return new File(context.getFilesDir(), "covers");
    }

    private static Rect coverRegion(int width, int height) {
        if (height > width * 2) {
            return new Rect(0, 0, width, Math.min(height, Math.round(width * 1.5f)));
        }
        return new Rect(0, 0, width, height);
    }

    private static int sampleFor(int width, int height) {
        int sample = 1;
        while (sample < 64 && (width / (sample * 2) >= MAX_WIDTH ||
                height / (sample * 2) >= MAX_HEIGHT)) sample *= 2;
        return sample;
    }
}
