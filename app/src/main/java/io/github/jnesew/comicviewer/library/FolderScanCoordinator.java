package io.github.jnesew.comicviewer.library;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import io.github.jnesew.comicviewer.data.CoverStore;
import io.github.jnesew.comicviewer.data.ContentFingerprint;
import io.github.jnesew.comicviewer.data.LibraryDatabase;
import io.github.jnesew.comicviewer.data.LibraryFolderScanner;
import io.github.jnesew.comicviewer.data.ReaderPreferences;
import io.github.jnesew.comicviewer.document.ComicDocumentFactory;
import io.github.jnesew.comicviewer.document.DocumentInfo;
import io.github.jnesew.comicviewer.model.ReadingProgress;
import io.github.jnesew.comicviewer.util.LibraryFolderLabel;
import io.github.jnesew.comicviewer.util.LibraryScanResult;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import android.content.Context;
import io.github.jnesew.comicviewer.R;
import java.util.function.BooleanSupplier;

/** SAF scan scheduling/reconciliation. A cancelled or inaccessible tree is not proof of deletion. */
public final class FolderScanCoordinator implements AutoCloseable {
    public interface Listener {
        void onLibraryChanged();
        void onScanStatus(String detail, boolean scanning, boolean persistent);
    }
    private static final long AUTO_SCAN_COOLDOWN_MS = 5_000L;
    private final Context context;
    private final LibraryDatabase database;
    private final ReaderPreferences preferences;
    private final LibraryImportCoordinator imports;
    private final BooleanSupplier readerBusy;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean folderScanRunning = new AtomicBoolean();
    private volatile boolean destroyed;
    private volatile long lastFolderScanStarted;
    private volatile String pendingReleaseFolderUri = "";
    private volatile Future<?> folderScanTask;
    private volatile int folderScanGeneration;
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
    }
    public boolean isRunning() { return folderScanRunning.get(); }
    /** The Activity has already persisted the newly selected grant. */
    public void selectFolder(Uri selected) {
        String previous = preferences.libraryFolderUri();
        folderScanGeneration++;
        Future<?> activeScan = folderScanTask;
        if (activeScan != null) activeScan.cancel(true);
        folderScanRunning.set(false);
        DocumentInfo folder = ComicDocumentFactory.describe(context, selected);
        preferences.setLibraryFolder(selected.toString(), LibraryFolderLabel.compact(folder.displayName));
        if (!previous.isEmpty() && !previous.equals(selected.toString())) pendingReleaseFolderUri = previous;
        updateLibraryFolderUi("", false, false);
        lastFolderScanStarted = 0L;
        maybeScanLibraryFolder(true);
    }
    private void updateLibraryFolderUi(String detail, boolean scanning, boolean persistent) {
        if (!destroyed && listener != null) listener.onScanStatus(detail, scanning, persistent);
    }
    private void notifyChanged() {
        if (!destroyed && listener != null) listener.onLibraryChanged();
    }
    private void releaseReadAccess(Uri uri) {
        try {
            context.getContentResolver().releasePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (RuntimeException ignored) {
            // Some providers grant temporary access only.
        }
    }
    @Override public void close() {
        destroyed = true;
        folderScanGeneration++;
        listener = null;
        mainHandler.removeCallbacksAndMessages(null);
        scanWorker.shutdownNow();
    }
    public void awaitStopped() throws InterruptedException {
        scanWorker.awaitTermination(Long.MAX_VALUE, java.util.concurrent.TimeUnit.NANOSECONDS);
    }
    public void stopLibraryFolder() {
        String configured = preferences.libraryFolderUri();
        preferences.clearLibraryFolder();
        folderScanGeneration++;
        Future<?> activeScan = folderScanTask;
        if (activeScan != null) activeScan.cancel(true);
        folderScanRunning.set(false);
        if (!configured.isEmpty()) {
            database.markFolderUnavailable(configured);
            releaseReadAccess(Uri.parse(configured));
        }
        String pendingRelease = pendingReleaseFolderUri;
        if (!pendingRelease.isEmpty() && !pendingRelease.equals(configured)) {
            database.markFolderUnavailable(pendingRelease);
            releaseReadAccess(Uri.parse(pendingRelease));
        }
        pendingReleaseFolderUri = "";
        updateLibraryFolderUi("", false, false);
        notifyChanged();
    }

    public void maybeScanLibraryFolder(boolean userRequested) {
        if (destroyed || preferences == null || database == null) return;
        String configured = preferences.libraryFolderUri();
        if (configured.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (!userRequested && now - lastFolderScanStarted < AUTO_SCAN_COOLDOWN_MS) return;
        if (!folderScanRunning.compareAndSet(false, true)) return;
        int scanGeneration = ++folderScanGeneration;
        lastFolderScanStarted = now;
        updateLibraryFolderUi("", true, true);
        Uri treeUri;
        try {
            treeUri = Uri.parse(configured);
        } catch (RuntimeException exception) {
            if (scanGeneration == folderScanGeneration) folderScanRunning.set(false);
            updateLibraryFolderUi(
                    context.getString(R.string.library_scan_incomplete_short), false, true);
            return;
        }

        folderScanTask = scanWorker.submit(() -> {
            ScanCounts counts = new ScanCounts();
            long scanStarted = System.currentTimeMillis();
            LibraryFolderScanner.Summary traversal = null;
            try {
                traversal = LibraryFolderScanner.scan(
                        context, treeUri,
                        () -> destroyed || Thread.currentThread().isInterrupted() ||
                                scanGeneration != folderScanGeneration ||
                                !preferences.libraryFolderUri().equals(treeUri.toString()),
                        entry -> processScannedEntry(
                                entry, scanStarted, counts, scanGeneration));
                if (!destroyed && scanGeneration == folderScanGeneration && traversal.complete() && !readerBusy.getAsBoolean() &&
                        preferences.libraryFolderUri().equals(treeUri.toString())) {
                    database.finishFolderScan(treeUri.toString(), scanStarted);
                    String pendingRelease = pendingReleaseFolderUri;
                    if (!pendingRelease.isEmpty() &&
                            !pendingRelease.equals(treeUri.toString())) {
                        database.markFolderUnavailable(pendingRelease);
                        releaseReadAccess(Uri.parse(pendingRelease));
                        pendingReleaseFolderUri = "";
                    }
                }
            } catch (IOException | RuntimeException error) {
                counts.errors++;
            } finally {
                if (scanGeneration == folderScanGeneration) folderScanRunning.set(false);
            }
            LibraryFolderScanner.Summary finished = traversal;
            mainHandler.post(() -> {
                if (destroyed || scanGeneration != folderScanGeneration) return;
                notifyChanged();
                LibraryScanResult result = folderScanResult(counts, finished);
                String summary = folderScanSummary(result);
                updateLibraryFolderUi(summary, false, result.shouldPersist());
                if (!preferences.libraryFolderUri().equals(treeUri.toString())) {
                    lastFolderScanStarted = 0L;
                    maybeScanLibraryFolder(false);
                }
            });
        });
    }

    private void processScannedEntry(
            LibraryFolderScanner.Entry entry,
            long scanStarted,
            ScanCounts counts,
            int scanGeneration) {
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
                    merge = database.mergeExactDuplicate(
                            manualMatch.uri, existing.canonicalUri,
                            probe.sample, probe.fullFingerprint);
                    if (!merge.merged) manualMatch = null;
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
            database.relinkCanonicalUri(existing.canonicalUri, documentUri);
        }

        if (probe.sample.isEmpty()) probe.sample = imports.sampleContent(entry.uri, entry.size);

        if (existing == null && !probe.sample.isEmpty()) {
            for (LibraryDatabase.ScannedFile candidate : database.duplicateCandidates(
                    identity, entry.size, probe.sample)) {
                if (database.get(candidate.canonicalUri).uri.isEmpty()) continue;
                try {
                    if (probe.fullFingerprint.isEmpty()) {
                        probe.fullFingerprint = ContentFingerprint.full(context, entry.uri);
                    }
                    String candidateFingerprint = candidate.contentFingerprint;
                    if (candidateFingerprint.isEmpty()) {
                        candidateFingerprint = ContentFingerprint.full(
                                context, Uri.parse(candidate.documentUri));
                        database.setScannedFingerprint(
                                candidate.sourceIdentity, candidateFingerprint);
                    }
                    if (!probe.fullFingerprint.equals(candidateFingerprint)) continue;
                    if (!scanStillConfigured(entry, scanGeneration)) return;
                    database.upsertScannedFile(scannedFile(
                            entry, candidate.canonicalUri, probe.sample,
                            probe.fullFingerprint, scanStarted));
                    counts.duplicates++;
                    return;
                } catch (IOException | RuntimeException ignored) {
                    // An inaccessible candidate is not sufficient evidence to suppress context item.
                }
            }
        }

        boolean previouslyImported = existing != null || !database.get(documentUri).uri.isEmpty();
        if (!imports.processLibraryItem(entry.uri, false, entry, false)) {
            counts.skipped++;
            return;
        }
        if (!scanStillConfigured(entry, scanGeneration)) {
            if (!previouslyImported) {
                String cover = database.forget(documentUri);
                CoverStore.delete(context, cover);
            }
            return;
        }
        database.upsertScannedFile(scannedFile(
                entry, documentUri, probe.sample, probe.fullFingerprint, scanStarted));
        if (previouslyImported) counts.updated++;
        else counts.imported++;
    }

    private void reapplyFolderSeries(
            String canonicalUri, LibraryFolderScanner.Entry entry) {
        ReadingProgress current = database.get(canonicalUri);
        if (current.uri.isEmpty()) return;
        boolean embedded = current.detectedSeriesKey.startsWith("metadata:");
        database.applyDetectedSeries(
                canonicalUri,
                embedded ? current.detectedSeriesName : "",
                embedded ? current.detectedSeriesNumber : "",
                entry.seriesFolderKey,
                entry.seriesFolderName);
    }

    private void updateRenamedTitle(
            LibraryDatabase.ScannedFile existing, LibraryFolderScanner.Entry entry) {
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
        return !destroyed && !Thread.currentThread().isInterrupted() &&
                scanGeneration == folderScanGeneration &&
                preferences.libraryFolderUri().equals(entry.treeUri);
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
}
