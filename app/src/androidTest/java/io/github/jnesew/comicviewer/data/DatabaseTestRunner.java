package io.github.jnesew.comicviewer.data;

import android.app.Activity;
import android.app.Instrumentation;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.util.Log;
import io.github.jnesew.comicviewer.model.PageInfo;
import io.github.jnesew.comicviewer.model.ReadingProgress;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Platform-only instrumentation: exercises the production helper and real Android SQLite. */
public final class DatabaseTestRunner extends Instrumentation {
    private static final String NAME = "refactor-instrumentation.sqlite3";
    private LibraryDatabase db;
    private int current;
    private int failed;
    private static final int TOTAL = 7;

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        run("unicodeMetadataAndReadingState", this::metadata);
        run("indexReplacementRollsBack", this::indexRollback);
        run("canonicalRelinkMovesDependentRows", this::relink);
        run("exactDuplicateMergePreservesUserChoices", this::merge);
        run("lostAccessIsNotConfirmedMissing", this::missing);
        run("forgetRollsBackAndNeverDeletesSourceFile", this::forget);
        run("upgradeVersionOneToEight", this::upgrade);
        Bundle result = new Bundle();
        result.putString("stream", "\n" + (TOTAL - failed) + "/" + TOTAL + " database tests passed\n");
        finish(Activity.RESULT_OK, result);
    }

    private void run(String name, Runnable test) {
        Bundle status = new Bundle();
        status.putString("id", "InstrumentationTestRunner");
        status.putString("class", getClass().getName());
        status.putString("test", name);
        status.putInt("numtests", TOTAL);
        status.putInt("current", ++current);
        sendStatus(1, status);
        try {
            getTargetContext().deleteDatabase(NAME);
            db = new LibraryDatabase(getTargetContext(), NAME);
            test.run();
            status.putString("stream", ".");
            sendStatus(0, status);
        } catch (Throwable error) {
            failed++;
            status.putString("stack", Log.getStackTraceString(error));
            status.putString("stream", "\n" + name + ": " + error + "\n");
            sendStatus(-2, status);
        } finally {
            if (db != null) db.close();
            getTargetContext().deleteDatabase(NAME);
        }
    }

    private void metadata() {
        db.ensureImported("comic", "Ääni 漫画 🦊", 100, 200);
        db.applyDetectedSeries("comic", "Sarja 日本", "2", "", "");
        ReadingProgress p = db.get("comic");
        p.page = 3; p.pageCount = 20; p.scrollRatio = .25f; p.zoom = 1.5f;
        p.zoomGesturesLocked = true; p.lastOpened = 123;
        db.saveReadingProgress(p);
        db.toggleFavorite("comic"); db.toggleBookmark("comic", 3);
        db.setReadingDirection("comic", "rtl");
        db.setComicMetadata("comic", "Oma nimi 🦊", LibraryDatabase.SERIES_MANUAL, "Oma sarja", "2.5");
        db.ensureImported("comic", "Renamed source", 100, 200);
        p = db.get("comic");
        equal("Oma nimi 🦊", p.title); equal("Renamed source", p.originalTitle);
        equal("Oma sarja", p.seriesTitle); equal("2.5", p.seriesNumber);
        equal(3, p.page); equal(.25f, p.scrollRatio); equal(1.5f, p.zoom);
        check(p.zoomGesturesLocked && p.favorite && p.titleOverride);
        equal("rtl", p.readingDirection); check(db.isBookmarked("comic", 3));
        db.useAutomaticSeries("comic");
        equal("Sarja 日本", db.get("comic").seriesTitle);
        db.setManualSeries("comic", "", "");
        equal(0L, db.get("comic").seriesId);
    }

    private void indexRollback() {
        db.ensureImported("comic", "Comic", 100, 200);
        db.replacePageIndex("comic", List.of(new PageInfo("old.png", 12, 34)));
        boolean failed = false;
        try {
            db.replacePageIndex("comic", Arrays.asList(new PageInfo("new.png", 56, 78), null));
        } catch (NullPointerException expected) { failed = true; }
        check(failed);
        equal("old.png", db.pageIndex("comic").get(0).name);
        equal(1, db.get("comic").pageCount); check(db.get("comic").indexComplete);
    }

    private void relink() {
        seed("old", 10);
        db.toggleBookmark("old", 2);
        db.replacePageIndex("old", List.of(new PageInfo("page.png", 50, 70)));
        check(db.relinkCanonicalUri("old", "new"));
        check(db.get("old").uri.isEmpty()); equal("new", db.get("new").uri);
        check(db.isBookmarked("new", 2)); equal(1, db.pageIndex("new").size());
        equal("new", db.scannedFile("old-source").canonicalUri);
        db.ensureImported("occupied", "Occupied", 1, 1);
        check(!db.relinkCanonicalUri("new", "occupied"));
        check(db.isBookmarked("new", 2));
    }

    private void merge() {
        db.ensureImported("canonical", "Original", 100, 200); db.markManualSource("canonical");
        db.setComicMetadata("canonical", "My title", LibraryDatabase.SERIES_MANUAL, "Mine", "7");
        db.toggleBookmark("canonical", 1);
        seed("duplicate", 10);
        ReadingProgress p = db.get("duplicate"); p.page = 5; p.pageCount = 10; p.lastOpened = 99;
        db.saveReadingProgress(p); db.toggleFavorite("duplicate"); db.toggleBookmark("duplicate", 4);
        check(db.mergeExactDuplicate("canonical", "duplicate", "sample", "full").merged);
        p = db.get("canonical"); equal("My title", p.title); equal("Mine", p.seriesTitle);
        equal(5, p.page); check(p.favorite && p.manualSource);
        equal(List.of(1, 4), db.bookmarks("canonical"));
        check(db.get("duplicate").uri.isEmpty());
        equal("canonical", db.scannedFile("duplicate-source").canonicalUri);
    }

    private void missing() {
        seed("comic", 10);
        db.markFolderUnavailable("tree");
        check(!db.get("comic").available);
        check(db.confirmedMissingItems("tree").isEmpty());
        db.finishFolderScan("tree", 20);
        equal(1, db.confirmedMissingItems("tree").size());
        db.touchScannedFile("comic-source", "comic", "comic.cbz", 100, 200, 30);
        db.finishFolderScan("tree", 30);
        check(db.get("comic").available); check(db.confirmedMissingItems("tree").isEmpty());
    }

    private void forget() {
        java.io.File source = new java.io.File(getTargetContext().getCacheDir(), "refactor-source.cbz");
        try {
            check(source.createNewFile() || source.isFile());
            String uri = android.net.Uri.fromFile(source).toString();
            seed(uri, 10); seed("second", 10); db.toggleBookmark(uri, 2);
            db.getWritableDatabase().execSQL("CREATE TRIGGER reject_delete BEFORE DELETE ON progress " +
                    "WHEN OLD.uri='second' BEGIN SELECT RAISE(ABORT, 'test rollback'); END");
            boolean rolledBack = false;
            try { db.forgetAll(List.of(uri, "second")); }
            catch (android.database.SQLException expected) { rolledBack = true; }
            check(rolledBack); check(db.isBookmarked(uri, 2)); check(!db.get(uri).uri.isEmpty());
            db.getWritableDatabase().execSQL("DROP TRIGGER reject_delete");
            db.forgetAll(List.of(uri, "second"));
            check(db.get(uri).uri.isEmpty()); check(db.bookmarks(uri).isEmpty());
            check(source.isFile());
        } catch (java.io.IOException error) { throw new AssertionError(error); }
        finally { source.delete(); }
    }

    private void upgrade() {
        db.close(); getTargetContext().deleteDatabase(NAME);
        try (SQLiteDatabase old = getTargetContext().openOrCreateDatabase(NAME, 0, null)) {
            old.execSQL("CREATE TABLE progress (uri TEXT PRIMARY KEY,title TEXT NOT NULL," +
                    "page INTEGER NOT NULL DEFAULT 0,page_count INTEGER NOT NULL DEFAULT 0," +
                    "scroll_ratio REAL NOT NULL DEFAULT 0,zoom_mode TEXT NOT NULL DEFAULT 'fit_width'," +
                    "zoom REAL NOT NULL DEFAULT 1,reading_mode TEXT NOT NULL DEFAULT 'single'," +
                    "last_opened INTEGER NOT NULL DEFAULT 0)");
            old.execSQL("CREATE TABLE bookmarks (uri TEXT NOT NULL,page INTEGER NOT NULL," +
                    "created INTEGER NOT NULL,PRIMARY KEY(uri,page))");
            old.execSQL("INSERT INTO progress(uri,title,page,page_count,last_opened) " +
                    "VALUES('legacy','Vanha 🦊',4,30,123)");
            old.execSQL("INSERT INTO bookmarks VALUES('legacy',4,123)");
            old.setVersion(1);
        }
        db = new LibraryDatabase(getTargetContext(), NAME);
        ReadingProgress p = db.get("legacy");
        equal(8, db.getReadableDatabase().getVersion()); equal("Vanha 🦊", p.originalTitle);
        equal(4, p.page); equal(30, p.pageCount); equal(123L, p.addedAt);
        check(p.manualSource && p.available && !p.titleOverride); check(db.isBookmarked("legacy", 4));
        try (Cursor c = db.getReadableDatabase().rawQuery("PRAGMA integrity_check", null)) {
            check(c.moveToFirst()); equal("ok", c.getString(0));
        }
    }

    private void seed(String uri, long seen) {
        db.ensureImported(uri, "Comic", 100, 200);
        LibraryDatabase.ScannedFile f = new LibraryDatabase.ScannedFile();
        f.sourceIdentity = uri + "-source"; f.treeUri = "tree"; f.documentId = uri;
        f.documentUri = uri; f.canonicalUri = uri; f.lastSeen = seen;
        db.upsertScannedFile(f);
    }
    private static void check(boolean value) { if (!value) throw new AssertionError("Condition failed"); }
    private static void equal(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw new AssertionError(expected + " != " + actual);
    }
}
