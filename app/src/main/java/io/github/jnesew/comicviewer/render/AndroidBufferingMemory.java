package io.github.jnesew.comicviewer.render;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Debug;
import android.os.SystemClock;

import java.util.function.BooleanSupplier;

/** Samples system pressure at most once per five seconds while speculative work is requested. */
public final class AndroidBufferingMemory implements BooleanSupplier {
    private static final long SYSTEM_SAMPLE_INTERVAL_MS = 5_000L;
    private final ActivityManager manager;
    private final long heapBytes;
    private final ActivityManager.MemoryInfo system = new ActivityManager.MemoryInfo();
    private boolean lowRam = true;
    private boolean known;
    private long nextSystemSample;

    public AndroidBufferingMemory(Context context, long heapBytes) {
        manager = context.getApplicationContext().getSystemService(ActivityManager.class);
        this.heapBytes = heapBytes;
    }

    public static String deviceDefault(Context context) {
        try {
            ActivityManager manager = context.getSystemService(ActivityManager.class);
            if (manager == null) return BufferingPolicy.STANDARD;
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            manager.getMemoryInfo(info);
            return BufferingPolicy.deviceDefault(info.totalMem,
                    Runtime.getRuntime().maxMemory(), manager.isLowRamDevice());
        } catch (RuntimeException ignored) {
            return BufferingPolicy.STANDARD;
        }
    }

    @Override public synchronized boolean getAsBoolean() {
        long now = SystemClock.elapsedRealtime();
        if (now >= nextSystemSample) {
            nextSystemSample = now + SYSTEM_SAMPLE_INTERVAL_MS;
            known = false;
            try {
                if (manager != null) {
                    manager.getMemoryInfo(system);
                    lowRam = manager.isLowRamDevice();
                    known = true;
                }
            } catch (RuntimeException ignored) {
                // Unknown pressure must not authorize extra decoding.
            }
        }
        if (!known) return false;
        Runtime runtime = Runtime.getRuntime();
        long availableHeap = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory());
        return BufferingPolicy.hasMemoryRoom(heapBytes, availableHeap,
                Debug.getNativeHeapAllocatedSize(), system.totalMem, system.availMem,
                system.threshold, system.lowMemory, lowRam);
    }
}
