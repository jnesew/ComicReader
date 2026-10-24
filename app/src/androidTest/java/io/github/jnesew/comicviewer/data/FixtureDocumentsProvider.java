package io.github.jnesew.comicviewer.data;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** A real, test-APK-only provider with opaque IDs and controllable source failures. */
public final class FixtureDocumentsProvider extends DocumentsProvider {
    static final String AUTHORITY = "io.github.jnesew.comicviewer.test.documents";
    private static final String[] COLUMNS = { Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS };
    private volatile boolean denyA, partialA, hideA, distinct, blockA;
    private int aListings;

    @Override public boolean onCreate() { return true; }

    @Override public synchronized Bundle call(String method, String arg, Bundle options) {
        if ("fixture-reset".equals(method)) {
            denyA = partialA = hideA = distinct = blockA = false;
            aListings = 0;
            try { archive("same.cbz", Color.RED); archive("different.cbz", Color.BLUE); }
            catch (IOException error) { throw new IllegalStateException(error); }
        } else if ("fixture-options".equals(method)) {
            denyA = options.getBoolean("denyA"); partialA = options.getBoolean("partialA");
            hideA = options.getBoolean("hideA"); distinct = options.getBoolean("distinct");
            blockA = options.getBoolean("blockA");
        } else if (!"fixture-stats".equals(method)) {
            return super.call(method, arg, options);
        }
        Bundle result = new Bundle(); result.putInt("aListings", aListings); return result;
    }

    private void archive(String name, int color) throws IOException {
        Bitmap image = Bitmap.createBitmap(80, 120, Bitmap.Config.ARGB_8888);
        image.eraseColor(color);
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file(name)))) {
            for (int page = 0; page < 3; page++) {
                ZipEntry entry = new ZipEntry("page" + page + ".png"); entry.setTime(1_000_000L);
                zip.putNextEntry(entry); image.compress(Bitmap.CompressFormat.PNG, 100, zip);
                zip.closeEntry();
            }
        } finally { image.recycle(); }
    }

    private File file(String name) { return new File(getContext().getCacheDir(), name); }
    private void accessible(String id) throws FileNotFoundException {
        if (denyA && id.startsWith("a")) throw new FileNotFoundException("Disconnected fixture A");
    }
    private MatrixCursor cursor(String[] projection) {
        return new MatrixCursor(projection == null ? COLUMNS : projection);
    }
    private void add(MatrixCursor cursor, String id) throws FileNotFoundException {
        accessible(id);
        boolean directory = !id.endsWith("book");
        String name = directory ? (id.endsWith("series") ? "Shared series" : "Root " + id)
                : "Same name.cbz";
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) {
            switch (column) {
                case Document.COLUMN_DOCUMENT_ID: row.add(id); break;
                case Document.COLUMN_DISPLAY_NAME: row.add(name); break;
                case Document.COLUMN_MIME_TYPE:
                    row.add(directory ? Document.MIME_TYPE_DIR : "application/vnd.comicbook+zip"); break;
                case Document.COLUMN_SIZE:
                    row.add(directory ? 0 : file(distinct && id.startsWith("b")
                            ? "different.cbz" : "same.cbz").length()); break;
                case Document.COLUMN_LAST_MODIFIED: row.add(1000L); break;
                case Document.COLUMN_FLAGS: row.add(0); break;
                default: row.add(null);
            }
        }
    }

    @Override public Cursor queryRoots(String[] projection) { return new MatrixCursor(new String[]{"root_id"}); }
    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = cursor(projection); add(cursor, id); return cursor;
    }
    @Override public Cursor queryChildDocuments(String parent, String[] projection, String sort)
            throws FileNotFoundException {
        accessible(parent);
        if (parent.equals("a-series")) {
            synchronized (this) { aListings++; }
            if (blockA) {
                // Bounded delay lets tests remove a source while its provider call is in flight.
                try { Thread.sleep(500); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            }
            if (partialA) throw new FileNotFoundException("Partial fixture traversal");
        }
        MatrixCursor cursor = cursor(projection);
        if (parent.equals("a") || parent.equals("b")) add(cursor, parent + "-series");
        else if (parent.equals("a-series") && !hideA) add(cursor, "a-book");
        else if (parent.equals("b-series")) add(cursor, "b-book");
        return cursor;
    }
    @Override public boolean isChildDocument(String parent, String child) {
        return parent.equals(child) || (parent.equals("a") && (child.equals("a-series") || child.equals("a-book")))
                || (parent.equals("b") && (child.equals("b-series") || child.equals("b-book")))
                || (parent.equals("a-series") && child.equals("a-book"))
                || (parent.equals("b-series") && child.equals("b-book"));
    }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        accessible(id);
        if (signal != null) signal.throwIfCanceled();
        if (!id.endsWith("book")) throw new FileNotFoundException(id);
        return ParcelFileDescriptor.open(file(distinct && id.startsWith("b")
                ? "different.cbz" : "same.cbz"), ParcelFileDescriptor.MODE_READ_ONLY);
    }
}
