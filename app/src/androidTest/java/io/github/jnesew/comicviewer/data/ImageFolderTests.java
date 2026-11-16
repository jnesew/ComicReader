package io.github.jnesew.comicviewer.data;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import io.github.jnesew.comicviewer.document.ComicDocument;
import io.github.jnesew.comicviewer.document.ComicDocumentFactory;
import io.github.jnesew.comicviewer.document.ImageFolderDocument;
import io.github.jnesew.comicviewer.model.PageInfo;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Real provider grants, image decoding, saved-page remapping, and schema 11 upgrade coverage. */
final class ImageFolderTests {
    static void run(Instrumentation instrumentation, LibraryDatabase db) {
        try { runChecked(instrumentation, db); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private static void runChecked(Instrumentation instrumentation, LibraryDatabase db) throws Exception {
        Context context = instrumentation.getTargetContext();
        String authority = ImageFolderDocumentsProvider.authority(context.getPackageName());
        Uri provider = Uri.parse("content://" + authority);
        Uri tree = DocumentsContract.buildTreeDocumentUri(authority, "images");
        Intent grant = new Intent();
        grant.setClassName(instrumentation.getContext().getPackageName(), FixtureGrantActivity.class.getName());
        grant.putExtra("targetPackage", context.getPackageName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(grant);
        long deadline = System.currentTimeMillis() + 5000;
        while (true) {
            try {
                context.getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                break;
            } catch (SecurityException pending) {
                if (System.currentTimeMillis() >= deadline) throw pending;
                Thread.sleep(10);
            }
        }
        context.getContentResolver().call(provider, "reset", null, null);
        File output = new File(context.getCacheDir(), "folder-extracted.png");
        try {
            try (ImageFolderDocument document = open(context, tree)) {
                check(document.count() == 3, "Nested folders were included");
                check(document.page(0).name.endsWith("/a") && document.page(1).name.endsWith("/c") &&
                        document.page(2).name.endsWith("/b"), "Pages did not sort naturally");
                check("Embedded folder".equals(document.title()) && document.suggestedRightToLeft(), "Sidecar hints missing");
                check("Folder series".equals(document.seriesMetadata().name), "Series missing");
                document.extractCover(output);
                Bitmap cover = BitmapFactory.decodeFile(output.getPath());
                try { check(cover.getPixel(0, 0) == Color.BLUE, "FrontCover hint was ignored"); }
                finally { cover.recycle(); }
                document.buildPageIndex(context, null, new ComicDocument.IndexCallback() {
                    @Override public void onProgress(int indexed, int count) { }
                    @Override public void onPagesUpdated() { }
                    @Override public void onComplete(List<PageInfo> pages) { }
                });
                check(document.isIndexComplete() && document.page(1).height == 120, "Dimensions were not decoded");
                db.ensureImported(document.key(), document.title(), -1, -1);
                db.markManualSource(document.key());
                db.reconcileImageFolder(document);
                var saved = db.get(document.key());
                saved.page = 1; saved.pageCount = 3; saved.scrollRatio = .4f;
                db.saveImageFolderProgress(saved, document.page(1).name);
                db.toggleBookmark(document.key(), 1);
                db.toggleBookmark(document.key(), 0);
                db.toggleFavorite(document.key());
                db.setReadStatus(document.key(), true);
                db.setComicMetadata(document.key(), "My title", LibraryDatabase.SERIES_MANUAL, "My series", "9");
            }
            options(context, provider, "insert", "rename");
            try (ImageFolderDocument document = open(context, tree)) {
                db.reconcileImageFolder(document);
                var saved = db.get(tree.toString());
                check(saved.page == 1 && saved.scrollRatio == .4f, "Progress did not follow image identity");
                check(db.isBookmarked(document.key(), 1) && db.isBookmarked(document.key(), 3), "Bookmarks did not follow renamed images");
                check(saved.favorite && saved.read && "My title".equals(saved.title) &&
                        "My series".equals(saved.seriesTitle), "Folder refresh changed user metadata");
                // An old reader snapshot must not restore a stale numeric page after reconciliation.
                saved.page = 0; saved.pageCount = 3;
                db.saveImageFolderProgress(saved, document.page(3).name);
                check(db.get(document.key()).page == 3 && db.get(document.key()).pageCount == 4, "Stale numeric save won");
            }
            options(context, provider, "remove");
            try (ImageFolderDocument document = open(context, tree)) {
                db.reconcileImageFolder(document);
                check(db.bookmarks(document.key()).equals(List.of(0)), "Absent image bookmark attached to another page");
            }
            db.close(); db.getWritableDatabase();
            options(context, provider);
            try (ImageFolderDocument document = open(context, tree)) {
                db.reconcileImageFolder(document);
                check(db.bookmarks(document.key()).equals(List.of(0, 1)), "Returning image lost its bookmark across restart");
                db.toggleBookmark(document.key(), 1);
                db.reconcileImageFolder(document);
                check(!db.isBookmarked(document.key(), 1), "Removed bookmark returned");
                options(context, provider, "changed");
                expectFailure(() -> document.extractPage(0, output));
            }
            for (String option : new String[]{"loading", "denied", "empty", "oversize"}) {
                options(context, provider, option);
                expectFailure(() -> { try (ImageFolderDocument ignored = open(context, tree)) { } });
                check("My title".equals(db.get(tree.toString()).title), "Failed provider open changed metadata");
            }
            options(context, provider);
            ImageFolderDocument closed = open(context, tree); closed.close();
            expectFailure(() -> closed.openPage(0));
            check(!new File(output.getPath() + ".partial").exists(), "Partial extraction survived failure");
            String prefs = "volume-navigation-instrumentation";
            context.deleteSharedPreferences(prefs);
            try (ImageFolderDocument document = open(context, tree)) {
                db.reconcileImageFolder(document);
                io.github.jnesew.comicviewer.reader.VolumeNavigationTests.run(instrumentation, db,
                        new ReaderPreferences(context, prefs, io.github.jnesew.comicviewer.render.BufferingPolicy.STANDARD), document);
            } finally { context.deleteSharedPreferences(prefs); }
            new LibraryFeatureTests(instrumentation, db).imageFolderAvailability(tree);
            cbzHints(context, db);
            schema11Upgrade(context);
            db.forget(tree.toString());
            try (var rows = db.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM image_folder_bookmarks", null)) {
                rows.moveToFirst(); check(rows.getInt(0) == 0, "Forget retained folder bookmarks");
            }
        } finally {
            output.delete();
            context.getContentResolver().releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
    }
    private static ImageFolderDocument open(Context context, Uri tree) throws IOException {
        return (ImageFolderDocument) ComicDocumentFactory.open(context, tree,
                ComicDocumentFactory.describe(context, tree), 0, List.of(), -1, -1, null);
    }
    private static void options(Context context, Uri provider, String... enabled) {
        Bundle options = new Bundle(); for (String key : enabled) options.putBoolean(key, true);
        context.getContentResolver().call(provider, "options", null, options);
    }
    private interface Action { void run() throws IOException; }
    private static void expectFailure(Action action) throws IOException {
        try { action.run(); } catch (IOException expected) { return; }
        throw new AssertionError("Expected a controlled I/O failure");
    }
    private static void cbzHints(Context context, LibraryDatabase db) throws Exception {
        File archive = new File(context.getCacheDir(), "comicinfo-instrumentation.cbz");
        File cover = new File(context.getCacheDir(), "comicinfo-cover.png");
        File stale = new File(context.getCacheDir(), "comicinfo-stale-cover.jpg");
        Bitmap image = Bitmap.createBitmap(80, 120, Bitmap.Config.ARGB_8888);
        try {
            try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive))) {
                for (int page = 0; page < 2; page++) {
                    zip.putNextEntry(new ZipEntry(page + ".png"));
                    image.eraseColor(page == 0 ? Color.RED : Color.BLUE);
                    image.compress(Bitmap.CompressFormat.PNG, 100, zip); zip.closeEntry();
                }
                zip.putNextEntry(new ZipEntry("ComicInfo.xml"));
                zip.write(("<ComicInfo><Title>CBZ title</Title><Manga>YesAndRightToLeft</Manga>" +
                        "<Pages><Page Image=\"1\" Type=\"FrontCover\" DoublePage=\"true\"/></Pages></ComicInfo>")
                        .getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            }
            Uri uri = Uri.fromFile(archive);
            try (ComicDocument document = ComicDocumentFactory.open(context, uri,
                    ComicDocumentFactory.describe(context, uri), 0, List.of(), -1, -1, null)) {
                check("CBZ title".equals(document.title()) && document.suggestedRightToLeft(), "CBZ publication hints ignored");
                check(document.metadata().pages.get(0).doublePage(), "Spread hint not exposed");
                document.extractCover(cover);
                Bitmap decoded = BitmapFactory.decodeFile(cover.getPath());
                try { check(decoded.getPixel(0, 0) == Color.BLUE, "CBZ cover hint ignored"); }
                finally { decoded.recycle(); }
            }
            // Simulate a library entry imported before FrontCover hints were understood.
            image.eraseColor(Color.RED);
            try (FileOutputStream output = new FileOutputStream(stale)) {
                image.compress(Bitmap.CompressFormat.JPEG, 90, output);
            }
            var description = ComicDocumentFactory.describe(context, uri);
            db.ensureImported(uri.toString(), description.title, description.size, description.modified);
            db.setCover(uri.toString(), stale.getPath(), LibraryDatabase.COVER_READY);
            java.util.concurrent.CountDownLatch imported = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger();
            var imports = new io.github.jnesew.comicviewer.library.LibraryImportCoordinator(context, db,
                    new io.github.jnesew.comicviewer.library.LibraryImportCoordinator.Listener() {
                        @Override public void onLibraryChanged() { }
                        @Override public void onImportFinished(int added) { count.set(added); imported.countDown(); }
                    });
            try {
                imports.importComics(List.of(uri));
                check(imported.await(5, java.util.concurrent.TimeUnit.SECONDS) && count.get() == 1, "CBZ refresh import failed");
                Bitmap refreshed = BitmapFactory.decodeFile(db.get(uri.toString()).coverPath);
                try {
                    check(refreshed != null && Color.blue(refreshed.getPixel(0, 0)) > 200 &&
                            Color.red(refreshed.getPixel(0, 0)) < 40, "Cached page-zero cover hid FrontCover metadata");
                } finally { if (refreshed != null) refreshed.recycle(); }
            } finally { imports.close(); imports.awaitStopped(); }
            CoverStore.delete(context, db.get(uri.toString()).coverPath);
        } finally { image.recycle(); archive.delete(); cover.delete(); stale.delete(); }
    }
    private static void schema11Upgrade(Context context) {
        String name = "image-folder-upgrade.sqlite3";
        context.deleteDatabase(name);
        try {
            try (LibraryDatabase old = new LibraryDatabase(context, name)) {
                old.ensureImported("existing", "Existing", 100, 200);
                old.toggleBookmark("existing", 2); old.toggleFavorite("existing"); old.setReadStatus("existing", true);
                old.getWritableDatabase().execSQL("DROP TABLE image_folder_bookmarks");
                old.getWritableDatabase().execSQL("DROP TABLE image_folders");
                old.getWritableDatabase().setVersion(11);
            }
            try (LibraryDatabase upgraded = new LibraryDatabase(context, name)) {
                check(upgraded.getReadableDatabase().getVersion() == 13, "Schema did not upgrade");
                check(upgraded.get("existing").favorite && upgraded.get("existing").read &&
                        upgraded.isBookmarked("existing", 2), "Schema upgrade lost state");
                try (var rows = upgraded.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM image_folders", null)) {
                    rows.moveToFirst(); check(rows.getInt(0) == 0, "Migration fabricated image folders");
                }
            }
        } finally { context.deleteDatabase(name); }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
