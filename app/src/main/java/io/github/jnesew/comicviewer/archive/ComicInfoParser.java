package io.github.jnesew.comicviewer.archive;

import io.github.jnesew.comicviewer.model.SeriesMetadata;
import io.github.jnesew.comicviewer.model.ComicMetadata;
import io.github.jnesew.comicviewer.util.InputLimits;
import io.github.jnesew.comicviewer.util.SecureXml;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.parsers.ParserConfigurationException;

/** Bounded, XXE-safe reader for optional ComicInfo publication and page hints. */
public final class ComicInfoParser {
    private static final int MAX_XML_BYTES = 1024 * 1024;

    private ComicInfoParser() {
    }

    public static SeriesMetadata parse(ZipFile zipFile) {
        return parseMetadata(zipFile).series;
    }

    public static ComicMetadata parseMetadata(ZipFile zipFile) {
        try {
            InputLimits.validateArchive(zipFile);
            ZipEntry metadata = findMetadata(zipFile);
            if (metadata == null) return ComicMetadata.EMPTY;
            InputLimits.validateMetadataEntry(metadata, MAX_XML_BYTES);
            try (InputStream source = zipFile.getInputStream(metadata)) {
                return parseMetadata(source);
            }
        } catch (IOException | RuntimeException ignored) {
            return ComicMetadata.EMPTY;
        }
    }

    /** Does not close the caller-owned stream. Invalid optional metadata never blocks reading. */
    public static ComicMetadata parseMetadata(InputStream source) {
        try {
            Document document = SecureXml.parse(InputLimits.readAll(
                    source, MAX_XML_BYTES, InputLimits.Reason.METADATA_BYTES), false);
            Element root = document.getDocumentElement();
            if (root == null || !"comicinfo".equalsIgnoreCase(localName(root))) return ComicMetadata.EMPTY;
            Map<String, String> fields = new HashMap<>();
            List<ComicMetadata.Page> pages = new ArrayList<>();
            Set<Integer> images = new HashSet<>();
            for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (!(child instanceof Element element)) continue;
                String name = localName(element);
                if ("Pages".equals(name)) {
                    for (Node item = element.getFirstChild(); item != null; item = item.getNextSibling()) {
                        if (!(item instanceof Element page) || !"Page".equals(localName(page))) continue;
                        int image = ComicMetadata.integer(page.getAttribute("Image"), 0, InputLimits.MAX_PAGES - 1);
                        if (image < 0 || !images.add(image)) continue;
                        pages.add(new ComicMetadata.Page(image,
                                InputLimits.normalizeText(page.getAttribute("Type"), 64),
                                "true".equalsIgnoreCase(page.getAttribute("DoublePage")) ||
                                        "1".equals(page.getAttribute("DoublePage"))));
                        InputLimits.validatePageCount(pages.size());
                    }
                } else if (SUPPORTED_FIELDS.contains(name)) {
                    fields.putIfAbsent(name, element.getTextContent());
                }
            }
            return new ComicMetadata(fields, pages);
        } catch (IOException | ParserConfigurationException | SAXException | RuntimeException ignored) {
            return ComicMetadata.EMPTY;
        }
    }

    private static final Set<String> SUPPORTED_FIELDS = Set.of(
            "Title", "Series", "Number", "Volume", "Year", "Month", "Day", "Summary",
            "Writer", "Penciller", "Inker", "Colorist", "Letterer", "CoverArtist", "Editor",
            "Translator", "Publisher", "LanguageISO", "Manga");

    private static String localName(Element element) {
        return element.getLocalName() == null ? element.getNodeName() : element.getLocalName();
    }

    private static ZipEntry findMetadata(ZipFile zipFile) {
        Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory()) continue;
            String name = entry.getName().replace('\\', '/');
            int slash = name.lastIndexOf('/');
            String filename = slash < 0 ? name : name.substring(slash + 1);
            if ("comicinfo.xml".equals(filename.toLowerCase(Locale.ROOT))) return entry;
        }
        return null;
    }

}
