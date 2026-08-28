package com.localtools.comicviewer.archive;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import com.localtools.comicviewer.R;
import com.localtools.comicviewer.document.ComicDocument;
import com.localtools.comicviewer.document.DocumentInfo;
import com.localtools.comicviewer.model.PageInfo;
import com.localtools.comicviewer.model.SeriesMetadata;
import com.localtools.comicviewer.util.CacheKey;
import com.localtools.comicviewer.util.InputLimits;
import com.localtools.comicviewer.util.InputLimits.LimitExceededException;
import com.localtools.comicviewer.util.NaturalOrder;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/** Random-access CBZ or image-page EPUB session with progressive background indexing. */
public final class ComicArchive implements ComicDocument {
    private static final Set<String> IMAGE_EXTENSIONS = new HashSet<>(Arrays.asList(
            ".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp"));

    private final Uri uri;
    private final String key;
    private final String title;
    private final long documentSize;
    private final long documentModified;
    private final ZipFile zipFile;
    private final ParcelFileDescriptor descriptor;
    private final File cachedArchive;
    private final List<String> pageNames;
    private final String coverName;
    private final boolean rightToLeft;
    private final SeriesMetadata seriesMetadata;
    private final AtomicReferenceArray<PageInfo> pageInfo;
    private final boolean[] indexed;
    private final List<PageInfo> pagesView;
    private volatile boolean indexComplete;
    private volatile int indexedPages;
    private volatile boolean closed;

    private ComicArchive(
            Uri uri,
            DocumentInfo document,
            ZipFile zipFile,
            ParcelFileDescriptor descriptor,
            File cachedArchive,
            List<String> pageNames,
            String coverName,
            boolean rightToLeft,
            SeriesMetadata seriesMetadata,
            String parsedTitle,
            List<PageInfo> initialPages,
            boolean[] indexed,
            int indexedPages,
            boolean indexComplete) {
        this.uri = uri;
        this.key = uri.toString();
        this.title = parsedTitle == null || parsedTitle.trim().isEmpty()
                ? document.title : parsedTitle.trim();
        this.documentSize = document.size;
        this.documentModified = document.modified;
        this.zipFile = zipFile;
        this.descriptor = descriptor;
        this.cachedArchive = cachedArchive;
        this.pageNames = Collections.unmodifiableList(new ArrayList<>(pageNames));
        this.coverName = coverName == null || coverName.isEmpty() ? pageNames.get(0) : coverName;
        this.rightToLeft = rightToLeft;
        this.seriesMetadata = seriesMetadata == null ? SeriesMetadata.EMPTY : seriesMetadata;
        this.pageInfo = new AtomicReferenceArray<>(initialPages.size());
        for (int index = 0; index < initialPages.size(); index++) {
            this.pageInfo.set(index, initialPages.get(index));
        }
        this.indexed = indexed;
        this.indexedPages = indexedPages;
        this.indexComplete = indexComplete;
        this.pagesView = Collections.unmodifiableList(new AbstractList<>() {
            @Override
            public PageInfo get(int index) {
                return ComicArchive.this.pageInfo.get(index);
            }

            @Override
            public int size() {
                return ComicArchive.this.pageInfo.length();
            }
        });
    }

    public static ComicArchive open(
            Context context,
            Uri uri,
            DocumentInfo document,
            boolean epubExpected,
            int preferredPage,
            List<PageInfo> cachedPages,
            long cachedDocumentSize,
            long cachedDocumentModified,
            ProgressCallback callback) throws IOException {
        var resolver = context.getContentResolver();
        update(callback, context.getString(R.string.reader_opening_named, document.title));

        ParcelFileDescriptor descriptor = null;
        File cachedArchive = null;
        ZipFile zipFile = null;
        try {
            InputLimits.validateDocumentSize(document.size);
            descriptor = resolver.openFileDescriptor(uri, "r");
            if (descriptor != null) {
                long descriptorSize = descriptor.getStatSize();
                InputLimits.validateDocumentSize(descriptorSize);
                if (descriptorSize < 0L) {
                    descriptor.close();
                    descriptor = null;
                } else {
                    try {
                        zipFile = new ZipFile("/proc/self/fd/" + descriptor.getFd());
                    } catch (IOException ignored) {
                        descriptor.close();
                        descriptor = null;
                    }
                }
            }

            if (zipFile == null) {
                update(callback, context.getString(R.string.reader_preparing_archive));
                File archiveCache = new File(context.getCacheDir(), "archive_sessions");
                if (!archiveCache.exists() && !archiveCache.mkdirs()) {
                    throw new IOException(context.getString(R.string.error_archive_cache_create));
                }
                cachedArchive = new File(archiveCache, CacheKey.digest(uri.toString()) + ".zip");
                try (InputStream source = resolver.openInputStream(uri)) {
                    if (source == null) {
                        throw new IOException(context.getString(R.string.error_document_unreadable));
                    }
                    InputLimits.copyAtomically(
                            source, cachedArchive, InputLimits.MAX_DOCUMENT_BYTES,
                            InputLimits.Reason.DOCUMENT_BYTES,
                            context.getString(R.string.error_archive_cache_replace),
                            context.getString(R.string.error_archive_cache_finish));
                }
                zipFile = new ZipFile(cachedArchive);
            }
            InputLimits.validateArchive(zipFile);

            ArrayList<String> names;
            String coverName;
            String parsedTitle = "";
            boolean rightToLeft = false;
            SeriesMetadata seriesMetadata = SeriesMetadata.EMPTY;
            boolean isEpub = EpubParser.looksLikeEpub(zipFile);
            if (epubExpected && !isEpub) {
                throw new IOException(context.getString(R.string.error_invalid_epub));
            }
            if (isEpub) {
                try {
                    EpubParser.Result epub = EpubParser.parse(zipFile);
                    names = new ArrayList<>(epub.pageNames);
                    coverName = epub.coverName;
                    parsedTitle = epub.title;
                    rightToLeft = epub.rightToLeft;
                    seriesMetadata = new SeriesMetadata(epub.seriesName, epub.seriesNumber);
                } catch (EpubParser.EpubException exception) {
                    throw epubError(context, exception);
                }
            } else {
                names = collectPages(zipFile);
                coverName = names.isEmpty() ? "" : names.get(0);
                seriesMetadata = ComicInfoParser.parse(zipFile);
            }
            if (names.isEmpty()) throw new IOException(context.getString(R.string.error_no_pages));

            boolean cacheValid = fingerprintMatches(
                    document, cachedDocumentSize, cachedDocumentModified) &&
                    cachedIndexMatches(cachedPages, names);
            ArrayList<PageInfo> initial = new ArrayList<>(names.size());
            boolean[] indexed = new boolean[names.size()];
            int indexedCount;
            if (cacheValid) {
                initial.addAll(cachedPages);
                Arrays.fill(indexed, true);
                indexedCount = names.size();
            } else {
                int target = clamp(preferredPage, 0, names.size() - 1);
                PageInfo targetInfo = decodePageInfo(
                        context, zipFile, names.get(target));
                for (String name : names) {
                    initial.add(new PageInfo(name, targetInfo.width, targetInfo.height));
                }
                initial.set(target, targetInfo);
                indexed[target] = true;
                indexedCount = 1;
            }
            update(callback, context.getString(R.string.reader_ready));
            return new ComicArchive(uri, document, zipFile, descriptor, cachedArchive,
                    names, coverName, rightToLeft, seriesMetadata, parsedTitle,
                    initial, indexed, indexedCount, cacheValid);
        } catch (LimitExceededException exception) {
            closeQuietly(zipFile, descriptor);
            deleteQuietly(cachedArchive);
            throw limitError(context, exception);
        } catch (ZipException exception) {
            closeQuietly(zipFile, descriptor);
            deleteQuietly(cachedArchive);
            throw new IOException(context.getString(
                    epubExpected ? R.string.error_invalid_epub : R.string.error_invalid_archive),
                    exception);
        } catch (IOException | RuntimeException exception) {
            closeQuietly(zipFile, descriptor);
            deleteQuietly(cachedArchive);
            if (exception instanceof IOException ioException) throw ioException;
            throw new IOException(context.getString(R.string.error_open_generic), exception);
        }
    }

    @Override
    public void buildPageIndex(
            Context context, ProgressCallback progress, IndexCallback callback) throws IOException {
        if (Thread.currentThread().isInterrupted() || callback.isCancelled()) return;
        if (indexComplete) {
            callback.onComplete(snapshotPages());
            return;
        }
        for (int index = 0; index < count(); index++) {
            if (Thread.currentThread().isInterrupted() || callback.isCancelled()) return;
            ensureOpen();
            if (!indexed[index]) {
                PageInfo decoded = decodePageInfo(context, zipFile, pageNames.get(index));
                pageInfo.set(index, decoded);
                indexed[index] = true;
                indexedPages++;
            }
            if (index == 0 || index % 12 == 0 || index == count() - 1) {
                update(progress, context.getString(
                        R.string.reader_reading_page_information, index + 1, count()));
                callback.onProgress(indexedPages, count());
                callback.onPagesUpdated();
            }
        }
        if (Thread.currentThread().isInterrupted() || callback.isCancelled()) return;
        indexComplete = true;
        callback.onComplete(snapshotPages());
    }

    @Override
    public Uri uri() {
        return uri;
    }

    @Override
    public String key() {
        return key;
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    public long documentSize() {
        return documentSize;
    }

    @Override
    public long documentModified() {
        return documentModified;
    }

    @Override
    public int count() {
        return pageInfo.length();
    }

    @Override
    public int indexedPages() {
        return indexedPages;
    }

    @Override
    public boolean isIndexComplete() {
        return indexComplete;
    }

    @Override
    public boolean suggestedRightToLeft() {
        return rightToLeft;
    }

    @Override
    public SeriesMetadata seriesMetadata() {
        return seriesMetadata;
    }

    @Override
    public List<PageInfo> pages() {
        return pagesView;
    }

    @Override
    public List<PageInfo> snapshotPages() {
        ArrayList<PageInfo> result = new ArrayList<>(count());
        for (int index = 0; index < count(); index++) result.add(pageInfo.get(index));
        return result;
    }

    @Override
    public PageInfo page(int index) {
        return pageInfo.get(index);
    }

    @Override
    public synchronized InputStream openPage(int index) throws IOException {
        ensureOpen();
        ZipEntry entry = zipFile.getEntry(pageNames.get(index));
        if (entry == null) throw new IOException("Page entry is missing.");
        InputLimits.validatePageEntry(entry);
        return new BufferedInputStream(InputLimits.bounded(
                zipFile.getInputStream(entry), InputLimits.MAX_PAGE_BYTES,
                InputLimits.Reason.PAGE_BYTES), 128 * 1024);
    }

    @Override
    public synchronized void extractPage(int index, File destination) throws IOException {
        extractEntry(pageNames.get(index), destination);
    }

    @Override
    public synchronized void extractCover(File destination) throws IOException {
        extractEntry(coverName, destination);
    }

    private void extractEntry(String entryName, File destination) throws IOException {
        ensureOpen();
        ZipEntry entry = zipFile.getEntry(entryName);
        if (entry == null) throw new IOException("Page entry is missing.");
        InputLimits.validatePageEntry(entry);
        try (InputStream source = new BufferedInputStream(
                zipFile.getInputStream(entry), 128 * 1024)) {
            InputLimits.copyAtomically(
                    source, destination, InputLimits.MAX_PAGE_BYTES,
                    InputLimits.Reason.PAGE_BYTES,
                    "Could not refresh the page cache.",
                    "Could not finish caching a page.");
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        closeQuietly(zipFile, descriptor);
        deleteQuietly(cachedArchive);
    }

    private synchronized void ensureOpen() throws IOException {
        if (closed) throw new IOException("The archive has been closed.");
    }

    private static PageInfo decodePageInfo(Context context, ZipFile zipFile, String name)
            throws IOException {
        ZipEntry entry = zipFile.getEntry(name);
        if (entry == null) throw new IOException(context.getString(R.string.error_page_missing));
        InputLimits.validatePageEntry(entry);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream stream = new BufferedInputStream(InputLimits.bounded(
                zipFile.getInputStream(entry), InputLimits.MAX_PAGE_BYTES,
                InputLimits.Reason.PAGE_BYTES), 64 * 1024)) {
            BitmapFactory.decodeStream(stream, null, options);
        }
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            throw new IOException(context.getString(R.string.error_decode_image, name));
        }
        InputLimits.validateImageDimensions(options.outWidth, options.outHeight);
        return new PageInfo(name, options.outWidth, options.outHeight);
    }

    private static ArrayList<String> collectPages(ZipFile zipFile) throws IOException {
        ArrayList<String> names = new ArrayList<>();
        Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (entry.isDirectory() || name.startsWith("__MACOSX/") ||
                    baseName(name).startsWith("._")) continue;
            String lower = name.toLowerCase(Locale.ROOT);
            for (String extension : IMAGE_EXTENSIONS) {
                if (lower.endsWith(extension)) {
                    InputLimits.validatePageEntry(entry);
                    names.add(name);
                    InputLimits.validatePageCount(names.size());
                    break;
                }
            }
        }
        names.sort(NaturalOrder.INSTANCE);
        return names;
    }

    private static boolean cachedIndexMatches(List<PageInfo> cached, List<String> names) {
        if (cached == null || cached.size() != names.size() || cached.isEmpty()) return false;
        for (int index = 0; index < names.size(); index++) {
            PageInfo page = cached.get(index);
            if (!names.get(index).equals(page.name)) return false;
            try {
                InputLimits.validateImageDimensions(page.width, page.height);
            } catch (LimitExceededException exception) {
                return false;
            }
        }
        return true;
    }

    private static boolean fingerprintMatches(DocumentInfo current, long oldSize, long oldModified) {
        if (current.size >= 0L && oldSize >= 0L && current.size != oldSize) return false;
        return current.modified < 0L || oldModified < 0L || current.modified == oldModified;
    }

    private static IOException epubError(Context context, EpubParser.EpubException exception) {
        int message = switch (exception.code) {
            case UNSUPPORTED_LAYOUT -> R.string.error_epub_unsupported_layout;
            case UNSAFE_RESOURCE -> R.string.error_epub_unsafe_resource;
            case ENCRYPTED -> R.string.error_epub_encrypted;
            case NO_PAGES -> R.string.error_epub_no_pages;
            default -> R.string.error_invalid_epub;
        };
        return new IOException(context.getString(message), exception);
    }

    private static IOException limitError(Context context, LimitExceededException exception) {
        int message = switch (exception.reason) {
            case PAGE_COUNT -> R.string.error_too_many_pages;
            case PAGE_BYTES, IMAGE_DIMENSIONS -> R.string.error_page_too_large;
            default -> R.string.error_document_too_large;
        };
        return new IOException(context.getString(message), exception);
    }

    private static void update(ProgressCallback callback, String message) {
        if (callback != null) callback.update(message);
    }

    private static String baseName(String value) {
        int slash = value.lastIndexOf('/');
        return slash < 0 ? value : value.substring(slash + 1);
    }

    private static void closeQuietly(ZipFile zipFile, ParcelFileDescriptor descriptor) {
        try {
            if (zipFile != null) zipFile.close();
        } catch (IOException ignored) {
        }
        try {
            if (descriptor != null) descriptor.close();
        } catch (IOException ignored) {
        }
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists()) file.delete();
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
