package io.github.jnesew.comicviewer.archive;

import io.github.jnesew.comicviewer.model.ComicMetadata;

import io.github.jnesew.comicviewer.model.SeriesMetadata;

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

    @Test
    public void publicationAndPageHintsAreAvailableWithoutReplacingActualPages() {
        var metadata = parse("<Title> Embedded title </Title><Series>A</Series><Number>2</Number>" +
                "<Volume>2024</Volume><Year>2024</Year><Month>2</Month><Day>29</Day>" +
                "<Summary>Some story</Summary><Publisher>Publisher</Publisher><LanguageISO>fi</LanguageISO>" +
                "<Manga>YesAndRightToLeft</Manga><Writer>Writer</Writer><Penciller>Artist</Penciller>" +
                "<Inker>Ink</Inker><Colorist>Color</Colorist><Letterer>Letters</Letterer>" +
                "<CoverArtist>Cover</CoverArtist><Editor>Editor</Editor><Translator>Translator</Translator>" +
                "<Pages><Page Image=\"2\" Type=\"FrontCover\" DoublePage=\"true\"/>" +
                "<Page Image=\"0\" Type=\"Story\"/></Pages>");
        assertEquals("Embedded title", metadata.title);
        assertEquals("A", metadata.series.name);
        assertEquals("2", metadata.series.number);
        assertEquals(2024, metadata.volume);
        assertEquals(2024, metadata.year); assertEquals(2, metadata.month); assertEquals(29, metadata.day);
        assertEquals("Some story", metadata.summary);
        assertEquals("Publisher", metadata.publisher); assertEquals("fi", metadata.language);
        assertEquals(8, metadata.creators.size()); assertEquals("Artist", metadata.creators.get("Penciller"));
        assertEquals(ComicMetadata.Manga.RIGHT_TO_LEFT, metadata.manga);
        assertEquals(2, metadata.coverPage(3)); assertEquals(0, metadata.coverPage(2));
        assertTrue(metadata.pages.get(0).doublePage());
    }

    @Test public void malformedHintsStayOptionalAndDoNotInventDirectionOrDates() {
        var metadata = parse("<Title>Valid</Title><Volume>99999999999999999</Volume>" +
                "<Year>2023</Year><Month>2</Month><Day>29</Day><Manga>Yes</Manga>" +
                "<Pages><Page Image=\"-1\" Type=\"FrontCover\"/><Page Image=\"999999\"/>" +
                "<Page Image=\"1\" DoublePage=\"1\"/><Page Image=\"1\" Type=\"FrontCover\"/></Pages>");
        assertEquals("Valid", metadata.title); assertEquals(-1, metadata.volume);
        assertEquals(2023, metadata.year); assertEquals(-1, metadata.day);
        assertEquals(ComicMetadata.Manga.YES, metadata.manga);
        assertEquals(1, metadata.pages.size()); assertTrue(metadata.pages.get(0).doublePage());
        assertEquals(0, metadata.coverPage(3));
        assertEquals(-1, parse("<Year>2024</Year>").month);
        assertEquals("", parse("<Unknown><Title>Nested</Title></Unknown>").title);
    }

    @Test public void optionalTextAndXmlAreBounded() {
        assertEquals(4096, parse("<Summary>" + "x".repeat(6000) + "</Summary>").summary.length());
        var metadata = parse("<Title>" + "x".repeat(1024 * 1024) + "</Title>");
        assertEquals("", metadata.title);
    }

    private static ComicMetadata parse(String body) {
        return ComicInfoParser.parseMetadata(new java.io.ByteArrayInputStream(
                ("<ComicInfo>" + body + "</ComicInfo>").getBytes(StandardCharsets.UTF_8)));
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
