package com.localtools.comicviewer.document;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import com.localtools.comicviewer.util.InputLimits;

import java.util.Locale;

/** Stable metadata available before a selected document is parsed. */
public final class DocumentInfo {
    public final String displayName;
    public final String title;
    public final long size;
    public final long modified;
    public final String mimeType;

    public DocumentInfo(String displayName, long size, long modified, String mimeType) {
        String safeName = InputLimits.normalizeText(
                displayName, InputLimits.MAX_TITLE_CODE_POINTS);
        this.displayName = safeName.isEmpty() ? "Comic" : safeName;
        String safeTitle = InputLimits.normalizeText(
                stripSupportedExtension(this.displayName), InputLimits.MAX_TITLE_CODE_POINTS);
        this.title = safeTitle.isEmpty() ? "Comic" : safeTitle;
        this.size = size;
        this.modified = modified;
        this.mimeType = InputLimits.normalizeText(
                mimeType, InputLimits.MAX_MIME_CODE_POINTS);
    }

    public static DocumentInfo describe(ContentResolver resolver, Uri uri) {
        String title = null;
        long size = -1L;
        long modified = -1L;
        String[] projection = {
                OpenableColumns.DISPLAY_NAME,
                OpenableColumns.SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
        };
        try (Cursor cursor = resolver.query(uri, projection, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int titleColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE);
                int modifiedColumn = cursor.getColumnIndex(
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED);
                if (titleColumn >= 0 && !cursor.isNull(titleColumn)) title = cursor.getString(titleColumn);
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn);
                if (modifiedColumn >= 0 && !cursor.isNull(modifiedColumn)) {
                    modified = cursor.getLong(modifiedColumn);
                }
            }
        } catch (RuntimeException ignored) {
            try (Cursor cursor = resolver.query(
                    uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                    null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    if (!cursor.isNull(0)) title = cursor.getString(0);
                    if (cursor.getColumnCount() > 1 && !cursor.isNull(1)) size = cursor.getLong(1);
                }
            } catch (RuntimeException ignoredAgain) {
                // Fall through to the URI path.
            }
        }
        if (title == null || title.trim().isEmpty()) {
            String fallback = uri.getLastPathSegment();
            title = fallback == null || fallback.trim().isEmpty() ? "Comic" : fallback;
        }
        String mimeType;
        try {
            mimeType = resolver.getType(uri);
        } catch (RuntimeException ignored) {
            mimeType = null;
        }
        return new DocumentInfo(title, size, modified, mimeType);
    }

    public static String stripSupportedExtension(String value) {
        if (value == null) return "";
        String lower = value.toLowerCase(Locale.ROOT);
        for (String extension : new String[]{".cbz", ".zip", ".epub", ".pdf"}) {
            if (lower.endsWith(extension)) {
                return value.substring(0, value.length() - extension.length());
            }
        }
        return value;
    }
}
