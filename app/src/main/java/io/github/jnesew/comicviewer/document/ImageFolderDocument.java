package io.github.jnesew.comicviewer.document;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import io.github.jnesew.comicviewer.R;
import io.github.jnesew.comicviewer.archive.ComicInfoParser;
import io.github.jnesew.comicviewer.model.ComicMetadata;
import io.github.jnesew.comicviewer.model.PageInfo;
import io.github.jnesew.comicviewer.model.SeriesMetadata;
import io.github.jnesew.comicviewer.util.InputLimits;
import io.github.jnesew.comicviewer.util.NaturalOrder;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** One explicitly selected SAF directory, with a fixed, nonrecursive page manifest per session. */
public final class ImageFolderDocument implements ComicDocument {
    private record Entry(Uri uri, String name, long size, long modified) { }
    private final ContentResolver resolver;
    private final Context context;
    private final Uri uri;
    private final String title;
    private final List<Entry> entries;
    private final ComicMetadata metadata;
    private final AtomicReferenceArray<PageInfo> info;
    private final boolean[] indexed;
    private final List<PageInfo> pages;
    private volatile int indexedPages;
    private volatile boolean closed;

    private ImageFolderDocument(Context context, Uri uri, String title, List<Entry> entries,
            ComicMetadata metadata, int preferredPage) throws IOException {
        this.context = context.getApplicationContext();
        resolver = context.getContentResolver();
        this.uri = uri;
        this.title = metadata.title.isEmpty() ? title : metadata.title;
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
        this.metadata = metadata;
        info = new AtomicReferenceArray<>(entries.size());
        indexed = new boolean[entries.size()];
        int target = Math.max(0, Math.min(preferredPage, entries.size() - 1));
        PageInfo first = decodeInfo(target);
        for (int page = 0; page < entries.size(); page++) {
            info.set(page, new PageInfo(entries.get(page).uri.toString(), first.width, first.height));
        }
        info.set(target, first);
        indexed[target] = true;
        indexedPages = 1;
        pages = Collections.unmodifiableList(new AbstractList<>() {
            @Override public PageInfo get(int page) { return info.get(page); }
            @Override public int size() { return info.length(); }
        });
    }

    public static Uri directoryUri(Uri tree) {
        try {
            DocumentsContract.getDocumentId(tree);
            return tree;
        } catch (IllegalArgumentException rootTree) {
            return DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        }
    }

    public static ImageFolderDocument open(Context context, Uri tree, DocumentInfo description,
            int preferredPage) throws IOException {
        if (!DocumentsContract.isTreeUri(tree)) throw new IOException(context.getString(R.string.error_image_folder_access));
        Uri directory = directoryUri(tree);
        String parent = DocumentsContract.getDocumentId(directory);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent);
        List<Entry> entries = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Entry sidecar = null;
        int sidecars = 0;
        ContentResolver resolver = context.getContentResolver();
        String[] projection = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED};
        try (Cursor cursor = resolver.query(children, projection, null, null, null)) {
            if (cursor == null) throw new IOException(context.getString(R.string.error_image_folder_access));
            int count = 0;
            long declaredBytes = 0;
            while (cursor.moveToNext()) {
                interrupted();
                InputLimits.validateArchiveEntryCount(++count);
                String id = cursor.getString(0);
                String name = cursor.getString(1);
                if (id == null || name == null || id.isEmpty() ||
                        id.codePointCount(0, id.length()) > InputLimits.MAX_IDENTIFIER_CODE_POINTS ||
                        name.codePointCount(0, name.length()) > InputLimits.MAX_PATH_CODE_POINTS ||
                        !ids.add(id)) throw new IOException(context.getString(R.string.error_image_folder_access));
                if (Document.MIME_TYPE_DIR.equals(cursor.getString(2))) continue;
                long size = cursor.isNull(3) ? -1L : cursor.getLong(3);
                long modified = cursor.isNull(4) ? -1L : cursor.getLong(4);
                Entry entry = new Entry(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, size, modified);
                if ("comicinfo.xml".equalsIgnoreCase(name)) {
                    sidecars++;
                    sidecar = entry;
                } else if (isImage(name)) {
                    if (size > InputLimits.MAX_PAGE_BYTES) throw new IOException(context.getString(R.string.error_page_too_large));
                    declaredBytes += Math.max(0, size);
                    InputLimits.validateDocumentSize(declaredBytes);
                    entries.add(entry);
                    InputLimits.validatePageCount(entries.size());
                }
            }
            if (cursor.getExtras().getBoolean(DocumentsContract.EXTRA_LOADING, false) ||
                    cursor.getExtras().containsKey(DocumentsContract.EXTRA_ERROR)) {
                throw new IOException(context.getString(R.string.error_image_folder_access));
            }
        } catch (RuntimeException invalid) {
            throw new IOException(context.getString(R.string.error_image_folder_access), invalid);
        }
        if (entries.isEmpty()) throw new IOException(context.getString(R.string.error_no_pages));
        entries.sort((left, right) -> {
            int order = NaturalOrder.INSTANCE.compare(left.name, right.name);
            return order == 0 ? left.uri.toString().compareTo(right.uri.toString()) : order;
        });
        ComicMetadata metadata = ComicMetadata.EMPTY;
        if (sidecars == 1 && sidecar != null && sidecar.size <= 1024 * 1024) {
            try (InputStream input = resolver.openInputStream(sidecar.uri)) {
                if (input != null) metadata = ComicInfoParser.parseMetadata(input);
            } catch (IOException | RuntimeException ignored) { /* Optional sidecar. */ }
        }
        interrupted();
        return new ImageFolderDocument(context, tree, description.displayName, entries, metadata, preferredPage);
    }

    private static boolean isImage(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String extension : new String[]{".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp"}) {
            if (lower.endsWith(extension)) return true;
        }
        return false;
    }

    @Override public Uri uri() { return uri; }
    @Override public String key() { return uri.toString(); }
    @Override public String title() { return title; }
    // Directory timestamps and sizes cannot establish that the children are unchanged.
    @Override public long documentSize() { return -1L; }
    @Override public long documentModified() { return -1L; }
    @Override public int count() { return entries.size(); }
    @Override public int indexedPages() { return indexedPages; }
    @Override public boolean isIndexComplete() { return indexedPages == count(); }
    @Override public boolean suggestedRightToLeft() { return metadata.manga == ComicMetadata.Manga.RIGHT_TO_LEFT; }
    @Override public SeriesMetadata seriesMetadata() { return metadata.series; }
    @Override public ComicMetadata metadata() { return metadata; }
    @Override public List<PageInfo> pages() { return pages; }
    @Override public List<PageInfo> snapshotPages() { return new ArrayList<>(pages); }
    @Override public PageInfo page(int index) { return info.get(index); }

    @Override public void buildPageIndex(Context context, ProgressCallback progress, IndexCallback callback)
            throws IOException {
        IOException failure = null;
        for (int page = 0; page < count(); page++) {
            if (closed || Thread.currentThread().isInterrupted() || callback.isCancelled()) return;
            if (!indexed[page]) {
                try {
                    info.set(page, decodeInfo(page));
                    indexed[page] = true;
                    indexedPages++;
                } catch (IOException error) {
                    if (closed || Thread.currentThread().isInterrupted() || callback.isCancelled()) return;
                    if (failure == null) failure = error;
                }
            }
            if (page % 12 == 0 || page == count() - 1) {
                if (progress != null) progress.update(context.getString(
                        R.string.reader_reading_page_information, page + 1, count()));
                callback.onProgress(indexedPages, count());
                callback.onPagesUpdated();
            }
        }
        if (!closed && !Thread.currentThread().isInterrupted() && !callback.isCancelled()) {
            if (failure != null) throw failure;
            callback.onComplete(snapshotPages());
        }
    }

    private PageInfo decodeInfo(int page) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream input = openPage(page)) { BitmapFactory.decodeStream(input, null, options); }
        interrupted();
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            throw new IOException(context.getString(R.string.error_decode_image,
                    InputLimits.normalizeText(entries.get(page).name, InputLimits.MAX_TITLE_CODE_POINTS)));
        }
        InputLimits.validateImageDimensions(options.outWidth, options.outHeight);
        return new PageInfo(entries.get(page).uri.toString(), options.outWidth, options.outHeight);
    }

    @Override public synchronized InputStream openPage(int page) throws IOException {
        ensureOpen();
        Entry entry = entries.get(page);
        DocumentInfo current = DocumentInfo.describe(resolver, entry.uri);
        if ((entry.size >= 0 && current.size >= 0 && entry.size != current.size) ||
                (entry.modified >= 0 && current.modified >= 0 && entry.modified != current.modified)) {
            throw new IOException(context.getString(R.string.error_image_folder_changed));
        }
        try {
            InputStream input = resolver.openInputStream(entry.uri);
            if (input == null) throw new IOException(context.getString(R.string.error_page_missing));
            return new BufferedInputStream(InputLimits.bounded(input, InputLimits.MAX_PAGE_BYTES,
                    InputLimits.Reason.PAGE_BYTES), 64 * 1024);
        } catch (RuntimeException invalid) {
            throw new IOException(context.getString(R.string.error_image_folder_access), invalid);
        }
    }

    @Override public synchronized void extractPage(int page, File destination) throws IOException {
        try (InputStream input = openPage(page)) {
            InputLimits.copyAtomically(input, destination, InputLimits.MAX_PAGE_BYTES, InputLimits.Reason.PAGE_BYTES,
                    context.getString(R.string.error_image_folder_access),
                    context.getString(R.string.error_image_folder_access));
        }
    }
    @Override public void extractCover(File destination) throws IOException {
        extractPage(metadata.coverPage(count()), destination);
    }
    @Override public synchronized void close() { closed = true; }
    private void ensureOpen() throws IOException {
        interrupted();
        if (closed) throw new IOException(context.getString(R.string.error_image_folder_access));
    }
    private static void interrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
    }
}
