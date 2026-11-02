package io.github.jnesew.comicviewer.ui;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.SystemClock;
import android.widget.ImageView;

import io.github.jnesew.comicviewer.model.ReadingProgress;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

public final class CoverRecoveryTests {
    private CoverRecoveryTests() { }

    public static void coalescedDecodeReachesCurrentView(Instrumentation instrumentation) {
        File file = new File(instrumentation.getTargetContext().getCacheDir(), "cover-rebind-fixture.jpg");
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        CoverThumbnailLoader loader = new CoverThumbnailLoader(source -> {
            calls.incrementAndGet(); started.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Cover barrier was not released");
            } catch (InterruptedException error) { throw new AssertionError(error); }
            Bitmap bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.RED); return bitmap;
        });
        ImageView first = new ImageView(instrumentation.getTargetContext());
        ImageView current = new ImageView(instrumentation.getTargetContext());
        ReadingProgress x = new ReadingProgress(); x.uri = "cover:X"; x.coverPath = file.getAbsolutePath();
        ReadingProgress y = new ReadingProgress(); y.uri = "cover:Y";
        try {
            if (!file.createNewFile() && !file.isFile()) throw new IOException("Cannot create cover fixture");
            instrumentation.runOnMainSync(() -> loader.bindCover(first, x));
            if (!started.await(5, TimeUnit.SECONDS)) throw new AssertionError("Cover decode never started");
            instrumentation.runOnMainSync(() -> {
                loader.bindCover(first, y);
                loader.bindCover(current, x);
            });
            release.countDown();
            AtomicBoolean delivered = new AtomicBoolean();
            long deadline = SystemClock.uptimeMillis() + 5_000;
            do {
                instrumentation.runOnMainSync(() -> delivered.set(current.getDrawable() != null));
                if (delivered.get()) break;
                SystemClock.sleep(10);
            } while (SystemClock.uptimeMillis() < deadline);
            if (!delivered.get()) throw new AssertionError("Coalesced decode lost the current view");
            instrumentation.runOnMainSync(() -> {
                if (first.getDrawable() != null) throw new AssertionError("Recycled view received a stale cover");
                if (calls.get() != 1) throw new AssertionError("Coalesced cover was decoded more than once");
            });
        } catch (IOException | InterruptedException error) { throw new AssertionError(error); }
        finally {
            release.countDown(); instrumentation.runOnMainSync(loader::close); file.delete();
        }
    }

    public static void obsoleteQueuedCoversDoNotDecode(Instrumentation instrumentation) {
        File file = new File(instrumentation.getTargetContext().getCacheDir(), "cover-queue-fixture.jpg");
        CountDownLatch started = new CountDownLatch(2), release = new CountDownLatch(1);
        List<String> decoded = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger calls = new AtomicInteger();
        CoverThumbnailLoader loader = new CoverThumbnailLoader(source -> {
            decoded.add(source.getName());
            if (calls.incrementAndGet() <= 2) {
                started.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Cover queue barrier was not released");
                } catch (InterruptedException error) { throw new AssertionError(error); }
            }
            return Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
        });
        ImageView first = new ImageView(instrumentation.getTargetContext());
        ImageView second = new ImageView(instrumentation.getTargetContext());
        ImageView current = new ImageView(instrumentation.getTargetContext());
        try {
            if (!file.createNewFile() && !file.isFile()) throw new IOException("Cannot create cover fixture");
            instrumentation.runOnMainSync(() -> {
                loader.bindCover(first, item("first", file));
                loader.bindCover(second, item("second", file));
            });
            if (!started.await(5, TimeUnit.SECONDS)) throw new AssertionError("Cover workers did not start");
            instrumentation.runOnMainSync(() -> {
                for (int i = 0; i < 30; i++) loader.bindCover(current, item("queued-" + i, file));
            });
            release.countDown(); awaitDrawable(instrumentation, current);
            if (calls.get() != 3) throw new AssertionError("Obsolete cover bindings decoded: " + calls.get());
        } catch (IOException | InterruptedException error) { throw new AssertionError(error); }
        finally { release.countDown(); instrumentation.runOnMainSync(loader::close); file.delete(); }
    }

    public static void backgroundTrimDisposesAndResumes(Instrumentation instrumentation) {
        File file = new File(instrumentation.getTargetContext().getCacheDir(), "cover-trim-fixture.jpg");
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Bitmap> first = new AtomicReference<>();
        CoverThumbnailLoader loader = new CoverThumbnailLoader(source -> {
            int call = calls.incrementAndGet();
            if (call == 1) {
                started.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Cover trim barrier was not released");
                } catch (InterruptedException error) { throw new AssertionError(error); }
            }
            Bitmap bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
            if (call == 1) first.set(bitmap);
            return bitmap;
        });
        ImageView current = new ImageView(instrumentation.getTargetContext());
        try {
            if (!file.createNewFile() && !file.isFile()) throw new IOException("Cannot create cover fixture");
            instrumentation.runOnMainSync(() -> loader.bindCover(current, item("trimmed", file)));
            if (!started.await(5, TimeUnit.SECONDS)) throw new AssertionError("Cover decode did not start");
            instrumentation.runOnMainSync(() -> { loader.setForeground(false); loader.trimMemory(); });
            release.countDown();
            long deadline = SystemClock.uptimeMillis() + 5_000;
            while ((first.get() == null || !first.get().isRecycled()) && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(10);
            if (first.get() == null || !first.get().isRecycled()) throw new AssertionError("Background completion retained pixels");
            instrumentation.runOnMainSync(() -> {
                if (current.getDrawable() != null) throw new AssertionError("Background completion reached the view");
                loader.setForeground(true);
            });
            awaitDrawable(instrumentation, current);
            if (calls.get() != 2) throw new AssertionError("Foreground demand was not restored once");
        } catch (IOException | InterruptedException error) { throw new AssertionError(error); }
        finally { release.countDown(); instrumentation.runOnMainSync(loader::close); file.delete(); }
    }

    private static ReadingProgress item(String uri, File file) {
        ReadingProgress item = new ReadingProgress(); item.uri = uri; item.coverPath = file.getAbsolutePath(); return item;
    }
    private static void awaitDrawable(Instrumentation instrumentation, ImageView image) {
        AtomicBoolean ready = new AtomicBoolean();
        long deadline = SystemClock.uptimeMillis() + 5_000;
        do {
            instrumentation.runOnMainSync(() -> ready.set(image.getDrawable() != null));
            if (ready.get()) return;
            SystemClock.sleep(10);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Current cover demand did not finish");
    }
}
