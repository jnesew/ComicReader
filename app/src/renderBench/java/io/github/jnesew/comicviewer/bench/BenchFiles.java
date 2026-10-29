package io.github.jnesew.comicviewer.bench;

import android.content.Context;
import android.os.Build;
import android.app.ActivityManager;
import android.os.Debug;
import android.os.PowerManager;
import io.github.jnesew.comicviewer.BuildConfig;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class BenchFiles {
    private final Context context;
    final File directory;
    private int events;
    BenchFiles(Context context, String suite, String osLabel) throws Exception {
        this.context = context;
        directory = new File(context.getFilesDir(), "bench-runs/" + System.currentTimeMillis());
        if (!directory.mkdirs()) throw new IOException("Cannot create run directory");
        JSONObject meta = new JSONObject();
        meta.put("schema", 2); meta.put("protocol", BenchPlan.PROTOCOL);
        meta.put("suite", suite); meta.put("source", BuildConfig.BENCH_SOURCE);
        meta.put("coverage_metrics", "exact_missing,uncovered,fallback; source-rectangle union; version 1");
        if (new BenchPlan(suite).scrolling()) meta.put("motion_profile", "cancel-ended repeated drags v1; target and delivered speed exported per action");
        meta.put("session_reuse", new BenchPlan(suite).sustained());
        meta.put("frame_attribution", "intended_vsync_timestamp; bounded 8192 frames per case; 250ms drain grace");
        meta.put("readiness_poll_ms", 16); meta.put("action_window_settle_ms", new BenchPlan(suite).shortRun() ? 60 : 180);
        meta.put("warmup", "One initial baseline viewport per fixture/mode; repeat -1, excluded from comparisons");
        meta.put("package", context.getPackageName());
        android.content.pm.PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        meta.put("version", info.versionName); meta.put("version_code", info.versionCode);
        meta.put("debuggable", (context.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        meta.put("model", Build.MODEL); meta.put("manufacturer", Build.MANUFACTURER);
        meta.put("android", Build.VERSION.RELEASE); meta.put("api", Build.VERSION.SDK_INT);
        meta.put("security_patch", Build.VERSION.SECURITY_PATCH); meta.put("os_user_label", osLabel);
        meta.put("heap_limit_bytes", Runtime.getRuntime().maxMemory());
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        meta.put("memory_class_mib", manager == null ? JSONObject.NULL : manager.getMemoryClass());
        meta.put("large_memory_class_mib", manager == null ? JSONObject.NULL : manager.getLargeMemoryClass());
        meta.put("large_heap_requested", (context.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_LARGE_HEAP) != 0);
        meta.put("abis", new JSONArray(Build.SUPPORTED_ABIS));
        meta.put("available_processors", Runtime.getRuntime().availableProcessors());
        meta.put("initial_refresh_hz", context.getSystemService(android.view.WindowManager.class).getDefaultDisplay().getRefreshRate());
        meta.put("memory", memory(context));
        meta.put("fixture_manifest", new JSONObject(new String(readAsset(context, "manifest.json"), StandardCharsets.UTF_8)));
        meta.put("limits", "Protocol 2 changes measurement windows/scroll settling. Fresh sessions except sustained suite; not kernel-cache cold. Exact gaps may have fallback. Frame phases overlap and do not identify an upload bottleneck alone. No full GPU/process PSS or energy measurement; synthetic local files, not a provider benchmark.");
        write("run.json", meta.toString(2));
        write("cases.jsonl", ""); write("events.jsonl", ""); write("actions.jsonl", ""); write("checkpoints.jsonl", "");
    }
    void write(String name, String data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(new File(directory, name))) {
            out.write(data.getBytes(StandardCharsets.UTF_8)); out.getFD().sync();
        }
    }
    synchronized void event(JSONObject value) throws IOException {
        if (events++ < 12000) append("events.jsonl", value);
    }
    synchronized void result(JSONObject value) throws IOException { append("cases.jsonl", value); }
    synchronized void action(JSONObject value) throws IOException { append("actions.jsonl", value); }
    synchronized void checkpoint(JSONObject value) throws IOException { append("checkpoints.jsonl", value); }
    private void append(String name, JSONObject value) throws IOException {
        try (FileOutputStream out = new FileOutputStream(new File(directory, name), true)) {
            out.write((value.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
    }
    static JSONObject memory(Context context) throws Exception {
        JSONObject out = new JSONObject();
        Runtime runtime = Runtime.getRuntime();
        out.put("java_used_bytes", runtime.totalMemory() - runtime.freeMemory());
        out.put("native_allocated_bytes", Debug.getNativeHeapAllocatedSize());
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        if (manager != null) {
            ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
            manager.getMemoryInfo(memory);
            out.put("system_total_bytes", memory.totalMem); out.put("system_available_bytes", memory.availMem);
            out.put("system_low_memory", memory.lowMemory); out.put("low_ram_device", manager.isLowRamDevice());
        }
        PowerManager power = context.getSystemService(PowerManager.class);
        if (power != null) {
            out.put("power_save", power.isPowerSaveMode());
            if (Build.VERSION.SDK_INT >= 29) out.put("thermal_status", power.getCurrentThermalStatus());
        }
        android.os.BatteryManager battery = context.getSystemService(android.os.BatteryManager.class);
        if (battery != null) { out.put("battery_percent", battery.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)); out.put("charging", battery.isCharging()); }
        return out;
    }
    static File fixture(Context context, String name) throws Exception {
        JSONArray list = new JSONObject(new String(readAsset(context, "manifest.json"), StandardCharsets.UTF_8)).getJSONArray("fixtures");
        JSONObject expected = null;
        for (int i = 0; i < list.length(); i++) if (name.equals(list.getJSONObject(i).getString("name"))) expected = list.getJSONObject(i);
        if (expected == null) throw new IOException("Unknown fixture");
        File root = new File(context.getFilesDir(), "bench-fixtures");
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot prepare fixtures");
        File file = new File(root, name);
        byte[] data = readAsset(context, name);
        if (!hex(MessageDigest.getInstance("SHA-256").digest(data)).equals(expected.getString("sha256"))) {
            throw new IOException("Fixture hash mismatch");
        }
        Files.write(file.toPath(), data);
        return file;
    }
    static byte[] readAsset(Context context, String name) throws IOException {
        try (InputStream input = context.getAssets().open("bench/" + name);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[32768]; int read;
            while ((read = input.read(buffer)) != -1) {
                if (output.size() + read > 32 * 1024 * 1024) throw new IOException("Asset exceeds test limit");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }
    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte value : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return out.toString();
    }
    static void export(Context context, File run, OutputStream destination, boolean fixtures) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(destination)) {
            if (fixtures) {
                for (String name : context.getAssets().list("bench")) {
                    zip.putNextEntry(new ZipEntry(name)); zip.write(readAsset(context, name)); zip.closeEntry();
                }
            } else {
                for (String name : new String[]{"run.json", "cases.jsonl", "events.jsonl", "actions.jsonl", "checkpoints.jsonl", "summary.txt", "results.csv", "state.json"}) {
                    File file = new File(run, name);
                    if (!file.isFile()) continue;
                    zip.putNextEntry(new ZipEntry(name)); Files.copy(file.toPath(), zip); zip.closeEntry();
                }
            }
        }
    }
    static File latest(Context context) {
        File[] dirs = new File(context.getFilesDir(), "bench-runs").listFiles(File::isDirectory);
        if (dirs == null || dirs.length == 0) return null;
        java.util.Arrays.sort(dirs, java.util.Comparator.comparing(File::getName));
        return dirs[dirs.length - 1];
    }
}
