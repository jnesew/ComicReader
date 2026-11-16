package io.github.jnesew.comicviewer.data;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import io.github.jnesew.comicviewer.document.ComicDocumentFactory;
import io.github.jnesew.comicviewer.document.ImageFolderDocument;
import io.github.jnesew.comicviewer.library.FolderScanCoordinator;
import io.github.jnesew.comicviewer.library.LibraryImportCoordinator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Rescans use the real provider and coordinator, including failures, reading, and replaced requests. */
final class ImageFolderManagementTests {
    static void refresh(Instrumentation instrumentation, LibraryDatabase db) {
        Context context = instrumentation.getTargetContext();
        Uri provider = Uri.parse("content://" + ImageFolderDocumentsProvider.authority(context.getPackageName()));
        Uri tree = DocumentsContract.buildTreeDocumentUri(provider.getAuthority(), "images");
        Intent grant = new Intent();
        grant.setClassName(instrumentation.getContext().getPackageName(), FixtureGrantActivity.class.getName());
        grant.putExtra("targetPackage", context.getPackageName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(grant);
        await(() -> {
            try { context.getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION); return true; }
            catch (SecurityException pending) { return false; }
        });
        context.getContentResolver().call(provider, "reset", null, null);
        String prefsName = "image-folder-management-tests";
        context.deleteSharedPreferences(prefsName);
        ReaderPreferences prefs = new ReaderPreferences(context, prefsName, io.github.jnesew.comicviewer.render.BufferingPolicy.STANDARD);
        AtomicBoolean busy = new AtomicBoolean();
        LibraryImportCoordinator imports = new LibraryImportCoordinator(context, db, null);
        FolderScanCoordinator scans = new FolderScanCoordinator(context, db, prefs, imports, busy::get, null);
        try {
            try (ImageFolderDocument opened = (ImageFolderDocument) ComicDocumentFactory.open(context, tree,
                    ComicDocumentFactory.describe(context, tree), 0, List.of(), -1, -1, null)) {
                db.ensureImported(opened.key(), opened.title(), -1, -1); db.markManualSource(opened.key());
                db.reconcileImageFolder(opened);
                var saved = db.get(opened.key()); saved.page = 1; saved.scrollRatio = .4f; saved.lastOpened = 123;
                db.saveImageFolderProgress(saved, opened.page(1).name);
                db.toggleBookmark(opened.key(), 1); db.toggleFavorite(opened.key()); db.setReadStatus(opened.key(), true);
                db.setComicMetadata(opened.key(), "My title", LibraryDatabase.SERIES_MANUAL, "My series", "9");
            }
            check(db.imageFolderComics().size() == 1 && db.isImageFolder(tree.toString()), "Image comic missing from folder sources");
            Bundle xml = new Bundle(); xml.putString("xml", "<ComicInfo><Title>New original</Title><Writer>New writer</Writer><Summary>New synopsis</Summary></ComicInfo>");
            context.getContentResolver().call(provider, "metadata", null, xml);
            options(context, provider, "insert", "rename");
            main(instrumentation, () -> scans.maybeScanLibraryFolder(true)); idle(instrumentation, scans);
            var refreshed = db.get(tree.toString());
            check(refreshed.pageCount == 4 && refreshed.page == 1 && refreshed.scrollRatio == .4f, "Rescan lost the page anchor");
            check(db.bookmarks(tree.toString()).equals(List.of(1)), "Rescan moved a bookmark to another image");
            check(refreshed.lastOpened == 123 && refreshed.favorite && refreshed.read && "My title".equals(refreshed.title)
                    && "My series".equals(refreshed.seriesTitle), "Rescan changed reading or user metadata");
            check("New writer".equals(db.publicationMetadata(tree.toString()).creators.get("Writer")), "Rescan did not refresh ComicInfo");
            for (String failure : new String[]{"loading", "denied", "empty"}) {
                options(context, provider, failure);
                main(instrumentation, () -> scans.maybeScanLibraryFolder(true)); idle(instrumentation, scans);
                check(db.get(tree.toString()).pageCount == 4 && db.get(tree.toString()).page == 1
                        && db.bookmarks(tree.toString()).equals(List.of(1)), "Failed scan replaced saved pages or bookmarks");
                check("New synopsis".equals(db.publicationMetadata(tree.toString()).summary), "Failed scan erased metadata");
                check(scans.imageFolderState(tree.toString()).unavailable(), "Failed scan was reported as successful");
            }
            options(context, provider, "remove"); busy.set(true);
            main(instrumentation, () -> scans.maybeScanLibraryFolder(true)); idle(instrumentation, scans);
            check(db.get(tree.toString()).pageCount == 4, "Rescan changed the active reader manifest");
            busy.set(false);
            db.setSourceAvailability(tree.toString(), false);
            db.setManualSourceAvailable(tree.toString(), tree.toString(), false);
            main(instrumentation, () -> scans.maybeScanLibraryFolder(false)); idle(instrumentation, scans);
            check(db.get(tree.toString()).available && db.get(tree.toString()).pageCount == 2 && db.bookmarks(tree.toString()).isEmpty(), "Deferred scan failed or reassigned absent bookmark");

            options(context, provider, "block");
            main(instrumentation, () -> scans.maybeScanLibraryFolder(true));
            await(() -> context.getContentResolver().call(provider, "state", null, null).getBoolean("blocked"));
            main(instrumentation, () -> { scans.cancelImageFolder(tree.toString()); scans.maybeScanLibraryFolder(true); });
            options(context, provider, "insert"); idle(instrumentation, scans);
            check(db.get(tree.toString()).pageCount == 4 && db.bookmarks(tree.toString()).equals(List.of(2)), "Cancelled completion displaced the retry");

            options(context, provider, "block");
            main(instrumentation, () -> scans.maybeScanLibraryFolder(true));
            await(() -> context.getContentResolver().call(provider, "state", null, null).getBoolean("blocked"));
            busy.set(true); options(context, provider, "remove"); idle(instrumentation, scans);
            check(db.get(tree.toString()).pageCount == 4, "Reader opened during scan but stale manifest was published");
            busy.set(false); main(instrumentation, () -> scans.maybeScanLibraryFolder(false)); idle(instrumentation, scans);
            check(db.get(tree.toString()).pageCount == 2, "Interrupted refresh did not resume after reading");

            options(context, provider, "block");
            main(instrumentation, () -> scans.maybeScanLibraryFolder(true));
            await(() -> context.getContentResolver().call(provider, "state", null, null).getBoolean("blocked"));
            main(instrumentation, () -> { scans.cancelImageFolder(tree.toString()); db.forget(tree.toString()); });
            options(context, provider); main(instrumentation, scans::close); scans.awaitStopped();
            check(db.imageFolderComics().isEmpty() && db.get(tree.toString()).uri.isEmpty(), "Late refresh resurrected a forgotten comic");
        } catch (Exception error) { throw new AssertionError(error); }
        finally {
            options(context, provider); main(instrumentation, () -> { scans.close(); imports.close(); });
            try { scans.awaitStopped(); imports.awaitStopped(); } catch (InterruptedException error) { throw new AssertionError(error); }
            context.getContentResolver().releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.deleteSharedPreferences(prefsName);
        }
    }
    private static void options(Context context, Uri provider, String... flags) {
        Bundle bundle = new Bundle(); for (String flag : flags) bundle.putBoolean(flag, true);
        context.getContentResolver().call(provider, "options", null, bundle);
    }
    private static void main(Instrumentation instrumentation, Runnable action) {
        java.util.concurrent.atomic.AtomicReference<Throwable> error = new java.util.concurrent.atomic.AtomicReference<>();
        instrumentation.runOnMainSync(() -> { try { action.run(); } catch (Throwable failure) { error.set(failure); } });
        if (error.get() != null) throw new AssertionError(error.get());
    }
    private static void idle(Instrumentation instrumentation, FolderScanCoordinator scans) {
        await(() -> { AtomicBoolean idle = new AtomicBoolean(); main(instrumentation, () -> idle.set(!scans.isRunning())); return idle.get(); });
    }
    private static void await(BooleanSupplier condition) {
        long end = System.currentTimeMillis() + 15_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > end) throw new AssertionError("Timed out waiting for folder refresh");
            try { Thread.sleep(20); } catch (InterruptedException error) { throw new AssertionError(error); }
        }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
