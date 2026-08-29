package io.github.jnesew.comicviewer.util;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.text.Normalizer;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Shared fail-closed limits for all provider-controlled document and metadata input. */
public final class InputLimits {
    public static final long MAX_DOCUMENT_BYTES = 4L * 1024L * 1024L * 1024L;
    public static final long MAX_ARCHIVE_DECLARED_BYTES = 16L * 1024L * 1024L * 1024L;
    public static final long MAX_PAGE_BYTES = 256L * 1024L * 1024L;
    public static final int MAX_XML_BYTES = 4 * 1024 * 1024;
    public static final int MAX_ARCHIVE_ENTRIES = 50_000;
    public static final int MAX_PAGES = 20_000;
    public static final int MAX_IMAGE_DIMENSION = 262_144;
    public static final long MAX_IMAGE_PIXELS = 256L * 1024L * 1024L;
    public static final long MAX_FALLBACK_BITMAP_PIXELS = 32L * 1024L * 1024L;
    public static final int MAX_TITLE_CODE_POINTS = 512;
    public static final int MAX_SERIES_CODE_POINTS = 512;
    public static final int MAX_ISSUE_CODE_POINTS = 128;
    public static final int MAX_IDENTIFIER_CODE_POINTS = 8_192;
    public static final int MAX_STABLE_KEY_CODE_POINTS = 20_000;
    public static final int MAX_PATH_CODE_POINTS = 8_192;
    public static final int MAX_MIME_CODE_POINTS = 256;

    private static final long MIN_RATIO_CHECK_BYTES = 1024L * 1024L;
    private static final long MAX_COMPRESSION_RATIO = 1_000L;
    private static final int MAX_ZERO_LENGTH_READS = 16;

    public enum Reason {
        DOCUMENT_BYTES,
        PAGE_BYTES,
        ENTRY_COUNT,
        PAGE_COUNT,
        ARCHIVE_EXPANSION,
        IMAGE_DIMENSIONS,
        METADATA_BYTES,
        METADATA_TEXT
    }

    public static final class LimitExceededException extends IOException {
        private static final long serialVersionUID = 1L;
        public final Reason reason;

        public LimitExceededException(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }
    }

    private InputLimits() {
    }

    public static void validateDocumentSize(long size) throws LimitExceededException {
        if (size > MAX_DOCUMENT_BYTES) {
            throw exceeded(Reason.DOCUMENT_BYTES, "The document exceeds the safety limit.");
        }
    }

    public static void validateArchive(ZipFile zipFile) throws LimitExceededException {
        int count = 0;
        long declaredTotal = 0L;
        Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            validateArchiveEntryCount(++count);
            validateEntryName(entry.getName());
            if (entry.isDirectory()) continue;
            validateCompressionRatio(entry);
            long size = entry.getSize();
            if (size < 0L) continue;
            if (declaredTotal > MAX_ARCHIVE_DECLARED_BYTES - size) {
                throw exceeded(Reason.ARCHIVE_EXPANSION,
                        "The archive declares too much expanded data.");
            }
            declaredTotal += size;
        }
    }

    public static void validateArchiveEntryCount(int count) throws LimitExceededException {
        if (count > MAX_ARCHIVE_ENTRIES) {
            throw exceeded(Reason.ENTRY_COUNT, "The archive contains too many entries.");
        }
    }

    public static void validatePageCount(int count) throws LimitExceededException {
        if (count > MAX_PAGES) {
            throw exceeded(Reason.PAGE_COUNT, "The document contains too many pages.");
        }
    }

    public static void validatePageEntry(ZipEntry entry) throws LimitExceededException {
        validateEntryName(entry.getName());
        validateEntrySize(entry, MAX_PAGE_BYTES, Reason.PAGE_BYTES,
                "A page exceeds the safety limit.");
    }

    public static void validateMetadataEntry(ZipEntry entry, long maximum)
            throws LimitExceededException {
        validateEntryName(entry.getName());
        validateEntrySize(entry, maximum, Reason.METADATA_BYTES,
                "Document metadata exceeds the safety limit.");
    }

    public static void validateImageDimensions(int width, int height)
            throws LimitExceededException {
        if (width <= 0 || height <= 0 || width > MAX_IMAGE_DIMENSION ||
                height > MAX_IMAGE_DIMENSION || (long) width * height > MAX_IMAGE_PIXELS) {
            throw exceeded(Reason.IMAGE_DIMENSIONS,
                    "The page image dimensions exceed the safety limit.");
        }
    }

    public static int boundedBitmapSample(int width, int height, int requested)
            throws LimitExceededException {
        validateImageDimensions(width, height);
        int sample = Math.max(1, requested);
        while (decodedPixels(width, height, sample) > MAX_FALLBACK_BITMAP_PIXELS) {
            if (sample > 1 << 28) {
                throw exceeded(Reason.IMAGE_DIMENSIONS,
                        "The page image cannot be decoded safely.");
            }
            sample *= 2;
        }
        return sample;
    }

    public static InputStream bounded(InputStream source, long maximum, Reason reason) {
        return new BoundedInputStream(source, maximum, reason);
    }

    public static byte[] readAll(InputStream source, int maximum, Reason reason)
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximum, 64 * 1024));
        copy(source, output, maximum, reason);
        return output.toByteArray();
    }

    public static byte[] readPrefix(InputStream source, int maximum) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(maximum);
        byte[] buffer = new byte[Math.min(maximum, 16 * 1024)];
        int remaining = maximum;
        int zeroReads = 0;
        while (remaining > 0) {
            checkInterrupted();
            int count = source.read(buffer, 0, Math.min(buffer.length, remaining));
            if (count < 0) break;
            if (count == 0) {
                if (++zeroReads > MAX_ZERO_LENGTH_READS) {
                    throw new IOException("The document provider stream made no progress.");
                }
                continue;
            }
            zeroReads = 0;
            output.write(buffer, 0, count);
            remaining -= count;
        }
        return output.toByteArray();
    }

    public static long copy(InputStream source, OutputStream destination, long maximum, Reason reason)
            throws IOException {
        byte[] buffer = new byte[128 * 1024];
        long total = 0L;
        int zeroReads = 0;
        while (true) {
            checkInterrupted();
            int count = source.read(buffer);
            if (count < 0) break;
            if (count == 0) {
                if (++zeroReads > MAX_ZERO_LENGTH_READS) {
                    throw new IOException("The document provider stream made no progress.");
                }
                continue;
            }
            zeroReads = 0;
            if (total > maximum - count) throw streamLimit(reason);
            destination.write(buffer, 0, count);
            total += count;
        }
        checkInterrupted();
        return total;
    }

    public static void copyAtomically(
            InputStream source,
            File destination,
            long maximum,
            Reason reason,
            String replaceError,
            String finishError) throws IOException {
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create the private document cache.");
        }
        File partial = parent == null
                ? new File(destination.getPath() + ".partial")
                : new File(parent, destination.getName() + ".partial");
        boolean committed = false;
        try {
            if (partial.exists() && !partial.delete()) {
                throw new IOException("Could not clear an incomplete cache file.");
            }
            try (OutputStream output = new FileOutputStream(partial)) {
                copy(source, output, maximum, reason);
            }
            if (destination.exists() && !destination.delete()) {
                throw new IOException(replaceError);
            }
            if (!partial.renameTo(destination)) throw new IOException(finishError);
            committed = true;
        } finally {
            if (!committed && partial.exists()) partial.delete();
        }
    }

    public static String normalizeText(String value, int maximumCodePoints) {
        if (value == null || value.isEmpty() || maximumCodePoints <= 0) return "";
        String normalized;
        try {
            normalized = Normalizer.normalize(value, Normalizer.Form.NFC);
        } catch (RuntimeException exception) {
            normalized = value;
        }
        StringBuilder result = new StringBuilder(Math.min(normalized.length(), maximumCodePoints));
        boolean pendingSpace = false;
        int accepted = 0;
        for (int offset = 0; offset < normalized.length() && accepted < maximumCodePoints;) {
            int codePoint = normalized.codePointAt(offset);
            offset += Character.charCount(codePoint);
            int type = Character.getType(codePoint);
            boolean invalidScalar = codePoint >= Character.MIN_SURROGATE &&
                    codePoint <= Character.MAX_SURROGATE;
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) ||
                    Character.isISOControl(codePoint)) {
                pendingSpace = result.length() > 0;
                continue;
            }
            if (type == Character.FORMAT) continue;
            if (pendingSpace && accepted < maximumCodePoints) {
                result.append(' ');
                accepted++;
            }
            pendingSpace = false;
            if (accepted >= maximumCodePoints) break;
            result.appendCodePoint(invalidScalar ? 0xfffd : codePoint);
            accepted++;
        }
        return result.toString();
    }

    public static boolean isIdentifierAcceptable(String value) {
        return value != null && value.codePointCount(0, value.length()) <=
                MAX_IDENTIFIER_CODE_POINTS && value.indexOf('\0') < 0;
    }

    private static void validateEntryName(String name) throws LimitExceededException {
        if (!isIdentifierAcceptable(name)) {
            throw exceeded(Reason.METADATA_TEXT, "An archive entry name is too large.");
        }
    }

    private static void validateEntrySize(
            ZipEntry entry, long maximum, Reason reason, String message)
            throws LimitExceededException {
        long size = entry.getSize();
        if (size > maximum) throw exceeded(reason, message);
        validateCompressionRatio(entry);
    }

    private static void validateCompressionRatio(ZipEntry entry) throws LimitExceededException {
        long size = entry.getSize();
        long compressed = entry.getCompressedSize();
        if (size < MIN_RATIO_CHECK_BYTES || compressed < 0L) return;
        if (compressed == 0L || size / Math.max(1L, compressed) > MAX_COMPRESSION_RATIO) {
            throw exceeded(Reason.ARCHIVE_EXPANSION,
                    "An archive entry declares an unsafe compression ratio.");
        }
    }

    private static long decodedPixels(int width, int height, int sample) {
        long decodedWidth = (width + (long) sample - 1L) / sample;
        long decodedHeight = (height + (long) sample - 1L) / sample;
        return decodedWidth * decodedHeight;
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Document processing was cancelled.");
        }
    }

    private static LimitExceededException streamLimit(Reason reason) {
        String message = switch (reason) {
            case DOCUMENT_BYTES -> "The document exceeds the safety limit.";
            case PAGE_BYTES -> "A page exceeds the safety limit.";
            case METADATA_BYTES -> "Document metadata exceeds the safety limit.";
            default -> "Document input exceeds the safety limit.";
        };
        return exceeded(reason, message);
    }

    private static LimitExceededException exceeded(Reason reason, String message) {
        return new LimitExceededException(reason, message);
    }

    private static final class BoundedInputStream extends FilterInputStream {
        private final long maximum;
        private final Reason reason;
        private long total;

        private BoundedInputStream(InputStream source, long maximum, Reason reason) {
            super(source);
            this.maximum = maximum;
            this.reason = reason;
        }

        @Override
        public int read() throws IOException {
            checkInterrupted();
            int value = super.read();
            if (value >= 0) add(1L);
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            checkInterrupted();
            int count = super.read(buffer, offset, length);
            if (count > 0) add(count);
            return count;
        }

        private void add(long count) throws LimitExceededException {
            if (total > maximum - count) throw streamLimit(reason);
            total += count;
        }
    }
}
