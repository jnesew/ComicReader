package io.github.jnesew.comicviewer.data;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Independent SAF image-folder fixtures, including mutation and incomplete provider results. */
public final class ImageFolderDocumentsProvider extends DocumentsProvider {
    static String authority(String targetPackage) { return targetPackage + ".test.imagefolders"; }
    private static final String[] COLUMNS = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED};
    private volatile Bundle options = new Bundle();
    private volatile boolean blocked;
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if ("state".equals(method)) {
            Bundle result = new Bundle(); result.putBoolean("blocked", blocked); return result;
        }
        if ("metadata".equals(method)) {
            try (FileOutputStream output = new FileOutputStream(file("meta"))) {
                output.write(extras.getString("xml").getBytes(StandardCharsets.UTF_8));
            } catch (IOException error) { throw new IllegalStateException(error); }
            return new Bundle();
        }
        if ("reset".equals(method)) {
            options = new Bundle();
            try {
                for (String id : new String[]{"a", "b", "c", "new"}) {
                    Bitmap image = Bitmap.createBitmap(80, 120, Bitmap.Config.ARGB_8888);
                    image.eraseColor("b".equals(id) ? Color.BLUE : Color.RED);
                    try (FileOutputStream output = new FileOutputStream(file(id))) {
                        image.compress(Bitmap.CompressFormat.PNG, 100, output);
                    } finally { image.recycle(); }
                }
                try (FileOutputStream output = new FileOutputStream(file("meta"))) {
                    output.write(("<ComicInfo><Title>Embedded folder</Title><Series>Folder series</Series>" +
                            "<Number>3</Number><Manga>YesAndRightToLeft</Manga>" +
                            "<Pages><Page Image=\"2\" Type=\"FrontCover\"/></Pages></ComicInfo>").getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException error) { throw new IllegalStateException(error); }
        } else if ("options".equals(method)) options = extras == null ? new Bundle() : new Bundle(extras);
        else return super.call(method, arg, extras);
        return new Bundle();
    }
    private File file(String id) { return new File(getContext().getCacheDir(), "image-folder-" + id); }
    private MatrixCursor cursor(String[] columns) { return new MatrixCursor(columns == null ? COLUMNS : columns); }
    private void add(MatrixCursor cursor, String id) {
        boolean directory = "images".equals(id) || "nested".equals(id);
        String name = switch (id) {
            case "images" -> "Loose pages"; case "nested" -> "Nested chapter";
            case "a" -> options.getBoolean("rename") ? "20.png" : "1.png";
            case "b" -> "10.png"; case "c" -> "2.png"; case "new" -> "0.png";
            default -> "ComicInfo.xml";
        };
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) {
            switch (column) {
                case Document.COLUMN_DOCUMENT_ID: row.add(id); break;
                case Document.COLUMN_DISPLAY_NAME: row.add(name); break;
                case Document.COLUMN_MIME_TYPE: row.add(directory ? Document.MIME_TYPE_DIR :
                        "meta".equals(id) ? "application/xml" : "image/png"); break;
                case Document.COLUMN_SIZE: row.add(directory ? 0 : options.getBoolean("oversize") && "a".equals(id)
                        ? io.github.jnesew.comicviewer.util.InputLimits.MAX_PAGE_BYTES + 1 : file(id).length()); break;
                case Document.COLUMN_LAST_MODIFIED: row.add(options.getBoolean("changed") && "a".equals(id) ? 2000 : 1000); break;
                default: row.add(null);
            }
        }
    }
    @Override public Cursor queryRoots(String[] projection) { return new MatrixCursor(new String[]{"root_id"}); }
    @Override public Cursor queryDocument(String id, String[] projection) {
        MatrixCursor cursor = cursor(projection); add(cursor, id); return cursor;
    }
    @Override public Cursor queryChildDocuments(String parent, String[] projection, String order)
            throws FileNotFoundException {
        blocked = options.getBoolean("block");
        long deadline = System.currentTimeMillis() + 10_000;
        while (options.getBoolean("block") && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(10); } catch (InterruptedException error) { Thread.currentThread().interrupt(); break; }
        }
        blocked = false;
        if (options.getBoolean("denied")) throw new FileNotFoundException("Disconnected folder");
        MatrixCursor cursor = cursor(projection);
        if (!"images".equals(parent)) return cursor;
        if (options.getBoolean("loading")) {
            Bundle extras = new Bundle(); extras.putBoolean(DocumentsContract.EXTRA_LOADING, true); cursor.setExtras(extras);
        }
        if (options.getBoolean("empty")) return cursor;
        if (options.getBoolean("insert")) add(cursor, "new");
        add(cursor, "b"); add(cursor, "a");
        if (!options.getBoolean("remove")) add(cursor, "c");
        add(cursor, "meta"); add(cursor, "nested");
        return cursor;
    }
    @Override public boolean isChildDocument(String parent, String child) { return "images".equals(parent); }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        if (options.getBoolean("denied")) throw new FileNotFoundException("Disconnected folder");
        if (signal != null) signal.throwIfCanceled();
        return ParcelFileDescriptor.open(file(id), ParcelFileDescriptor.MODE_READ_ONLY);
    }
}
