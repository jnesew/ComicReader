package io.github.jnesew.comicviewer.document;

import android.content.Context;
import android.net.Uri;

import io.github.jnesew.comicviewer.R;
import io.github.jnesew.comicviewer.archive.ComicArchive;
import io.github.jnesew.comicviewer.model.PageInfo;
import io.github.jnesew.comicviewer.util.InputLimits;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;

/** Detects supported comic containers without relying on a provider's MIME declaration alone. */
public final class ComicDocumentFactory {
    private enum Kind { ARCHIVE, EPUB, PDF }

    private ComicDocumentFactory() {
    }

    public static DocumentInfo describe(Context context, Uri uri) {
        return DocumentInfo.describe(context.getContentResolver(), uri);
    }

    /** Cheap provider hint used while recursively walking a user-selected document tree. */
    public static boolean isSupportedHint(String displayName, String mimeType) {
        String name = displayName == null ? "" : displayName.toLowerCase(Locale.ROOT);
        String mime = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        return name.endsWith(".cbz") || name.endsWith(".zip") || name.endsWith(".epub") ||
                name.endsWith(".pdf") || "application/vnd.comicbook+zip".equals(mime) ||
                "application/x-cbz".equals(mime) || "application/zip".equals(mime) ||
                "application/epub+zip".equals(mime) || "application/pdf".equals(mime);
    }

    public static ComicDocument open(
            Context context,
            Uri uri,
            DocumentInfo document,
            int preferredPage,
            List<PageInfo> cachedPages,
            long cachedDocumentSize,
            long cachedDocumentModified,
            ComicDocument.ProgressCallback callback) throws IOException {
        Kind kind = detect(context, uri, document);
        if (kind == Kind.PDF) {
            return PdfComicDocument.open(context, uri, document, preferredPage, cachedPages,
                    cachedDocumentSize, cachedDocumentModified, callback);
        }
        return ComicArchive.open(context, uri, document, kind == Kind.EPUB,
                preferredPage, cachedPages, cachedDocumentSize, cachedDocumentModified, callback);
    }

    private static Kind detect(Context context, Uri uri, DocumentInfo document) throws IOException {
        String mime = document.mimeType.toLowerCase(Locale.ROOT);
        String name = document.displayName.toLowerCase(Locale.ROOT);
        byte[] header;
        IOException headerFailure = null;
        try {
            header = readHeader(context, uri);
        } catch (IOException exception) {
            header = new byte[0];
            headerFailure = exception;
        }
        if (startsWith(header, "%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
            return Kind.PDF;
        }
        boolean zipHeader = header.length >= 4 && header[0] == 'P' && header[1] == 'K' &&
                ((header[2] == 3 && header[3] == 4) ||
                        (header[2] == 5 && header[3] == 6) ||
                        (header[2] == 7 && header[3] == 8));
        boolean epub = "application/epub+zip".equals(mime) || name.endsWith(".epub");
        if (zipHeader) return epub ? Kind.EPUB : Kind.ARCHIVE;
        if ("application/pdf".equals(mime) || name.endsWith(".pdf")) return Kind.PDF;
        if (epub || "application/zip".equals(mime) ||
                "application/vnd.comicbook+zip".equals(mime) ||
                "application/x-cbz".equals(mime) || name.endsWith(".cbz") ||
                name.endsWith(".zip")) return epub ? Kind.EPUB : Kind.ARCHIVE;
        if (headerFailure != null) throw headerFailure;
        throw new IOException(context.getString(R.string.error_unsupported_file_type));
    }

    private static byte[] readHeader(Context context, Uri uri) throws IOException {
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) {
                throw new IOException(context.getString(R.string.error_document_unreadable));
            }
            return InputLimits.readPrefix(input, 8);
        } catch (SecurityException exception) {
            throw new IOException(context.getString(R.string.error_document_unreadable), exception);
        }
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) return false;
        for (int index = 0; index < prefix.length; index++) {
            if (value[index] != prefix[index]) return false;
        }
        return true;
    }
}
