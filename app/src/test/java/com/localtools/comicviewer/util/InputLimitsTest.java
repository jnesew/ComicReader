package com.localtools.comicviewer.util;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class InputLimitsTest {
    @Test
    public void archiveWithTooManyEntriesIsRejected() throws Exception {
        File archive = Files.createTempFile("comicviewer-entry-limit-", ".zip").toFile();
        try {
            try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(archive))) {
                for (int index = 0; index <= InputLimits.MAX_ARCHIVE_ENTRIES; index++) {
                    output.putNextEntry(new ZipEntry(String.format("entry-%05d", index)));
                    output.closeEntry();
                }
            }
            try (ZipFile zipFile = new ZipFile(archive)) {
                expect(InputLimits.Reason.ENTRY_COUNT,
                        () -> InputLimits.validateArchive(zipFile));
            }
        } finally {
            Files.deleteIfExists(archive.toPath());
        }
    }

    @Test
    public void declaredOversizedPageIsRejectedBeforeReading() throws Exception {
        ZipEntry entry = new ZipEntry("page.png");
        entry.setSize(InputLimits.MAX_PAGE_BYTES + 1L);
        entry.setCompressedSize(InputLimits.MAX_PAGE_BYTES + 1L);
        expect(InputLimits.Reason.PAGE_BYTES,
                () -> InputLimits.validatePageEntry(entry));
    }

    @Test
    public void suspiciousCompressionRatioIsRejectedAsAnEarlySignal() throws Exception {
        ZipEntry entry = new ZipEntry("page.png");
        entry.setSize(2L * 1024L * 1024L);
        entry.setCompressedSize(1L);
        expect(InputLimits.Reason.ARCHIVE_EXPANSION,
                () -> InputLimits.validatePageEntry(entry));
    }

    @Test
    public void unknownLengthStreamIsAuthoritativelyBounded() throws Exception {
        byte[] bytes = new byte[65];
        Arrays.fill(bytes, (byte) 7);
        expect(InputLimits.Reason.METADATA_BYTES, () -> InputLimits.readAll(
                new ByteArrayInputStream(bytes), 64, InputLimits.Reason.METADATA_BYTES));
    }

    @Test
    public void noProgressProviderStreamCannotLoopForever() throws Exception {
        InputStream stalled = new InputStream() {
            @Override
            public int read() {
                return 0;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) {
                return 0;
            }
        };
        try {
            InputLimits.readPrefix(stalled, 8);
            fail("Expected a no-progress provider error.");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("no progress"));
        }
    }

    @Test
    public void excessivePdfPageCountAndImageDimensionsAreRejected() throws Exception {
        expect(InputLimits.Reason.PAGE_COUNT,
                () -> InputLimits.validatePageCount(InputLimits.MAX_PAGES + 1));
        expect(InputLimits.Reason.IMAGE_DIMENSIONS,
                () -> InputLimits.validateImageDimensions(
                        InputLimits.MAX_IMAGE_DIMENSION + 1, 1));
        expect(InputLimits.Reason.IMAGE_DIMENSIONS,
                () -> InputLimits.validateImageDimensions(32_768, 32_768));
    }

    @Test
    public void fallbackBitmapSampleCapsDecodedPixelAllocation() throws Exception {
        int sample = InputLimits.boundedBitmapSample(16_384, 16_384, 1);
        long width = (16_384L + sample - 1L) / sample;
        long height = (16_384L + sample - 1L) / sample;
        assertTrue(sample > 1);
        assertTrue(width * height <= InputLimits.MAX_FALLBACK_BITMAP_PIXELS);
    }

    @Test
    public void oversizedAtomicCopyKeepsOldFileAndDeletesPartial() throws Exception {
        File directory = Files.createTempDirectory("comicviewer-copy-limit-").toFile();
        File destination = new File(directory, "document.bin");
        Files.write(destination.toPath(), "old".getBytes(StandardCharsets.UTF_8));
        byte[] source = new byte[33];
        expect(InputLimits.Reason.DOCUMENT_BYTES, () -> InputLimits.copyAtomically(
                new ByteArrayInputStream(source), destination, 32,
                InputLimits.Reason.DOCUMENT_BYTES, "replace", "finish"));
        assertArrayEquals("old".getBytes(StandardCharsets.UTF_8),
                Files.readAllBytes(destination.toPath()));
        assertFalse(new File(directory, "document.bin.partial").exists());
        Files.deleteIfExists(destination.toPath());
        Files.deleteIfExists(directory.toPath());
    }

    @Test
    public void interruptedAtomicCopyDeletesPartial() throws Exception {
        File directory = Files.createTempDirectory("comicviewer-copy-cancel-").toFile();
        File destination = new File(directory, "document.bin");
        Thread.currentThread().interrupt();
        try {
            InputLimits.copyAtomically(
                    new ByteArrayInputStream(new byte[8]), destination, 32,
                    InputLimits.Reason.DOCUMENT_BYTES, "replace", "finish");
            fail("Expected interrupted copy.");
        } catch (InterruptedIOException expected) {
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertFalse(destination.exists());
        assertFalse(new File(directory, "document.bin.partial").exists());
        Files.deleteIfExists(directory.toPath());
    }

    @Test
    public void metadataNormalizationIsUnicodeSafeAndCodePointBounded() {
        String normalized = InputLimits.normalizeText(
                "  Cafe\u0301\n\t\u0000  📚📚  ", 7);
        assertEquals("Café 📚📚", normalized);
        assertEquals(7, normalized.codePointCount(0, normalized.length()));

        String supplementary = InputLimits.normalizeText("📖📖📖", 2);
        assertEquals("📖📖", supplementary);
        assertEquals(4, supplementary.length());
    }

    private static void expect(InputLimits.Reason reason, ThrowingRunnable action)
            throws Exception {
        try {
            action.run();
            fail("Expected safety limit " + reason);
        } catch (InputLimits.LimitExceededException exception) {
            assertEquals(reason, exception.reason);
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
