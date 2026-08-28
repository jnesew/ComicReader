package com.localtools.comicviewer.archive;

import com.localtools.comicviewer.model.SeriesMetadata;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ComicInfoParserTest {
    @Test
    public void readsSeriesAndIssueNumberCaseInsensitively() throws Exception {
        File archive = archive("Metadata/COMICINFO.XML",
                "<?xml version=\"1.0\"?><ComicInfo><Series>North Wind</Series>" +
                        "<Number>03</Number></ComicInfo>");
        try (ZipFile zip = new ZipFile(archive)) {
            SeriesMetadata metadata = ComicInfoParser.parse(zip);
            assertEquals("North Wind", metadata.name);
            assertEquals("03", metadata.number);
        } finally {
            Files.deleteIfExists(archive.toPath());
        }
    }

    @Test
    public void unsafeOptionalMetadataIsIgnored() throws Exception {
        File archive = archive("ComicInfo.xml",
                "<?xml version=\"1.0\"?><!DOCTYPE ComicInfo [" +
                        "<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>" +
                        "<ComicInfo><Series>&xxe;</Series></ComicInfo>");
        try (ZipFile zip = new ZipFile(archive)) {
            SeriesMetadata metadata = ComicInfoParser.parse(zip);
            assertFalse(metadata.hasSeries());
        } finally {
            Files.deleteIfExists(archive.toPath());
        }
    }

    @Test
    public void optionalSeriesFieldsAreNormalizedAndBounded() throws Exception {
        File archive = archive("ComicInfo.xml",
                "<?xml version=\"1.0\"?><ComicInfo><Series> Cafe\u0301\n" +
                        "x".repeat(600) + "</Series><Number> 2\tA </Number></ComicInfo>");
        try (ZipFile zip = new ZipFile(archive)) {
            SeriesMetadata metadata = ComicInfoParser.parse(zip);
            assertTrue(metadata.name.startsWith("Café x"));
            assertEquals(512, metadata.name.codePointCount(0, metadata.name.length()));
            assertEquals("2 A", metadata.number);
        } finally {
            Files.deleteIfExists(archive.toPath());
        }
    }

    private static File archive(String path, String xml) throws Exception {
        File file = Files.createTempFile("comicviewer-comicinfo-", ".cbz").toFile();
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(file))) {
            output.putNextEntry(new ZipEntry(path));
            output.write(xml.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            output.putNextEntry(new ZipEntry("001.jpg"));
            output.write(new byte[]{1});
            output.closeEntry();
        }
        return file;
    }
}
