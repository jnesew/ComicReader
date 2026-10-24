package io.github.jnesew.comicviewer.data;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.os.CancellationSignal;

import io.github.jnesew.comicviewer.document.ComicDocumentFactory;
import io.github.jnesew.comicviewer.util.InputLimits;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;

/** Iterative, bounded traversal of one user-granted Storage Access Framework tree. */
public final class LibraryFolderScanner {
    private static final int MAX_DOCUMENTS = 100_000;
    private static final int MAX_DEPTH = 128;

    public interface Cancellation {
        boolean isCancelled();
        default CancellationSignal signal() { return null; }
    }

    public interface Visitor {
        void onComic(Entry entry);
    }

    public static final class Entry {
        public final String treeUri;
        public final String documentId;
        public final Uri uri;
        public final String displayName;
        public final String mimeType;
        public final long size;
        public final long modified;
        public final String relativePath;
        public final String seriesFolderKey;
        public final String seriesFolderName;

        private Entry(
                Uri treeUri,
                String documentId,
                Uri uri,
                String displayName,
                String mimeType,
                long size,
                long modified,
                String relativePath,
                String seriesFolderKey,
                String seriesFolderName) {
            this.treeUri = treeUri.toString();
            this.documentId = documentId;
            this.uri = uri;
            this.displayName = InputLimits.normalizeText(
                    displayName, InputLimits.MAX_TITLE_CODE_POINTS);
            this.mimeType = InputLimits.normalizeText(
                    mimeType, InputLimits.MAX_MIME_CODE_POINTS);
            this.size = size;
            this.modified = modified;
            this.relativePath = InputLimits.normalizeText(
                    relativePath, InputLimits.MAX_PATH_CODE_POINTS);
            this.seriesFolderKey = seriesFolderKey;
            this.seriesFolderName = InputLimits.normalizeText(
                    seriesFolderName, InputLimits.MAX_SERIES_CODE_POINTS);
        }

        public String sourceIdentity() {
            return treeUri + '\n' + documentId;
        }
    }

    public static final class Summary {
        public int directories;
        public int documents;
        public int supportedFiles;
        public int providerErrors;
        public boolean bounded;
        public boolean cancelled;
        public int cachedDirectories;

        public boolean complete() {
            return !bounded && !cancelled && providerErrors == 0;
        }
    }

    private LibraryFolderScanner() {
    }

    public static Summary scan(
            Context context,
            Uri treeUri,
            Cancellation cancellation,
            Visitor visitor) throws IOException {
        return scan(context, treeUri, cancellation, visitor, null);
    }

    public static Summary scan(Context context, Uri treeUri, Cancellation cancellation,
            Visitor visitor, DirectoryCache directoryCache) throws IOException {
        if (treeUri == null || !DocumentsContract.isTreeUri(treeUri)) {
            throw new IOException("The selected library folder is invalid.");
        }
        if (!InputLimits.isIdentifierAcceptable(treeUri.toString())) {
            throw new IOException("The selected library folder identifier is too large.");
        }
        ContentResolver resolver = context.getContentResolver();
        String rootId;
        try {
            rootId = DocumentsContract.getTreeDocumentId(treeUri);
        } catch (RuntimeException exception) {
            throw new IOException("The selected library folder is invalid.", exception);
        }
        if (!InputLimits.isIdentifierAcceptable(rootId)) {
            throw new IOException("The selected library folder identifier is too large.");
        }
        // Cached listings never substitute for proving access through this selected grant.
        Uri rootDocument = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId);
        try (Cursor root = resolver.query(rootDocument, new String[]{
                DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null, cancellation.signal())) {
            if (root == null || !root.moveToFirst() ||
                    !DocumentsContract.Document.MIME_TYPE_DIR.equals(root.getString(0))) {
                throw new IOException("The selected library folder is unavailable.");
            }
        } catch (RuntimeException unavailable) {
            throw new IOException("The selected library folder is unavailable.", unavailable);
        }

        Summary summary = new Summary();
        ArrayDeque<Folder> pending = new ArrayDeque<>();
        pending.add(new Folder(rootId, "", 0, "", ""));
        Set<String> visited = new HashSet<>();
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
        };

        while (!pending.isEmpty() && !cancellation.isCancelled()) {
            Folder folder = pending.removeFirst();
            if (!visited.add(folder.documentId)) continue;
            if (folder.depth > MAX_DEPTH || summary.documents >= MAX_DOCUMENTS) {
                summary.bounded = true;
                continue;
            }
            summary.directories++;
            Uri children;
            try {
                children = DocumentsContract.buildChildDocumentsUriUsingTree(
                        treeUri, folder.documentId);
            } catch (RuntimeException exception) {
                summary.providerErrors++;
                continue;
            }

            String cacheKey = treeUri.getAuthority() + "\n" + folder.documentId;
            Cursor cached = directoryCache == null ? null : directoryCache.cursor(cacheKey, projection);
            ArrayList<Object[]> observed = cached == null && directoryCache != null ? new ArrayList<>() : null;
            boolean cacheable = observed != null;
            if (cached != null) summary.cachedDirectories++;
            try (Cursor cursor = cached != null ? cached : resolver.query(children, projection, null, null, null,
                    cancellation.signal())) {
                if (cursor == null) {
                    summary.providerErrors++;
                    continue;
                }
                int idColumn = cursor.getColumnIndexOrThrow(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                int nameColumn = cursor.getColumnIndexOrThrow(
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                int mimeColumn = cursor.getColumnIndexOrThrow(
                        DocumentsContract.Document.COLUMN_MIME_TYPE);
                int sizeColumn = cursor.getColumnIndex(
                        DocumentsContract.Document.COLUMN_SIZE);
                int modifiedColumn = cursor.getColumnIndex(
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED);
                while (cursor.moveToNext() && !cancellation.isCancelled()) {
                    if (summary.documents++ >= MAX_DOCUMENTS) {
                        summary.bounded = true;
                        break;
                    }
                    String documentId = value(cursor, idColumn);
                    if (documentId.isEmpty() ||
                            !InputLimits.isIdentifierAcceptable(documentId)) {
                        cacheable = false;
                        summary.providerErrors++;
                        continue;
                    }
                    String displayName = InputLimits.normalizeText(
                            value(cursor, nameColumn), InputLimits.MAX_TITLE_CODE_POINTS);
                    if (displayName.isEmpty()) displayName = documentId;
                    String mimeType = InputLimits.normalizeText(
                            value(cursor, mimeColumn), InputLimits.MAX_MIME_CODE_POINTS);
                    if (cacheable) {
                        if (observed.size() >= 256) { cacheable = false; observed.clear(); }
                        else observed.add(new Object[]{documentId, displayName, mimeType,
                                longValue(cursor, sizeColumn), longValue(cursor, modifiedColumn)});
                    }
                    String relativePath = InputLimits.normalizeText(
                            folder.relativePath.isEmpty()
                                    ? displayName : folder.relativePath + "/" + displayName,
                            InputLimits.MAX_PATH_CODE_POINTS);
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                        String seriesKey = folder.seriesFolderKey;
                        String seriesName = folder.seriesFolderName;
                        if (folder.depth == 0) {
                            seriesKey = treeUri + "\n" + documentId;
                            if (seriesKey.codePointCount(0, seriesKey.length()) >
                                    InputLimits.MAX_STABLE_KEY_CODE_POINTS) {
                                summary.providerErrors++;
                                continue;
                            }
                            seriesName = displayName;
                        }
                        pending.addLast(new Folder(
                                documentId, relativePath, folder.depth + 1,
                                seriesKey, seriesName));
                        continue;
                    }
                    if (!ComicDocumentFactory.isSupportedHint(displayName, mimeType)) continue;
                    Uri documentUri;
                    try {
                        documentUri = DocumentsContract.buildDocumentUriUsingTree(
                                treeUri, documentId);
                    } catch (RuntimeException exception) {
                        summary.providerErrors++;
                        continue;
                    }
                    summary.supportedFiles++;
                    visitor.onComic(new Entry(
                            treeUri, documentId, documentUri, displayName, mimeType,
                            longValue(cursor, sizeColumn), longValue(cursor, modifiedColumn),
                            relativePath, folder.seriesFolderKey, folder.seriesFolderName));
                }
                if (cacheable && !cancellation.isCancelled() && !summary.bounded) {
                    directoryCache.put(cacheKey, observed);
                }
            } catch (SecurityException | IllegalArgumentException exception) {
                summary.providerErrors++;
            } catch (RuntimeException exception) {
                summary.providerErrors++;
            }
        }
        summary.cancelled = cancellation.isCancelled();
        return summary;
    }

    /** Bounded metadata reuse within one scan batch, keyed only by provider and opaque document ID. */
    public static final class DirectoryCache {
        private static final int MAX_ROWS = 2048;
        private static final int MAX_CHARACTERS = 1_048_576;
        private final LinkedHashMap<String, List<Object[]>> entries = new LinkedHashMap<>();
        private int rows;
        private int characters;

        public void clear() { entries.clear(); rows = 0; characters = 0; }

        private Cursor cursor(String key, String[] projection) {
            List<Object[]> values = entries.get(key);
            if (values == null) return null;
            MatrixCursor cursor = new MatrixCursor(projection, values.size());
            for (Object[] row : values) cursor.addRow(row);
            return cursor;
        }

        private void put(String key, List<Object[]> values) {
            int addedCharacters = characters(key, values);
            if (values.size() > MAX_ROWS || addedCharacters > MAX_CHARACTERS) return;
            while (!entries.isEmpty() && (rows + values.size() > MAX_ROWS ||
                    characters + addedCharacters > MAX_CHARACTERS || entries.size() >= 128)) {
                String oldest = entries.keySet().iterator().next();
                List<Object[]> removed = entries.remove(oldest);
                rows -= removed.size();
                characters -= characters(oldest, removed);
            }
            entries.put(key, values);
            rows += values.size();
            characters += addedCharacters;
        }

        private static int characters(String key, List<Object[]> values) {
            int count = key.length();
            for (Object[] row : values) for (Object value : row) {
                if (value instanceof String string) count += string.length();
            }
            return count;
        }
    }

    private static String value(Cursor cursor, int column) {
        return column < 0 || cursor.isNull(column) ? "" : cursor.getString(column);
    }

    private static long longValue(Cursor cursor, int column) {
        return column < 0 || cursor.isNull(column) ? -1L : cursor.getLong(column);
    }

    private static final class Folder {
        private final String documentId;
        private final String relativePath;
        private final int depth;
        private final String seriesFolderKey;
        private final String seriesFolderName;

        private Folder(
                String documentId,
                String relativePath,
                int depth,
                String seriesFolderKey,
                String seriesFolderName) {
            this.documentId = documentId;
            this.relativePath = relativePath;
            this.depth = depth;
            this.seriesFolderKey = seriesFolderKey;
            this.seriesFolderName = seriesFolderName;
        }
    }
}
