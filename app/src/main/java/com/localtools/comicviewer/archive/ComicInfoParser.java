package com.localtools.comicviewer.archive;

import com.localtools.comicviewer.model.SeriesMetadata;
import com.localtools.comicviewer.util.InputLimits;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXParseException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

/** Bounded, XXE-safe reader for the Series and Number fields in ComicInfo.xml. */
public final class ComicInfoParser {
    private static final int MAX_XML_BYTES = 1024 * 1024;

    private ComicInfoParser() {
    }

    public static SeriesMetadata parse(ZipFile zipFile) {
        try {
            InputLimits.validateArchive(zipFile);
        } catch (InputLimits.LimitExceededException exception) {
            return SeriesMetadata.EMPTY;
        }
        ZipEntry metadata = findMetadata(zipFile);
        if (metadata == null) return SeriesMetadata.EMPTY;
        try (InputStream source = zipFile.getInputStream(metadata)) {
            InputLimits.validateMetadataEntry(metadata, MAX_XML_BYTES);
            byte[] bytes = InputLimits.readAll(
                    source, MAX_XML_BYTES, InputLimits.Reason.METADATA_BYTES);
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature(
                    "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            try {
                factory.setAttribute(
                        "http://javax.xml.XMLConstants/property/accessExternalDTD", "");
                factory.setAttribute(
                        "http://javax.xml.XMLConstants/property/accessExternalSchema", "");
            } catch (IllegalArgumentException ignored) {
                // Android's parser may not expose these JAXP attributes; the features still apply.
            }
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new ErrorHandler() {
                @Override
                public void warning(SAXParseException exception) {
                }

                @Override
                public void error(SAXParseException exception) throws SAXException {
                    throw exception;
                }

                @Override
                public void fatalError(SAXParseException exception) throws SAXException {
                    throw exception;
                }
            });
            Document document = builder.parse(new ByteArrayInputStream(bytes));
            Element root = document.getDocumentElement();
            if (root == null || !"comicinfo".equalsIgnoreCase(root.getLocalName() == null
                    ? root.getNodeName() : root.getLocalName())) return SeriesMetadata.EMPTY;
            return new SeriesMetadata(firstText(root, "Series"), firstText(root, "Number"));
        } catch (IOException | ParserConfigurationException | SAXException | RuntimeException ignored) {
            // Optional metadata never makes otherwise readable comic pages fail to open.
            return SeriesMetadata.EMPTY;
        }
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

    private static String firstText(Element root, String name) {
        NodeList values = root.getElementsByTagName(name);
        if (values.getLength() == 0) values = root.getElementsByTagNameNS("*", name);
        if (values.getLength() == 0) return "";
        String value = values.item(0).getTextContent();
        return value == null ? "" : value.trim();
    }

}
