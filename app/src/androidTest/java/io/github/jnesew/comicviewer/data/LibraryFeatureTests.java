package io.github.jnesew.comicviewer.data;

import android.app.Instrumentation;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import io.github.jnesew.comicviewer.library.FolderScanCoordinator;
import io.github.jnesew.comicviewer.library.LibraryImportCoordinator;
import io.github.jnesew.comicviewer.library.LibraryQueryController;
import io.github.jnesew.comicviewer.model.LibraryFolder;
import io.github.jnesew.comicviewer.model.ReadingProgress;
import io.github.jnesew.comicviewer.render.BufferingPolicy;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Production SQLite, document-provider traversal and worker publication, without runtime libraries. */
final class LibraryFeatureTests {
    private static final String PREFS = "multiple-folder-instrumentation";
    private final Instrumentation instrumentation;
    private final Context context;
    private final LibraryDatabase db;

    LibraryFeatureTests(Instrumentation instrumentation, LibraryDatabase db) {
        this.instrumentation = instrumentation; this.context = instrumentation.getTargetContext(); this.db = db;
    }
    private static Uri tree(String id) {
        return DocumentsContract.buildTreeDocumentUri(FixtureDocumentsProvider.AUTHORITY, id);
    }
    private static Uri manual() {
        return DocumentsContract.buildDocumentUri(FixtureDocumentsProvider.AUTHORITY, "m-book");
    }
    private ReaderPreferences preferences() { return new ReaderPreferences(context, PREFS, BufferingPolicy.STANDARD); }
    private void main(Runnable action) { instrumentation.runOnMainSync(action); }
    private Bundle fixture(String method, Bundle options) {
        return context.getContentResolver().call(Uri.parse("content://" + FixtureDocumentsProvider.AUTHORITY),
                "fixture-" + method, null, options);
    }
    private void options(String... enabled) {
        Bundle options = new Bundle(); for (String key : enabled) options.putBoolean(key, true);
        fixture("options", options);
    }

    void preferencesMigration() {
        context.deleteSharedPreferences(PREFS);
        try {
            var old = context.getSharedPreferences(PREFS, 0);
            old.edit().putString("library_folder_uri", tree("a").toString())
                    .putString("library_folder_label", "Existing library").commit();
            ReaderPreferences prefs = preferences();
            equal(1, prefs.libraryFolders().size());
            equal("Existing library", prefs.libraryFolders().get(0).label);
            check(!old.contains("library_folder_uri"));
            prefs.addLibraryFolder(new LibraryFolder(tree("a").buildUpon().appendQueryParameter("alias", "1")
                    .build().toString(), "Renamed", "Provider"));
            equal(1, prefs.libraryFolders().size()); equal(tree("a").toString(), prefs.libraryFolders().get(0).uri);
            prefs.addLibraryFolder(new LibraryFolder(tree("b").toString(), "Other", "Provider"));
            equal(2, preferences().libraryFolders().size());
            prefs.removeLibraryFolder(tree("a").toString());
            equal(List.of(tree("b").toString()), preferences().libraryFolderUris());
            check(preferences().pendingFolderReleases().contains(tree("a").toString()));
            prefs.removeLibraryFolder(tree("b").toString());
            equal(0, preferences().libraryFolders().size());
            // An explicitly empty collection must never resurrect stale legacy preferences.
            old.edit().putString("library_folder_uri", tree("a").toString()).commit();
            equal(0, preferences().libraryFolders().size());
            context.deleteSharedPreferences(PREFS);
            equal(0, preferences().libraryFolders().size());
        } finally { context.deleteSharedPreferences(PREFS); }
    }

    void seriesFavorites() {
        db.ensureImported("one", "Issue One", 1, 1); db.ensureImported("two", "Issue Two", 1, 1);
        db.ensureImported("standalone", "Standalone", 1, 1);
        db.applyDetectedSeries("one", "", "", "folder-key", "Original");
        db.applyDetectedSeries("two", "", "", "folder-key", "Original");
        long series = db.get("one").seriesId;
        db.toggleFavorite("two"); db.toggleFavorite("standalone");
        LibraryQueryController queries = new LibraryQueryController(db);
        equal(1, queries.query("", LibraryDatabase.SORT_TITLE_ASC, LibraryDatabase.FILTER_FAVORITES, 0, true)
                .seriesRows().size()); // A favorite issue never implicitly stars its series.
        check(db.toggleSeriesFavorite(series));
        equal(2, queries.query("", LibraryDatabase.SORT_TITLE_ASC, LibraryDatabase.FILTER_FAVORITES, 0, true)
                .seriesRows().size());
        equal(2, queries.query("", LibraryDatabase.SORT_TITLE_ASC, LibraryDatabase.FILTER_ALL, series, true)
                .rows().size());
        equal(1, queries.query("", LibraryDatabase.SORT_TITLE_ASC, LibraryDatabase.FILTER_FAVORITES, series, true)
                .rows().size()); // An explicitly chosen issue filter is still honored inside the series.
        equal(2, queries.query("", LibraryDatabase.SORT_TITLE_ASC, LibraryDatabase.FILTER_FAVORITES, 0, false)
                .rows().size());
        db.applyDetectedSeries("one", "", "", "folder-key", "Renamed");
        db.ensureImported("three", "New issue", 1, 1);
        db.applyDetectedSeries("three", "", "", "folder-key", "Renamed");
        equal(series, db.get("three").seriesId); check(db.get("three").seriesFavorite);
        equal(3, queries.query("", LibraryDatabase.SORT_TITLE_ASC, LibraryDatabase.FILTER_ALL, series, true)
                .rows().size());
        check(!db.get("one").favorite && db.get("two").favorite && !db.get("three").favorite);
        check(!db.toggleSeriesFavorite(series)); check(db.get("two").favorite);
    }

    void upgradeTen() {
        String name = "version-ten-instrumentation.sqlite3";
        context.deleteDatabase(name);
        try {
            try (LibraryDatabase seed = new LibraryDatabase(context, name)) {
                seed.ensureImported("legacy", "Original", 50, 100); seed.markManualSource("legacy");
                seed.setComicMetadata("legacy", "Edited", LibraryDatabase.SERIES_MANUAL, "Series", "2");
                seed.toggleFavorite("legacy"); seed.toggleBookmark("legacy", 1); seed.setReadStatus("legacy", true);
                ReadingProgress progress = seed.get("legacy"); progress.page = 1; progress.pageCount = 3;
                progress.lastOpened = 123; seed.saveReadingProgress(progress);
                SQLiteDatabase sql = seed.getWritableDatabase();
                sql.execSQL("DROP TABLE manual_sources");
                sql.execSQL("ALTER TABLE series RENAME TO series_eleven");
                sql.execSQL("CREATE TABLE series (id INTEGER PRIMARY KEY AUTOINCREMENT," +
                        "stable_key TEXT NOT NULL UNIQUE,name TEXT NOT NULL,origin TEXT NOT NULL," +
                        "updated_at INTEGER NOT NULL DEFAULT 0)");
                sql.execSQL("INSERT INTO series SELECT id,stable_key,name,origin,updated_at FROM series_eleven");
                sql.execSQL("DROP TABLE series_eleven");
                sql.setVersion(10);
            }
            try (LibraryDatabase upgraded = new LibraryDatabase(context, name)) {
                ReadingProgress progress = upgraded.get("legacy");
                equal(11, upgraded.getReadableDatabase().getVersion());
                equal("Edited", progress.title); equal("Series", progress.seriesTitle);
                equal(1, progress.page); equal(123L, progress.lastOpened);
                check(progress.favorite && progress.read && !progress.seriesFavorite);
                check(upgraded.isBookmarked("legacy", 1)); equal(List.of("legacy"), upgraded.manualSources("legacy"));
                check(upgraded.toggleSeriesFavorite(progress.seriesId));
            }
        } finally { context.deleteDatabase(name); }
    }

    void sourceAvailability() {
        db.ensureImported("canonical", "Comic", 1, 1);
        LibraryDatabase.ScannedFile a = source("a", "a-doc", "canonical", 100);
        LibraryDatabase.ScannedFile b = source("b", "b-doc", "canonical", 5);
        db.upsertScannedFile(a); db.upsertScannedFile(b);
        db.finishFolderScan("a", 100);
        check(db.get("canonical").available); equal("canonical", db.scannedFile(b.sourceIdentity).canonicalUri);
        db.markFolderUnavailable("a"); check(db.get("canonical").available);
        equal(0, db.confirmedMissingItems(List.of("a", "b")).size());
        db.finishFolderScan("b", 20); check(!db.get("canonical").available);
        // A lost grant is uncertainty even after the other root completes a negative scan.
        equal(0, db.confirmedMissingItems(List.of("a", "b")).size());
        db.finishFolderScan("a", 200);
        equal(1, db.confirmedMissingItems(List.of("a", "b")).size());
        db.markManualSource("canonical"); equal(0, db.confirmedMissingItems(List.of("a", "b")).size());
    }
    private static LibraryDatabase.ScannedFile source(String tree, String uri, String canonical, long seen) {
        LibraryDatabase.ScannedFile source = new LibraryDatabase.ScannedFile();
        source.treeUri = tree; source.documentId = uri; source.documentUri = uri;
        source.sourceIdentity = tree + "\n" + uri; source.canonicalUri = canonical; source.lastSeen = seen;
        return source;
    }

    private final class Scanning implements AutoCloseable {
        final ReaderPreferences prefs;
        final AtomicBoolean busy = new AtomicBoolean();
        LibraryImportCoordinator imports;
        FolderScanCoordinator scans;
        Scanning() {
            context.deleteSharedPreferences(PREFS); prefs = preferences();
            android.content.Intent grant = new android.content.Intent();
            grant.setClassName(instrumentation.getContext().getPackageName(), FixtureGrantActivity.class.getName());
            grant.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(grant);
            long end = System.currentTimeMillis() + 5_000;
            while (true) {
                try {
                    for (String id : new String[]{"a", "b", "a-series"}) {
                        context.getContentResolver().takePersistableUriPermission(tree(id),
                                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    }
                    context.getContentResolver().takePersistableUriPermission(manual(),
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    break;
                } catch (SecurityException pendingGrant) {
                    if (System.currentTimeMillis() >= end) throw pendingGrant;
                    try { Thread.sleep(30); } catch (InterruptedException error) { throw new AssertionError(error); }
                }
            }
            fixture("reset", null);
            main(() -> {
                imports = new LibraryImportCoordinator(context, db, null);
                scans = new FolderScanCoordinator(context, db, prefs, imports, busy::get,
                        new FolderScanCoordinator.Listener() {
                            @Override public void onLibraryChanged() { }
                            @Override public void onScanStatus(String detail, boolean scanning, boolean persistent) { }
                        });
            });
        }
        void add(String id) { main(() -> scans.selectFolder(tree(id))); waitIdle(); }
        void remove(String id) { main(() -> scans.removeFolder(tree(id).toString())); waitIdle(); }
        void rescan() { main(() -> scans.maybeScanLibraryFolder(true)); waitIdle(); }
        void waitIdle() {
            long end = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < end) {
                AtomicBoolean running = new AtomicBoolean(); main(() -> running.set(scans.isRunning()));
                if (!running.get()) return;
                try { Thread.sleep(30); } catch (InterruptedException error) { throw new AssertionError(error); }
            }
            throw new AssertionError("Folder worker did not become idle");
        }
        @Override public void close() {
            main(() -> { scans.close(); imports.close(); });
            try { scans.awaitStopped(); imports.awaitStopped(); }
            catch (InterruptedException error) { throw new AssertionError(error); }
            for (String id : new String[]{"a", "b", "a-series"}) {
                try { context.getContentResolver().releasePersistableUriPermission(tree(id),
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                catch (SecurityException alreadyReleased) { }
            }
            context.getContentResolver().releasePersistableUriPermission(manual(),
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.deleteSharedPreferences(PREFS);
        }
    }
    private List<ReadingProgress> all() { return db.library("", LibraryDatabase.SORT_TITLE_ASC, LibraryDatabase.FILTER_ALL); }

    void sharedCopies(boolean reverse) {
        try (Scanning flow = new Scanning()) {
            String first = reverse ? "b" : "a", second = reverse ? "a" : "b";
            flow.add(first); equal(1, all().size());
            String old = all().get(0).uri;
            db.setComicMetadata(old, "My title", LibraryDatabase.SERIES_MANUAL, "My series", "7");
            db.toggleFavorite(old); db.toggleBookmark(old, 1); db.setReadStatus(old, true);
            db.setReadingMode(old, "continuous", true); db.setReadingDirection(old, "rtl", true);
            db.replacePageIndex(old, List.of(
                    new io.github.jnesew.comicviewer.model.PageInfo("page0.png", 80, 120),
                    new io.github.jnesew.comicviewer.model.PageInfo("page1.png", 80, 120),
                    new io.github.jnesew.comicviewer.model.PageInfo("page2.png", 80, 120)));
            ReadingProgress progress = db.get(old); progress.page = 1; progress.lastOpened = 123;
            db.saveReadingProgress(progress); db.toggleSeriesFavorite(progress.seriesId);
            flow.add(second); flow.rescan(); equal(1, all().size());
            equal(2, db.sourceCopies(old).size()); equal(old, all().get(0).uri);
            flow.remove(first); equal(1, all().size());
            progress = all().get(0); check(progress.available && !progress.uri.equals(old));
            equal("My title", progress.title); equal("My series", progress.seriesTitle);
            equal(1, progress.page); equal(123L, progress.lastOpened);
            check(progress.favorite && progress.read && progress.seriesFavorite);
            check(progress.readingModeOverride && progress.readingDirectionOverride);
            check(db.isBookmarked(progress.uri, 1)); check(!db.pageIndex(progress.uri).isEmpty());
            try (var stream = context.getContentResolver().openInputStream(Uri.parse(progress.uri))) {
                check(stream != null && stream.read() >= 0);
            } catch (java.io.IOException error) { throw new AssertionError("Retained copy did not open", error); }
            flow.remove(second); check(!all().get(0).available); check(all().get(0).favorite);
            equal(0, db.confirmedMissingItems(flow.prefs.libraryFolderUris()).size());
        }
    }

    void failuresAndBusyRemoval() {
        try (Scanning flow = new Scanning()) {
            flow.add("a"); flow.add("b"); String first = all().get(0).uri;
            options("denyA"); flow.rescan();
            check(all().get(0).available && !all().get(0).uri.equals(first));
            equal(0, db.confirmedMissingItems(flow.prefs.libraryFolderUris()).size());
            options(); flow.rescan();
            flow.busy.set(true); String current = all().get(0).uri;
            flow.remove("b"); equal(current, all().get(0).uri);
            check(flow.prefs.pendingFolderReleases().contains(tree("b").toString()));
            flow.busy.set(false); flow.rescan();
            check(all().get(0).available); check(!all().get(0).uri.equals(current));
            check(flow.prefs.pendingFolderReleases().isEmpty());
        }
    }

    void conservativeAndPartial() {
        try (Scanning flow = new Scanning()) {
            options("distinct"); flow.add("a"); flow.add("b"); equal(2, all().size());
            check(all().get(0).seriesId != all().get(1).seriesId);
            options("distinct", "partialA"); flow.rescan();
            check(all().stream().allMatch(item -> item.available));
            equal(0, db.confirmedMissingItems(flow.prefs.libraryFolderUris()).size());
            options("distinct", "hideA"); flow.rescan();
            equal(1, db.confirmedMissingItems(flow.prefs.libraryFolderUris()).size());
            check(all().stream().anyMatch(item -> item.available));
        }
    }

    void overlapAndCancellation() {
        try (Scanning flow = new Scanning()) {
            LibraryFolderScanner.DirectoryCache cache = new LibraryFolderScanner.DirectoryCache();
            try {
                var parent = LibraryFolderScanner.scan(context, tree("a"), () -> false, entry -> { }, cache);
                var child = LibraryFolderScanner.scan(context, tree("a-series"), () -> false, entry -> { }, cache);
                check(parent.complete() && child.complete()); equal(1, child.cachedDirectories);
                equal(1, fixture("stats", null).getInt("aListings"));
            } catch (java.io.IOException error) { throw new AssertionError(error); }
            flow.add("a-series");
            flow.add("a"); equal(1, all().size());
            // Selecting a parent can enrich a standalone entry, then repeated scans keep that series.
            check(all().get(0).seriesId > 0); long enriched = all().get(0).seriesId;
            flow.rescan(); equal(enriched, all().get(0).seriesId);
            options("blockA");
            main(() -> scansStart(flow));
            flow.remove("a"); flow.add("b");
            equal(1, all().size()); check(all().get(0).available);
            check(flow.prefs.libraryFolderUris().contains(tree("b").toString()));
            check(!flow.prefs.libraryFolderUris().contains(tree("a").toString()));
        }
    }

    void independentManualCopy() {
        try (Scanning flow = new Scanning()) {
            main(() -> flow.imports.importComics(List.of(manual())));
            long end = System.currentTimeMillis() + 10_000;
            while (db.get(manual().toString()).contentFingerprint.isEmpty() ||
                    flow.imports.isProcessing(manual().toString())) {
                if (System.currentTimeMillis() >= end) throw new AssertionError("Manual import timed out");
                try { Thread.sleep(30); } catch (InterruptedException error) { throw new AssertionError(error); }
            }
            db.toggleFavorite(manual().toString()); db.toggleBookmark(manual().toString(), 1);
            flow.add("a"); equal(1, all().size()); equal(manual().toString(), all().get(0).uri);
            flow.remove("a"); check(all().get(0).available);
            equal(manual().toString(), all().get(0).uri); check(all().get(0).favorite);
            check(db.isBookmarked(manual().toString(), 1));
        }
    }

    private void awaitProviderListing() {
        long end = System.currentTimeMillis() + 5_000;
        while (fixture("stats", null).getInt("aListings") == 0) {
            if (System.currentTimeMillis() >= end) throw new AssertionError("Provider scan did not start");
            try { Thread.sleep(10); } catch (InterruptedException error) { throw new AssertionError(error); }
        }
    }

    void removeDuringTraversal() {
        try (Scanning flow = new Scanning()) {
            options("distinct", "blockA"); main(() -> flow.scans.selectFolder(tree("a")));
            awaitProviderListing();
            main(() -> { flow.scans.removeFolder(tree("a").toString()); flow.scans.selectFolder(tree("b")); });
            flow.waitIdle(); equal(1, all().size());
            check(all().get(0).uri.contains("b-book") && all().get(0).available);
            equal(List.of(tree("b").toString()), flow.prefs.libraryFolderUris());
        }
    }

    void restartDuringTraversal() {
        try (Scanning flow = new Scanning()) {
            options("blockA"); main(() -> flow.scans.selectFolder(tree("a")));
            awaitProviderListing(); main(() -> flow.scans.close());
            try { flow.scans.awaitStopped(); } catch (InterruptedException error) { throw new AssertionError(error); }
            options();
            main(() -> {
                flow.scans = new FolderScanCoordinator(context, db, preferences(), flow.imports, flow.busy::get, null);
                flow.scans.maybeScanLibraryFolder(true);
            });
            flow.waitIdle(); equal(1, all().size()); check(all().get(0).available);
            int before = fixture("stats", null).getInt("aListings");
            main(() -> { flow.scans.maybeScanLibraryFolder(true); flow.scans.maybeScanLibraryFolder(true); });
            flow.waitIdle(); equal(before + 1, fixture("stats", null).getInt("aListings"));
            equal(1, all().size()); equal(1, flow.prefs.libraryFolders().size());
        }
    }
    private void scansStart(Scanning flow) { flow.scans.maybeScanLibraryFolder(true); }

    private static void check(boolean value) { if (!value) throw new AssertionError("Condition failed"); }
    private static void equal(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw new AssertionError(expected + " != " + actual);
    }
}
