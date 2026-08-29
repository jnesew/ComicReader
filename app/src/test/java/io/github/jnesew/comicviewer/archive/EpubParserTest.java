package io.github.jnesew.comicviewer.archive;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class EpubParserTest {
    @Test
    public void fixedLayoutUsesSpineTitleCoverAndRtlProgression() throws Exception {
        LinkedHashMap<String, byte[]> entries = baseEntries(packageDocument(
                "<dc:title>Panel Story</dc:title>" +
                        "<meta property=\"rendition:layout\">pre-paginated</meta>",
                "<item id=\"cover\" href=\"images/cover.jpg\" media-type=\"image/jpeg\" properties=\"cover-image\"/>" +
                        "<item id=\"p1\" href=\"text/1.xhtml\" media-type=\"application/xhtml+xml\"/>" +
                        "<item id=\"p2\" href=\"text/2.xhtml\" media-type=\"application/xhtml+xml\"/>",
                "<spine page-progression-direction=\"rtl\"><itemref idref=\"p1\"/><itemref idref=\"p2\"/></spine>"));
        entries.put("OEBPS/text/1.xhtml", wrapper("../images/001.jpg"));
        entries.put("OEBPS/text/2.xhtml", wrapper("../images/002.jpg"));
        entries.put("OEBPS/images/cover.jpg", new byte[]{1});
        entries.put("OEBPS/images/001.jpg", new byte[]{2});
        entries.put("OEBPS/images/002.jpg", new byte[]{3});

        EpubParser.Result result = parse(entries);
        assertEquals("Panel Story", result.title);
        assertEquals(java.util.List.of(
                "OEBPS/images/001.jpg", "OEBPS/images/002.jpg"), result.pageNames);
        assertEquals("OEBPS/images/cover.jpg", result.coverName);
        assertTrue(result.rightToLeft);
        assertTrue(result.fixedLayout);
    }

    @Test
    public void imageOnlySpineIsAcceptedAsFallback() throws Exception {
        LinkedHashMap<String, byte[]> entries = baseEntries(packageDocument(
                "<dc:title>Image Fallback</dc:title>",
                "<item id=\"p1\" href=\"images/1.png\" media-type=\"image/png\"/>" +
                        "<item id=\"p2\" href=\"images/2.png\" media-type=\"image/png\"/>",
                "<spine><itemref idref=\"p1\"/><itemref idref=\"p2\"/></spine>"));
        entries.put("OEBPS/images/1.png", new byte[]{1});
        entries.put("OEBPS/images/2.png", new byte[]{2});

        EpubParser.Result result = parse(entries);
        assertEquals(java.util.List.of(
                "OEBPS/images/1.png", "OEBPS/images/2.png"), result.pageNames);
        assertEquals("OEBPS/images/1.png", result.coverName);
        assertFalse(result.fixedLayout);
        assertFalse(result.rightToLeft);
    }

    @Test
    public void epubThreeSeriesCollectionAndPositionAreRead() throws Exception {
        LinkedHashMap<String, byte[]> entries = baseEntries(packageDocument(
                "<dc:title>Issue Two</dc:title>" +
                        "<meta property=\"belongs-to-collection\" id=\"series\">" +
                        "River Scouts</meta>" +
                        "<meta refines=\"#series\" property=\"collection-type\">series</meta>" +
                        "<meta refines=\"#series\" property=\"group-position\">2</meta>",
                "<item id=\"p1\" href=\"images/1.png\" media-type=\"image/png\"/>",
                "<spine><itemref idref=\"p1\"/></spine>"));
        entries.put("OEBPS/images/1.png", new byte[]{1});

        EpubParser.Result result = parse(entries);
        assertEquals("River Scouts", result.seriesName);
        assertEquals("2", result.seriesNumber);
    }

    @Test
    public void calibreSeriesFallbackIsRead() throws Exception {
        LinkedHashMap<String, byte[]> entries = baseEntries(packageDocument(
                "<dc:title>Issue 2.5</dc:title>" +
                        "<meta name=\"calibre:series\" content=\"Moon Patrol\"/>" +
                        "<meta name=\"calibre:series_index\" content=\"2.5\"/>",
                "<item id=\"p1\" href=\"images/1.png\" media-type=\"image/png\"/>",
                "<spine><itemref idref=\"p1\"/></spine>"));
        entries.put("OEBPS/images/1.png", new byte[]{1});

        EpubParser.Result result = parse(entries);
        assertEquals("Moon Patrol", result.seriesName);
        assertEquals("2.5", result.seriesNumber);
    }

    @Test
    public void providerMetadataIsNormalizedAndBoundedBeforeUse() throws Exception {
        String longTitle = " Cafe\u0301\n" + "x".repeat(600);
        String longSeries = " River\tScouts " + "y".repeat(600);
        LinkedHashMap<String, byte[]> entries = baseEntries(packageDocument(
                "<dc:title>" + longTitle + "</dc:title>" +
                        "<meta name=\"calibre:series\" content=\"" + longSeries + "\"/>" +
                        "<meta name=\"calibre:series_index\" content=\" 03\n \"/>",
                "<item id=\"p1\" href=\"images/1.png\" media-type=\"image/png\"/>",
                "<spine><itemref idref=\"p1\"/></spine>"));
        entries.put("OEBPS/images/1.png", new byte[]{1});

        EpubParser.Result result = parse(entries);
        assertTrue(result.title.startsWith("Café x"));
        assertEquals(512, result.title.codePointCount(0, result.title.length()));
        assertTrue(result.seriesName.startsWith("River Scouts "));
        assertEquals(512, result.seriesName.codePointCount(0, result.seriesName.length()));
        assertEquals("03", result.seriesNumber);
    }

    @Test
    public void singleImageWrapperIsAcceptedAsFallback() throws Exception {
        EpubParser.Result result = parse(oneWrappedPage("images/page.jpg"));
        assertEquals(java.util.List.of("OEBPS/images/page.jpg"), result.pageNames);
        assertFalse(result.fixedLayout);
    }

    @Test
    public void reflowableTextAlongsideImageIsRejected() throws Exception {
        LinkedHashMap<String, byte[]> entries = oneWrappedPage("images/page.jpg");
        entries.put("OEBPS/page.xhtml", (
                "<?xml version=\"1.0\"?><html xmlns=\"http://www.w3.org/1999/xhtml\">" +
                        "<body><p>Visible chapter text</p><img src=\"images/page.jpg\"/></body></html>")
                .getBytes(StandardCharsets.UTF_8));
        expect(entries, EpubParser.ErrorCode.UNSUPPORTED_LAYOUT);
    }

    @Test
    public void layeredOrRepeatedImagesAreRejected() throws Exception {
        LinkedHashMap<String, byte[]> entries = oneWrappedPage("images/page.jpg");
        entries.put("OEBPS/page.xhtml", (
                "<?xml version=\"1.0\"?><html xmlns=\"http://www.w3.org/1999/xhtml\">" +
                        "<body><img src=\"images/page.jpg\"/><img src=\"images/page.jpg\"/>" +
                        "</body></html>").getBytes(StandardCharsets.UTF_8));
        expect(entries, EpubParser.ErrorCode.UNSUPPORTED_LAYOUT);
    }

    @Test
    public void remoteAndTraversalResourcesAreRejected() throws Exception {
        LinkedHashMap<String, byte[]> remote = oneWrappedPage("images/page.jpg");
        remote.put("OEBPS/page.xhtml", wrapper("https://example.test/page.jpg"));
        expect(remote, EpubParser.ErrorCode.UNSAFE_RESOURCE);

        LinkedHashMap<String, byte[]> traversal = oneWrappedPage("images/page.jpg");
        traversal.put("OEBPS/page.xhtml", wrapper("../../outside.jpg"));
        expect(traversal, EpubParser.ErrorCode.UNSAFE_RESOURCE);
    }

    @Test
    public void encryptedComicImageIsRejectedWithoutRejectingMetadataAlone() throws Exception {
        LinkedHashMap<String, byte[]> entries = oneWrappedPage("images/page.jpg");
        entries.put("META-INF/encryption.xml", (
                "<?xml version=\"1.0\"?><encryption xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">" +
                        "<EncryptedData><CipherData><CipherReference URI=\"OEBPS/images/page.jpg\"/>" +
                        "</CipherData></EncryptedData></encryption>")
                .getBytes(StandardCharsets.UTF_8));
        expect(entries, EpubParser.ErrorCode.ENCRYPTED);
    }

    @Test
    public void documentTypeDeclarationsAreRejected() throws Exception {
        LinkedHashMap<String, byte[]> entries = oneWrappedPage("images/page.jpg");
        entries.put("OEBPS/page.xhtml", (
                "<?xml version=\"1.0\"?><!DOCTYPE html [<!ENTITY x \"bad\">]>" +
                        "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body>&x;" +
                        "<img src=\"images/page.jpg\"/></body></html>")
                .getBytes(StandardCharsets.UTF_8));
        expect(entries, EpubParser.ErrorCode.INVALID);
    }

    @Test
    public void inertHtml5DocumentTypeIsAccepted() throws Exception {
        LinkedHashMap<String, byte[]> entries = oneWrappedPage("images/page.jpg");
        entries.put("OEBPS/page.xhtml", (
                "<?xml version=\"1.0\"?><!DOCTYPE html>" +
                        "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body>" +
                        "<img src=\"images/page.jpg\"/></body></html>")
                .getBytes(StandardCharsets.UTF_8));
        EpubParser.Result result = parse(entries);
        assertEquals(java.util.List.of("OEBPS/images/page.jpg"), result.pageNames);
    }

    private static EpubParser.Result parse(Map<String, byte[]> entries) throws Exception {
        File file = write(entries);
        try (ZipFile zip = new ZipFile(file)) {
            assertTrue(EpubParser.looksLikeEpub(zip));
            return EpubParser.parse(zip);
        } finally {
            Files.deleteIfExists(file.toPath());
        }
    }

    private static void expect(Map<String, byte[]> entries, EpubParser.ErrorCode code)
            throws Exception {
        try {
            parse(entries);
            fail("Expected EPUB parser error " + code);
        } catch (EpubParser.EpubException exception) {
            assertEquals(code, exception.code);
        }
    }

    private static LinkedHashMap<String, byte[]> oneWrappedPage(String imagePath) {
        LinkedHashMap<String, byte[]> entries = baseEntries(packageDocument(
                "<dc:title>One Page</dc:title>",
                "<item id=\"p1\" href=\"page.xhtml\" media-type=\"application/xhtml+xml\"/>",
                "<spine><itemref idref=\"p1\"/></spine>"));
        entries.put("OEBPS/page.xhtml", wrapper(imagePath));
        entries.put("OEBPS/" + imagePath, new byte[]{1});
        return entries;
    }

    private static LinkedHashMap<String, byte[]> baseEntries(String packageXml) {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("mimetype", "application/epub+zip".getBytes(StandardCharsets.US_ASCII));
        entries.put("META-INF/container.xml", (
                "<?xml version=\"1.0\"?><container version=\"1.0\" " +
                        "xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">" +
                        "<rootfiles><rootfile full-path=\"OEBPS/package.opf\" " +
                        "media-type=\"application/oebps-package+xml\"/></rootfiles></container>")
                .getBytes(StandardCharsets.UTF_8));
        entries.put("OEBPS/package.opf", packageXml.getBytes(StandardCharsets.UTF_8));
        return entries;
    }

    private static String packageDocument(String metadata, String manifest, String spine) {
        return "<?xml version=\"1.0\"?><package version=\"3.0\" " +
                "xmlns=\"http://www.idpf.org/2007/opf\" " +
                "xmlns:dc=\"http://purl.org/dc/elements/1.1/\">" +
                "<metadata>" + metadata + "</metadata><manifest>" + manifest +
                "</manifest>" + spine + "</package>";
    }

    private static byte[] wrapper(String source) {
        return ("<?xml version=\"1.0\"?><html xmlns=\"http://www.w3.org/1999/xhtml\">" +
                "<head><title>Page</title></head><body><img alt=\"\" src=\"" + source +
                "\"/></body></html>").getBytes(StandardCharsets.UTF_8);
    }

    private static File write(Map<String, byte[]> entries) throws Exception {
        File file = Files.createTempFile("comicviewer-epub-", ".epub").toFile();
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(file))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return file;
    }
}
