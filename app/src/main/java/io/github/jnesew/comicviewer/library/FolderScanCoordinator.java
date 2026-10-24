package io.github.jnesew.comicviewer.library;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import io.github.jnesew.comicviewer.R;
import io.github.jnesew.comicviewer.data.CoverStore;
import io.github.jnesew.comicviewer.data.ContentFingerprint;
import io.github.jnesew.comicviewer.data.LibraryDatabase;
import io.github.jnesew.comicviewer.data.LibraryFolderScanner;
import io.github.jnesew.comicviewer.data.ReaderPreferences;
import io.github.jnesew.comicviewer.document.ComicDocumentFactory;
import io.github.jnesew.comicviewer.document.DocumentInfo;
import io.github.jnesew.comicviewer.model.LibraryFolder;
import io.github.jnesew.comicviewer.model.ReadingProgress;
import io.github.jnesew.comicviewer.util.InputLimits;
import io.github.jnesew.comicviewer.util.LibraryFolderLabel;
import io.github.jnesew.comicviewer.util.LibraryScanResult;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** One bounded worker and one queued job per selected tree; cancellation is source-specific. */
public final class FolderScanCoordinator implements AutoCloseable {
    public interface Listener {
        void onLibraryChanged();
        void onScanStatus(String detail, boolean scanning, boolean persistent);
    }
    public record FolderState(String detail, boolean scanning, boolean unavailable) { }
    private static final long AUTO_SCAN_COOLDOWN_MS = 5_000L;
    private final Context context;
    private final LibraryDatabase database;
    private final ReaderPreferences preferences;
    private final LibraryImportCoordinator imports;
    private final BooleanSupplier readerBusy;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ConcurrentHashMap<String, ScanJob> jobs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastStarted = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, FolderState> states = new ConcurrentHashMap<>();
    private final AtomicInteger nextGeneration = new AtomicInteger();
    private final AtomicInteger nextBatch = new AtomicInteger();
    private final LibraryFolderScanner.DirectoryCache directoryCache = new LibraryFolderScanner.DirectoryCache();
    private int cachedBatch;
    private final AtomicBoolean reconciliationQueued = new AtomicBoolean();
    private final AtomicInteger sourceRevision = new AtomicInteger();
    private final LibrarySourceReconciler reconciler;
    private volatile boolean destroyed;
    private volatile boolean needsReconciliation = true;
    private volatile Set<String> configuredTrees;
    private volatile int configurationVersion;
    private Listener listener;
    private final ExecutorService scanWorker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "comic-folder-scanner");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });

    public FolderScanCoordinator(Context context, LibraryDatabase database, ReaderPreferences preferences,
            LibraryImportCoordinator imports, BooleanSupplier readerBusy, Listener listener) {
        this.context = context.getApplicationContext();
        this.database = database;
        this.preferences = preferences;
        this.imports = imports;
        this.readerBusy = readerBusy;
        this.listener = listener;
        this.configuredTrees = new HashSet<>(preferences.libraryFolderUris());
        this.reconciler = new LibrarySourceReconciler(this.context, database);
    }

    public boolean isRunning() { return !jobs.isEmpty() || reconciliationQueued.get(); }

    public FolderState folderState(String uri) {
        return states.getOrDefault(uri, new FolderState("", false, false));
    }

    /** The Activity has already persisted the newly selected grant. */
    public void selectFolder(Uri selected) { selectFolder(selected, ""); }

    public void selectFolder(Uri selected, String previousUri) {
        if (!DocumentsContract.isTreeUri(selected) ||
                !InputLimits.isIdentifierAcceptable(selected.toString())) {
            throw new IllegalArgumentException("Invalid library folder");
        }
        String rootId = DocumentsContract.getTreeDocumentId(selected);
        if (!InputLimits.isIdentifierAcceptable(rootId)) throw new IllegalArgumentException("Invalid folder identifier");
        Uri document = DocumentsContract.buildDocumentUriUsingTree(selected, rootId);
        DocumentInfo info = ComicDocumentFactory.describe(context, document);
        LibraryFolder folder = new LibraryFolder(selected.toString(),
                LibraryFolderLabel.compact(info.displayName),
                InputLimits.normalizeText(rootId, InputLimits.MAX_PATH_CODE_POINTS));
        preferences.replaceLibraryFolder(previousUri, folder);
        configurationChanged();
        if (!previousUri.isEmpty() && !previousUri.equals(selected.toString())) cancelFolder(previousUri);
        for (LibraryFolder configured : preferences.libraryFolders()) {
            if (configured.sameTree(selected.toString())) {
                cancelFolder(configured.uri);
                lastStarted.remove(configured.uri);
                break;
            }
        }
        maybeScanLibraryFolder(true);
    }

    public void removeFolder(String uri) {
        preferences.removeLibraryFolder(uri);
        configurationChanged();
        cancelFolder(uri);
        states.remove(uri);
        lastStarted.remove(uri);
        requestReconciliation();
        updateLibraryFolderUi("", false, false);
    }

    private void configurationChanged() {
        configuredTrees = new HashSet<>(preferences.libraryFolderUris());
        configurationVersion++;
        requireReconciliation();
    }

    private void requireReconciliation() {
        sourceRevision.incrementAndGet();
        needsReconciliation = true;
    }

    private void cancelFolder(String uri) {
        ScanJob job = jobs.remove(uri);
        if (job != null) {
            job.cancelled = true;
            job.cancellation.cancel();
            if (job.future != null) job.future.cancel(true);
        }
    }

    private void updateLibraryFolderUi(String detail, boolean scanning, boolean persistent) {
        if (!destroyed && listener != null) listener.onScanStatus(detail, !jobs.isEmpty(), persistent);
    }

    private void notifyChanged() {
        if (!destroyed && listener != null) listener.onLibraryChanged();
    }

    private void releaseReadAccess(Uri uri) {
        try {
            context.getContentResolver().releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (RuntimeException ignored) {
            // Temporary grants do not have a persistable permission to release.
        }
    }

    @Override public void close() {
        destroyed = true;
        for (String uri : jobs.keySet()) cancelFolder(uri);
        listener = null;
        mainHandler.removeCallbacksAndMessages(null);
        scanWorker.shutdownNow();
    }

    public void awaitStopped() throws InterruptedException {
        scanWorker.awaitTermination(Long.MAX_VALUE, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    public void maybeScanLibraryFolder(boolean userRequested) {
        if (destroyed) return;
        requestReconciliation();
        long now = System.currentTimeMillis();
        int batch = nextBatch.incrementAndGet();
        for (LibraryFolder folder : preferences.libraryFolders()) {
            if (jobs.containsKey(folder.uri)) continue;
            if (!userRequested && now - lastStarted.getOrDefault(folder.uri, 0L) < AUTO_SCAN_COOLDOWN_MS) continue;
            ScanJob job = new ScanJob(folder, nextGeneration.incrementAndGet(), batch);
            if (jobs.putIfAbsent(folder.uri, job) != null) continue;
            states.put(folder.uri, new FolderState(context.getString(R.string.library_folder_waiting), true, false));
            job.future = scanWorker.submit(() -> scan(job));
        }
        updateLibraryFolderUi("", !jobs.isEmpty(), true);
    }

    private void scan(ScanJob job) {
        if (!current(job)) return;
        if (cachedBatch != job.batch) { directoryCache.clear(); cachedBatch = job.batch; }
        lastStarted.put(job.folder.uri, System.currentTimeMillis());
        states.put(job.folder.uri, new FolderState(context.getString(R.string.library_folder_scanning), true, false));
        mainHandler.post(() -> updateLibraryFolderUi(job.folder.label, true, true));
        ScanCounts counts = new ScanCounts();
        LibraryFolderScanner.Summary traversal = null;
        long started = System.currentTimeMillis();
        try {
            traversal = LibraryFolderScanner.scan(context, Uri.parse(job.folder.uri),
                    new LibraryFolderScanner.Cancellation() {
                        @Override public boolean isCancelled() { return !current(job); }
                        @Override public android.os.CancellationSignal signal() { return job.cancellation; }
                    }, entry -> processScannedEntry(entry, started, counts,
                            job.generation, job.fingerprintBudget), directoryCache);
            if (current(job) && traversal.complete() && counts.errors == 0 && !readerBusy.getAsBoolean()) {
                database.finishFolderScan(job.folder.uri, started);
                requireReconciliation();
            } else if (current(job) && readerBusy.getAsBoolean()) {
                // Revisit negative scan evidence after the reader no longer holds source keys.
                lastStarted.remove(job.folder.uri);
            }
        } catch (IOException | RuntimeException error) {
            counts.errors++;
            if (current(job)) {
                database.markFolderUnavailable(job.folder.uri);
                requireReconciliation();
            }
        }
        boolean inaccessible = traversal == null;
        LibraryScanResult result = folderScanResult(counts, traversal);
        String detail = inaccessible ? context.getString(R.string.library_folder_access_unavailable)
                : folderScanSummary(result);
        int version = configurationVersion;
        List<String> roots = preferences.libraryFolderUris();
        List<LibrarySourceReconciler.Change> changes = prepareChanges(roots, job.folder.uri,
                () -> !current(job) || version != configurationVersion || readerBusy.getAsBoolean());
        mainHandler.post(() -> {
            if (!current(job)) return;
            // Reader activation and source-key publication both run on the main thread.
            if (version == configurationVersion && !readerBusy.getAsBoolean()) {
                for (LibrarySourceReconciler.Change change : changes) {
                    if (!imports.isProcessing(change.before.uri)) change.apply();
                }
            } else requireReconciliation();
            jobs.remove(job.folder.uri, job);
            states.put(job.folder.uri, new FolderState(detail, false, inaccessible));
            notifyChanged();
            updateLibraryFolderUi(detail, false, inaccessible || result.shouldPersist());
            requestReconciliation();
        });
    }

    private boolean current(ScanJob job) {
        return !destroyed && !job.cancelled && !Thread.currentThread().isInterrupted() &&
                jobs.get(job.folder.uri) == job && configuredTrees.contains(job.folder.uri);
    }

    private void requestReconciliation() {
        if (destroyed || !jobs.isEmpty() || readerBusy.getAsBoolean() || !needsReconciliation ||
                !reconciliationQueued.compareAndSet(false, true)) return;
        int version = configurationVersion;
        int revision = sourceRevision.get();
        List<String> roots = preferences.libraryFolderUris();
        Set<String> releases = preferences.pendingFolderReleases();
        scanWorker.execute(() -> {
            if (destroyed) return;
            List<LibrarySourceReconciler.Change> changes;
            try {
                for (String uri : releases) {
                    if (!configuredTrees.contains(uri)) database.markFolderUnavailable(uri);
                }
                changes = reconciler.prepare(roots, null,
                        () -> destroyed || Thread.currentThread().isInterrupted() || version != configurationVersion);
            } catch (RuntimeException error) {
                android.util.Log.w("FolderScanCoordinator", "Source reconciliation deferred", error);
                mainHandler.post(() -> reconciliationQueued.set(false));
                return;
            }
            mainHandler.post(() -> {
                reconciliationQueued.set(false);
                if (destroyed) return;
                if (version != configurationVersion) { requestReconciliation(); return; }
                if (readerBusy.getAsBoolean()) return;
                boolean processing = false;
                for (LibrarySourceReconciler.Change change : changes) {
                    if (imports.isProcessing(change.before.uri)) processing = true;
                    else change.apply();
                }
                if (!processing) {
                    for (String uri : releases) {
                        if (!configuredTrees.contains(uri)) {
                            releaseReadAccess(Uri.parse(uri));
                            preferences.finishFolderRelease(uri);
                        }
                    }
                    if (revision == sourceRevision.get() && jobs.isEmpty()) needsReconciliation = false;
                }
                notifyChanged();
                updateLibraryFolderUi("", !jobs.isEmpty(), false);
            });
        });
    }

    private List<LibrarySourceReconciler.Change> prepareChanges(List<String> roots,
            String affectedTree, BooleanSupplier cancelled) {
        try { return reconciler.prepare(roots, affectedTree, cancelled); }
        catch (RuntimeException error) {
            android.util.Log.w("FolderScanCoordinator", "Source reconciliation deferred", error);
            requireReconciliation();
            return java.util.Collections.emptyList();
        }
    }

    private static final class ScanJob {
        final LibraryFolder folder;
        final int generation;
        final int batch;
        final FingerprintBudget fingerprintBudget = new FingerprintBudget();
        final android.os.CancellationSignal cancellation = new android.os.CancellationSignal();
        volatile boolean cancelled;
        volatile Future<?> future;
        ScanJob(LibraryFolder folder, int generation, int batch) {
            this.folder = folder; this.generation = generation; this.batch = batch;
        }
    }

    private void processScannedEntry(
            LibraryFolderScanner.Entry entry,
            long scanStarted,
            ScanCounts counts,
            int scanGeneration,
            FingerprintBudget fingerprintBudget) {
        if (destroyed || Thread.currentThread().isInterrupted()) return;
        if (!scanStillConfigured(entry, scanGeneration)) return;
        String identity = entry.sourceIdentity();
        LibraryDatabase.ScannedFile existing = database.scannedFile(identity);
        String documentUri = entry.uri.toString();
        ReadingProgress existingProgress = existing == null
                ? new ReadingProgress() : database.get(existing.canonicalUri);
        boolean providerUnchanged = existing != null &&
                providerFingerprintMatches(existing, entry) && !existingProgress.uri.isEmpty();

        FingerprintProbe probe = new FingerprintProbe();
        if (existing != null) probe.sample = existing.sampleSignature;
        boolean newSource = existing == null;
        boolean scannerOwnsCanonical = existing != null &&
                existing.canonicalUri.equals(existing.documentUri);
        boolean safeToMerge = !readerBusy.getAsBoolean();
        if (newSource || (scannerOwnsCanonical && safeToMerge &&
                !existingProgress.uri.isEmpty())) {
            ReadingProgress manualMatch = findExactManualDuplicate(
                    entry, documentUri, probe);
            if (manualMatch != null && scanStillConfigured(entry, scanGeneration)) {
                LibraryDatabase.DuplicateMergeResult merge = null;
                if (!newSource) {
                    String manualKey = manualMatch.uri;
                    java.util.concurrent.atomic.AtomicReference<LibraryDatabase.DuplicateMergeResult> published =
                            new java.util.concurrent.atomic.AtomicReference<>();
                    boolean merged = publishWhenIdle(entry, scanGeneration, () -> {
                        if (imports.isProcessing(manualKey) || imports.isProcessing(existing.canonicalUri)) return false;
                        published.set(database.mergeExactDuplicate(manualKey, existing.canonicalUri,
                                probe.sample, probe.fullFingerprint));
                        if (!published.get().merged) return false;
                        database.upsertScannedFile(scannedFile(entry, manualKey, probe.sample,
                                probe.fullFingerprint, scanStarted));
                        return true;
                    });
                    merge = published.get();
                    if (!merged) { counts.skipped++; counts.errors++; return; }
                }
                if (manualMatch != null) {
                    database.upsertScannedFile(scannedFile(
                            entry, manualMatch.uri, probe.sample,
                            probe.fullFingerprint, scanStarted));
                    database.setLibraryFingerprint(
                            manualMatch.uri, entry.size,
                            probe.sample, probe.fullFingerprint);
                    reapplyFolderSeries(manualMatch.uri, entry);
                    if (merge != null) {
                        for (String cover : merge.obsoleteCoverPaths) {
                            CoverStore.delete(context, cover);
                        }
                    }
                    counts.duplicates++;
                    return;
                }
            }
        }

        if (providerUnchanged) {
            database.touchScannedFile(
                    identity, entry.uri.toString(), entry.relativePath,
                    entry.size, entry.modified, scanStarted);
            if (existing.contentFingerprint.isEmpty() &&
                    fingerprintBudget.reserve(entry.size)) {
                retainFingerprint(entry, database.scannedFile(identity), scanGeneration);
            }
            updateRenamedTitle(existing, entry);
            reapplyFolderSeries(existing.canonicalUri, entry);
            counts.unchanged++;
            return;
        }

        if (readerBusy.getAsBoolean() && existing != null &&
                existing.canonicalUri.equals(existing.documentUri) &&
                !existing.documentUri.equals(documentUri)) {
            counts.skipped++;
            return;
        }
        if (existing != null && existing.canonicalUri.equals(existing.documentUri) &&
                !existing.documentUri.equals(documentUri) && !readerBusy.getAsBoolean()) {
            if (!publishWhenIdle(entry, scanGeneration, () ->
                    !imports.isProcessing(existing.canonicalUri) &&
                            database.relinkCanonicalUri(existing.canonicalUri, documentUri))) {
                counts.skipped++; counts.errors++; return;
            }
        }

        if (probe.sample.isEmpty()) probe.sample = imports.sampleContent(entry.uri, entry.size);

        if (existing == null && !probe.sample.isEmpty()) {
            if (!readerBusy.getAsBoolean() &&
                    reconnectMissingManual(entry, probe, scanStarted, scanGeneration)) {
                counts.updated++;
                return;
            }
            for (LibraryDatabase.ScannedFile candidate : database.duplicateCandidates(
                    identity, entry.size, probe.sample)) {
                if (database.get(candidate.canonicalUri).uri.isEmpty()) continue;
                try {
                    if (probe.fullFingerprint.isEmpty()) {
                        probe.fullFingerprint = ContentFingerprint.full(context, entry.uri);
                    }
                    String candidateFingerprint = ContentFingerprint.full(
                            context, Uri.parse(candidate.documentUri));
                    if (!candidate.contentFingerprint.isEmpty() &&
                            !candidate.contentFingerprint.equals(candidateFingerprint)) continue;
                    if (candidate.contentFingerprint.isEmpty()) {
                        database.setScannedFingerprint(
                                candidate.sourceIdentity, candidateFingerprint);
                    }
                    if (!probe.fullFingerprint.equals(candidateFingerprint)) continue;
                    if (!scanStillConfigured(entry, scanGeneration)) return;
                    database.upsertScannedFile(scannedFile(
                            entry, candidate.canonicalUri, probe.sample,
                            probe.fullFingerprint, scanStarted));
                    reapplyFolderSeries(candidate.canonicalUri, entry);
                    counts.duplicates++;
                    return;
                } catch (IOException | RuntimeException ignored) {
                    // A retained full hash can establish identity after the old URI disappears.
                    if (candidate.canonicalUri.equals(candidate.documentUri) &&
                            !candidate.contentFingerprint.isEmpty() &&
                            !readerBusy.getAsBoolean() &&
                            scanStillConfigured(entry, scanGeneration)) {
                        try {
                            if (probe.fullFingerprint.isEmpty()) {
                                probe.fullFingerprint = ContentFingerprint.full(context, entry.uri);
                            }
                            if (probe.fullFingerprint.equals(candidate.contentFingerprint) &&
                                    probe.sample.equals(imports.sampleContent(entry.uri, entry.size)) &&
                                    scanStillConfigured(entry, scanGeneration) &&
                                    !readerBusy.getAsBoolean() &&
                                    publishWhenIdle(entry, scanGeneration, () ->
                                            !imports.isProcessing(candidate.canonicalUri) &&
                                            database.reconnectByFingerprint(candidate.canonicalUri,
                                                    candidate, scannedFile(entry, documentUri,
                                                            probe.sample, probe.fullFingerprint,
                                                            scanStarted)))) {
                                reapplyFolderSeries(documentUri, entry);
                                counts.updated++;
                                return;
                            }
                        } catch (IOException | RuntimeException ignoredAgain) {
                            // A failed hash never authorizes a move.
                        }
                    }
                }
            }
        }

        boolean previouslyImported = existing != null || !database.get(documentUri).uri.isEmpty();
        if (!imports.processLibraryItem(entry.uri, false, entry, false)) {
            counts.skipped++;
            counts.errors++;
            return;
        }
        if (!scanStillConfigured(entry, scanGeneration)) {
            ReadingProgress current = database.get(documentUri);
            if (!current.uri.isEmpty()) {
                LibraryDatabase.ScannedFile stopped = scannedFile(entry, documentUri,
                        probe.sample, probe.fullFingerprint, scanStarted);
                stopped.available = false;
                database.upsertScannedFile(stopped);
                requireReconciliation();
                if (!previouslyImported) mainHandler.post(() -> {
                    if (destroyed) return;
                    ReadingProgress latest = database.get(documentUri);
                    if (!destroyed && !readerBusy.getAsBoolean() && latest.lastOpened == 0L &&
                            !latest.favorite && !latest.read && !latest.manualSource && !latest.titleOverride &&
                            latest.seriesOverride == LibraryDatabase.SERIES_AUTOMATIC &&
                            database.bookmarks(documentUri).isEmpty()) {
                        CoverStore.delete(context, database.forget(documentUri));
                    }
                });
            }
            return;
        }

        if (probe.fullFingerprint.isEmpty() && !probe.sample.isEmpty() &&
                fingerprintBudget.reserve(entry.size)) {
            try {
                probe.fullFingerprint = ContentFingerprint.full(context, entry.uri);
                if (!probe.sample.equals(imports.sampleContent(entry.uri, entry.size))) {
                    probe.fullFingerprint = "";
                }
            } catch (IOException | RuntimeException ignored) {
                probe.fullFingerprint = "";
            }
        }
        if (!scanStillConfigured(entry, scanGeneration)) return;
        database.upsertScannedFile(scannedFile(
                entry, documentUri, probe.sample, probe.fullFingerprint, scanStarted));
        if (!probe.fullFingerprint.isEmpty()) {
            LibraryDatabase.ScannedFile saved = database.scannedFile(identity);
            database.retainScannedFingerprint(saved, probe.sample, probe.fullFingerprint);
        }
        if (previouslyImported) counts.updated++;
        else counts.imported++;
    }

    private void reapplyFolderSeries(
            String canonicalUri, LibraryFolderScanner.Entry entry) {
        ReadingProgress current = database.get(canonicalUri);
        if (current.uri.isEmpty()) return;
        boolean embedded = current.detectedSeriesKey.startsWith("metadata:");
        // An exact copy found through another tree must not rename or reassign an established
        // folder series according to scan order. The stable original key survives source failover.
        if (!embedded && current.detectedSeriesKey.startsWith("folder:") &&
                !current.detectedSeriesKey.equals("folder:" + entry.seriesFolderKey)) return;
        database.applyDetectedSeries(
                canonicalUri,
                embedded ? current.detectedSeriesName : "",
                embedded ? current.detectedSeriesNumber : "",
                entry.seriesFolderKey,
                entry.seriesFolderName);
    }

    private void updateRenamedTitle(
            LibraryDatabase.ScannedFile existing, LibraryFolderScanner.Entry entry) {
        if (!existing.canonicalUri.equals(existing.documentUri)) return;
        if (existing.relativePath.equals(entry.relativePath)) return;
        ReadingProgress current = database.get(existing.canonicalUri);
        if (current.uri.isEmpty()) return;
        String oldFilename = existing.relativePath;
        int slash = oldFilename.lastIndexOf('/');
        if (slash >= 0) oldFilename = oldFilename.substring(slash + 1);
        String oldTitle = DocumentInfo.stripSupportedExtension(oldFilename);
        if (!current.title.equals(oldTitle)) return;
        database.updateTitle(
                existing.canonicalUri,
                DocumentInfo.stripSupportedExtension(entry.displayName));
    }

    private ReadingProgress findExactManualDuplicate(
            LibraryFolderScanner.Entry entry,
            String excludedCanonicalUri,
            FingerprintProbe probe) {
        if (imports.isProcessing(excludedCanonicalUri)) return null;
        List<ReadingProgress> candidates = database.manualDuplicateCandidates(
                entry.size, excludedCanonicalUri);
        if (candidates.isEmpty()) return null;
        // Re-read both sides for the confirmation attempt. Persisted hashes are useful hints, but
        // a document provider may replace bytes without the title having been reopened first.
        probe.sample = imports.sampleContent(entry.uri, entry.size);
        probe.fullFingerprint = "";
        if (probe.sample.isEmpty()) return null;

        for (ReadingProgress candidate : candidates) {
            if (candidate.uri.equals(entry.uri.toString()) ||
                    imports.isProcessing(candidate.uri)) continue;
            try {
                Uri candidateUri = Uri.parse(candidate.uri);
                String candidateSample = ContentFingerprint.sample(
                        context, candidateUri, candidate.documentSize);
                database.setLibraryFingerprint(
                        candidate.uri, candidate.documentSize, candidateSample, "");
                if (!probe.sample.equals(candidateSample)) continue;
                if (probe.fullFingerprint.isEmpty()) {
                    probe.fullFingerprint = ContentFingerprint.full(context, entry.uri);
                }
                String candidateFingerprint = ContentFingerprint.full(context, candidateUri);
                database.setLibraryFingerprint(
                        candidate.uri, candidate.documentSize,
                        candidateSample, candidateFingerprint);
                if (probe.fullFingerprint.equals(candidateFingerprint)) return candidate;
            } catch (IOException | RuntimeException ignored) {
                // A revoked manual grant is not evidence that two entries are identical.
            }
        }
        return null;
    }

    private boolean reconnectMissingManual(LibraryFolderScanner.Entry entry,
            FingerprintProbe probe, long scanStarted, int scanGeneration) {
        for (ReadingProgress candidate : database.manualDuplicateCandidates(
                entry.size, entry.uri.toString())) {
            if (candidate.contentFingerprint.isEmpty() ||
                    !probe.sample.equals(candidate.sampleSignature) ||
                    imports.isProcessing(candidate.uri) ||
                    !imports.sampleContent(Uri.parse(candidate.uri), candidate.documentSize)
                            .isEmpty()) continue;
            try {
                if (probe.fullFingerprint.isEmpty()) {
                    probe.fullFingerprint = ContentFingerprint.full(context, entry.uri);
                }
                if (!probe.fullFingerprint.equals(candidate.contentFingerprint) ||
                        !probe.sample.equals(imports.sampleContent(entry.uri, entry.size)) ||
                        !scanStillConfigured(entry, scanGeneration) ||
                        readerBusy.getAsBoolean()) continue;
                if (publishWhenIdle(entry, scanGeneration, () ->
                        !imports.isProcessing(candidate.uri) && database.reconnectByFingerprint(candidate.uri, null,
                                scannedFile(entry, entry.uri.toString(), probe.sample,
                                        probe.fullFingerprint, scanStarted)))) {
                    reapplyFolderSeries(entry.uri.toString(), entry);
                    return true;
                }
            } catch (IOException | RuntimeException ignored) {
                // Keep the old item if either content read or the database changed.
            }
        }
        return false;
    }

    /** Serialize URI transfers with reader activation; never hold a DB transaction while waiting. */
    private boolean publishWhenIdle(LibraryFolderScanner.Entry entry, int generation,
            BooleanSupplier publication) {
        AtomicBoolean pending = new AtomicBoolean(true);
        AtomicBoolean result = new AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<RuntimeException> failure = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch published = new java.util.concurrent.CountDownLatch(1);
        mainHandler.post(() -> {
            try {
                if (pending.compareAndSet(true, false) && scanStillConfigured(entry, generation) &&
                        !readerBusy.getAsBoolean()) result.set(publication.getAsBoolean());
            } catch (RuntimeException error) { failure.set(error); }
            finally { published.countDown(); }
        });
        try {
            while (!published.await(50, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                if (!scanStillConfigured(entry, generation)) { pending.set(false); return false; }
            }
        } catch (InterruptedException interrupted) {
            pending.set(false); Thread.currentThread().interrupt(); return false;
        }
        if (failure.get() != null) throw failure.get();
        return result.get();
    }

    private void retainFingerprint(LibraryFolderScanner.Entry entry,
            LibraryDatabase.ScannedFile source, int scanGeneration) {
        String before = imports.sampleContent(entry.uri, entry.size);
        if (before.isEmpty() || (!source.sampleSignature.isEmpty() &&
                !source.sampleSignature.equals(before))) return;
        try {
            String full = ContentFingerprint.full(context, entry.uri);
            if (!scanStillConfigured(entry, scanGeneration) ||
                    !before.equals(imports.sampleContent(entry.uri, entry.size))) return;
            database.retainScannedFingerprint(source, before, full);
        } catch (IOException | RuntimeException ignored) {
            // Future scans can retry while the source is still accessible.
        }
    }

    private static LibraryDatabase.ScannedFile scannedFile(
            LibraryFolderScanner.Entry entry,
            String canonicalUri,
            String sample,
            String fingerprint,
            long seenAt) {
        LibraryDatabase.ScannedFile result = new LibraryDatabase.ScannedFile();
        result.sourceIdentity = entry.sourceIdentity();
        result.treeUri = entry.treeUri;
        result.documentId = entry.documentId;
        result.documentUri = entry.uri.toString();
        result.relativePath = entry.relativePath;
        result.canonicalUri = canonicalUri;
        result.documentSize = entry.size;
        result.documentModified = entry.modified;
        result.sampleSignature = sample;
        result.contentFingerprint = fingerprint;
        result.lastSeen = seenAt;
        result.available = true;
        return result;
    }

    private static boolean providerFingerprintMatches(
            LibraryDatabase.ScannedFile existing, LibraryFolderScanner.Entry entry) {
        boolean sizeChanged = existing.documentSize >= 0L && entry.size >= 0L &&
                existing.documentSize != entry.size;
        boolean modifiedChanged = existing.documentModified >= 0L && entry.modified >= 0L &&
                existing.documentModified != entry.modified;
        return !sizeChanged && !modifiedChanged;
    }

    private boolean scanStillConfigured(
            LibraryFolderScanner.Entry entry, int scanGeneration) {
        ScanJob job = jobs.get(entry.treeUri);
        return job != null && job.generation == scanGeneration && current(job);
    }

    private LibraryScanResult folderScanResult(
            ScanCounts counts, LibraryFolderScanner.Summary traversal) {
        int providerErrors = traversal == null ? 1 : traversal.providerErrors;
        boolean incomplete = counts.errors + providerErrors > 0 ||
                (traversal != null && traversal.bounded);
        return new LibraryScanResult(
                counts.imported, counts.updated, counts.unchanged,
                counts.duplicates, counts.skipped, incomplete);
    }

    private String folderScanSummary(LibraryScanResult result) {
        if (!result.shouldShow()) return "";
        ArrayList<String> parts = new ArrayList<>();
        if (result.incomplete) {
            parts.add(context.getString(R.string.library_scan_incomplete_short));
        }
        if (result.added > 0) {
            parts.add(context.getString(R.string.library_scan_added, result.added));
        }
        if (result.updated > 0) {
            parts.add(context.getString(R.string.library_scan_updated, result.updated));
        }
        if (result.duplicates > 0) {
            parts.add(context.getString(R.string.library_scan_duplicates, result.duplicates));
        }
        if (result.skipped > 0) {
            parts.add(context.getString(R.string.library_scan_skipped, result.skipped));
        }
        return String.join(" · ", parts);
    }

    private static final class ScanCounts {
        private int imported;
        private int updated;
        private int unchanged;
        private int duplicates;
        private int skipped;
        private int errors;
    }

    private static final class FingerprintProbe {
        private String sample = "";
        private String fullFingerprint = "";
    }

    /** Spread legacy backfills across scans instead of rereading an entire library at once. */
    private static final class FingerprintBudget {
        private static final long MAX_BYTES = 128L * 1024L * 1024L;
        private int remainingFiles = 4;
        private long remainingBytes = MAX_BYTES;

        private boolean reserve(long size) {
            if (remainingFiles <= 0 || size < 0 || size > remainingBytes) return false;
            remainingFiles--;
            remainingBytes -= size;
            return true;
        }
    }
}
