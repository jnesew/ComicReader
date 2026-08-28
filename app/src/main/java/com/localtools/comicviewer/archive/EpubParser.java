package com.localtools.comicviewer.archive;

import com.localtools.comicviewer.util.InputLimits;
import com.localtools.comicviewer.util.InputLimits.LimitExceededException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXParseException;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

/** Strict, non-rendering parser for the image-page subset of EPUB comics. */
public final class EpubParser {
    private static final Pattern INERT_HTML5_DOCTYPE = Pattern.compile(
            "<!DOCTYPE\\s+html\\s*>", Pattern.CASE_INSENSITIVE);
    private static final Set<String> RASTER_MEDIA_TYPES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "image/jpeg", "image/png", "image/webp", "image/gif", "image/bmp")));
    private static final Set<String> RASTER_EXTENSIONS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    ".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp")));

    public enum ErrorCode {
        INVALID,
        UNSUPPORTED_LAYOUT,
        UNSAFE_RESOURCE,
        ENCRYPTED,
        NO_PAGES
    }

    public static final class EpubException extends IOException {
        private static final long serialVersionUID = 1L;
        public final ErrorCode code;

        public EpubException(ErrorCode code, String message) {
            super(message);
            this.code = code;
        }

        public EpubException(ErrorCode code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }
    }

    public static final class Result {
        public final String title;
        public final List<String> pageNames;
        public final String coverName;
        public final boolean rightToLeft;
        public final boolean fixedLayout;
        public final String seriesName;
        public final String seriesNumber;

        private Result(
                String title,
                List<String> pageNames,
                String coverName,
                boolean rightToLeft,
                boolean fixedLayout,
                String seriesName,
                String seriesNumber) {
            this.title = InputLimits.normalizeText(
                    title, InputLimits.MAX_TITLE_CODE_POINTS);
            this.pageNames = Collections.unmodifiableList(new ArrayList<>(pageNames));
            this.coverName = coverName;
            this.rightToLeft = rightToLeft;
            this.fixedLayout = fixedLayout;
            this.seriesName = InputLimits.normalizeText(
                    seriesName, InputLimits.MAX_SERIES_CODE_POINTS);
            this.seriesNumber = InputLimits.normalizeText(
                    seriesNumber, InputLimits.MAX_ISSUE_CODE_POINTS);
        }
    }

    private EpubParser() {
    }

    public static boolean looksLikeEpub(ZipFile zipFile) {
        return zipFile.getEntry("META-INF/container.xml") != null;
    }

    public static Result parse(ZipFile zipFile) throws EpubException {
        try {
            InputLimits.validateArchive(zipFile);
            String packagePath = packagePath(zipFile);
            Document packageDocument = parseXml(zipFile, packagePath);
            Element packageRoot = packageDocument.getDocumentElement();
            if (packageRoot == null || !"package".equals(localName(packageRoot))) {
                throw invalid("The EPUB package document is missing.");
            }

            String title = firstText(packageRoot, "title");
            boolean globalFixed = false;
            String legacyCoverId = null;
            String legacySeriesName = "";
            String legacySeriesNumber = "";
            Map<String, String> collections = new LinkedHashMap<>();
            Set<String> seriesCollections = new HashSet<>();
            Map<String, String> collectionPositions = new HashMap<>();
            NodeList metadata = packageRoot.getElementsByTagNameNS("*", "meta");
            for (int index = 0; index < metadata.getLength(); index++) {
                Element meta = (Element) metadata.item(index);
                String property = attribute(meta, "property").toLowerCase(Locale.ROOT);
                String name = attribute(meta, "name").toLowerCase(Locale.ROOT);
                String value = property.isEmpty() ? attribute(meta, "content") : text(meta);
                if ("rendition:layout".equals(property) &&
                        "pre-paginated".equalsIgnoreCase(value.trim())) {
                    globalFixed = true;
                }
                if (("fixed-layout".equals(name) || "fixed_layout".equals(name)) &&
                        ("true".equalsIgnoreCase(value.trim()) ||
                                "pre-paginated".equalsIgnoreCase(value.trim()))) {
                    globalFixed = true;
                }
                if ("cover".equals(name)) legacyCoverId = attribute(meta, "content").trim();
                if ("calibre:series".equals(name)) legacySeriesName = value.trim();
                if ("calibre:series_index".equals(name)) legacySeriesNumber = value.trim();
                if ("belongs-to-collection".equals(property)) {
                    String id = attribute(meta, "id").trim();
                    if (!id.isEmpty() && !value.trim().isEmpty()) {
                        collections.put(id, value.trim());
                    }
                }
            }
            for (int index = 0; index < metadata.getLength(); index++) {
                Element meta = (Element) metadata.item(index);
                String refines = attribute(meta, "refines").trim();
                if (!refines.startsWith("#") || refines.length() == 1) continue;
                String id = refines.substring(1);
                if (!collections.containsKey(id)) continue;
                String property = attribute(meta, "property").trim().toLowerCase(Locale.ROOT);
                String value = text(meta).trim();
                if ("collection-type".equals(property) && "series".equalsIgnoreCase(value)) {
                    seriesCollections.add(id);
                }
                if ("group-position".equals(property) && !value.isEmpty()) {
                    collectionPositions.put(id, value);
                }
            }

            String seriesName = legacySeriesName;
            String seriesNumber = legacySeriesNumber;
            for (String id : collections.keySet()) {
                if (!seriesCollections.contains(id)) continue;
                seriesName = collections.get(id);
                seriesNumber = collectionPositions.getOrDefault(id, "");
                break;
            }

            Map<String, ManifestItem> manifest = new HashMap<>();
            Map<String, ManifestItem> manifestByPath = new HashMap<>();
            String coverId = legacyCoverId;
            NodeList items = packageRoot.getElementsByTagNameNS("*", "item");
            for (int index = 0; index < items.getLength(); index++) {
                Element item = (Element) items.item(index);
                String id = attribute(item, "id").trim();
                String href = attribute(item, "href").trim();
                if (id.isEmpty() || href.isEmpty()) continue;
                String path = resolvePath(packagePath, href);
                ManifestItem parsed = new ManifestItem(
                        id, path, attribute(item, "media-type").trim().toLowerCase(Locale.ROOT),
                        tokens(attribute(item, "properties")));
                manifest.put(id, parsed);
                manifestByPath.put(path, parsed);
                if (parsed.properties.contains("cover-image")) coverId = id;
            }

            Element spine = firstElement(packageRoot, "spine");
            if (spine == null) throw invalid("The EPUB spine is missing.");
            boolean rightToLeft = "rtl".equalsIgnoreCase(
                    attribute(spine, "page-progression-direction").trim());
            ArrayList<SpineItem> spineItems = new ArrayList<>();
            NodeList children = spine.getChildNodes();
            for (int index = 0; index < children.getLength(); index++) {
                Node child = children.item(index);
                if (!(child instanceof Element itemRef) ||
                        !"itemref".equals(localName(itemRef)) ||
                        "no".equalsIgnoreCase(attribute(itemRef, "linear"))) continue;
                ManifestItem item = manifest.get(attribute(itemRef, "idref").trim());
                if (item == null) throw invalid("A spine item is absent from the manifest.");
                spineItems.add(new SpineItem(item, tokens(attribute(itemRef, "properties"))));
                if (spineItems.size() > InputLimits.MAX_PAGES) {
                    throw new EpubException(ErrorCode.UNSUPPORTED_LAYOUT,
                            "The EPUB contains too many spine pages.");
                }
            }
            if (spineItems.isEmpty()) {
                throw new EpubException(ErrorCode.NO_PAGES, "The EPUB spine has no pages.");
            }

            boolean everyItemFixed = true;
            ArrayList<String> pageNames = new ArrayList<>(spineItems.size());
            for (SpineItem spineItem : spineItems) {
                everyItemFixed &= spineItem.properties.contains("rendition:layout-pre-paginated");
                pageNames.add(resolvePage(zipFile, spineItem.item, manifestByPath));
            }
            boolean fixedLayout = globalFixed || everyItemFixed;

            Set<String> encrypted = encryptedResources(zipFile);
            for (String page : pageNames) {
                if (encrypted.contains(page)) {
                    throw new EpubException(ErrorCode.ENCRYPTED,
                            "An EPUB page is encrypted or DRM protected.");
                }
            }

            String coverName = pageNames.get(0);
            ManifestItem coverItem = coverId == null ? null : manifest.get(coverId);
            if (coverItem != null) {
                try {
                    String candidate = resolvePage(zipFile, coverItem, manifestByPath);
                    if (encrypted.contains(candidate)) {
                        throw new EpubException(ErrorCode.ENCRYPTED,
                                "The EPUB cover is encrypted or DRM protected.");
                    }
                    coverName = candidate;
                } catch (EpubException coverError) {
                    if (coverError.code == ErrorCode.ENCRYPTED ||
                            coverError.code == ErrorCode.UNSAFE_RESOURCE) throw coverError;
                    // A decorative/non-image cover declaration does not invalidate image pages.
                }
            }
            return new Result(title, pageNames, coverName, rightToLeft, fixedLayout,
                    seriesName, seriesNumber);
        } catch (EpubException exception) {
            throw exception;
        } catch (LimitExceededException exception) {
            throw new EpubException(
                    ErrorCode.INVALID, "The EPUB exceeds a safety limit.", exception);
        } catch (RuntimeException exception) {
            throw new EpubException(ErrorCode.INVALID, "The EPUB metadata is invalid.", exception);
        }
    }

    private static String packagePath(ZipFile zipFile) throws EpubException {
        Document container = parseXml(zipFile, "META-INF/container.xml");
        NodeList rootFiles = container.getElementsByTagNameNS("*", "rootfile");
        for (int index = 0; index < rootFiles.getLength(); index++) {
            Element rootFile = (Element) rootFiles.item(index);
            String path = attribute(rootFile, "full-path").trim();
            if (!path.isEmpty()) return resolvePath("", path);
        }
        throw invalid("The EPUB container has no package path.");
    }

    private static String resolvePage(
            ZipFile zipFile,
            ManifestItem item,
            Map<String, ManifestItem> manifestByPath) throws EpubException {
        if (isRaster(item)) {
            requireEntry(zipFile, item.path);
            return item.path;
        }
        if ("application/xhtml+xml".equals(item.mediaType) ||
                "text/html".equals(item.mediaType) ||
                hasExtension(item.path, ".xhtml", ".html", ".htm") ||
                "image/svg+xml".equals(item.mediaType) || hasExtension(item.path, ".svg")) {
            return resolveWrapperImage(zipFile, item.path, manifestByPath);
        }
        throw new EpubException(ErrorCode.UNSUPPORTED_LAYOUT,
                "A spine page is not an image or single-image wrapper.");
    }

    private static String resolveWrapperImage(
            ZipFile zipFile,
            String wrapperPath,
            Map<String, ManifestItem> manifestByPath) throws EpubException {
        Document document = parseXml(zipFile, wrapperPath);
        Element root = document.getDocumentElement();
        if (root == null) throw invalid("An EPUB page wrapper is empty.");
        ArrayList<String> images = new ArrayList<>();
        NodeList elements = root.getElementsByTagName("*");
        for (int index = 0; index < elements.getLength(); index++) {
            Element element = (Element) elements.item(index);
            String local = localName(element).toLowerCase(Locale.ROOT);
            if ("script".equals(local)) {
                throw new EpubException(ErrorCode.UNSAFE_RESOURCE,
                        "Scripted EPUB pages are not supported.");
            }
            NamedNodeMap attributes = element.getAttributes();
            for (int attributeIndex = 0; attributeIndex < attributes.getLength(); attributeIndex++) {
                Node attribute = attributes.item(attributeIndex);
                String attributeName = localName(attribute).toLowerCase(Locale.ROOT);
                String value = attribute.getNodeValue() == null ? "" : attribute.getNodeValue().trim();
                if (attributeName.startsWith("on") && !value.isEmpty()) {
                    throw new EpubException(ErrorCode.UNSAFE_RESOURCE,
                            "Scripted EPUB pages are not supported.");
                }
                if (("src".equals(attributeName) || "href".equals(attributeName) ||
                        "data".equals(attributeName) || "poster".equals(attributeName)) &&
                        isRemoteOrData(value)) {
                    throw new EpubException(ErrorCode.UNSAFE_RESOURCE,
                            "Remote and embedded-data EPUB resources are not supported.");
                }
            }

            String reference = null;
            if ("img".equals(local)) reference = attribute(element, "src");
            else if ("image".equals(local)) reference = attribute(element, "href");
            else if ("object".equals(local)) reference = attribute(element, "data");
            if (reference == null || reference.trim().isEmpty()) continue;
            String path = resolvePath(wrapperPath, reference.trim());
            ManifestItem candidate = manifestByPath.get(path);
            if ((candidate != null && isRaster(candidate)) || isRasterPath(path)) {
                requireEntry(zipFile, path);
                images.add(path);
            } else {
                throw new EpubException(ErrorCode.UNSUPPORTED_LAYOUT,
                        "An EPUB page wrapper references a non-raster resource.");
            }
        }
        if (hasVisibleText(root, false)) {
            throw new EpubException(ErrorCode.UNSUPPORTED_LAYOUT,
                    "Text or layered EPUB pages require a reflowable ebook renderer.");
        }
        if (images.size() != 1) {
            throw new EpubException(ErrorCode.UNSUPPORTED_LAYOUT,
                    "Each EPUB spine page must resolve to exactly one image.");
        }
        return images.get(0);
    }

    private static boolean hasVisibleText(Node node, boolean ignored) {
        boolean ignoreChildren = ignored;
        if (node instanceof Element element) {
            String local = localName(element).toLowerCase(Locale.ROOT);
            ignoreChildren |= "head".equals(local) || "title".equals(local) ||
                    "desc".equals(local) ||
                    "style".equals(local) || "script".equals(local) || "metadata".equals(local);
        }
        if (node.getNodeType() == Node.TEXT_NODE && !ignoreChildren) {
            return node.getNodeValue() != null && !node.getNodeValue().trim().isEmpty();
        }
        NodeList children = node.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            if (hasVisibleText(children.item(index), ignoreChildren)) return true;
        }
        return false;
    }

    private static Set<String> encryptedResources(ZipFile zipFile) throws EpubException {
        if (zipFile.getEntry("META-INF/encryption.xml") == null) return Collections.emptySet();
        Document encryption = parseXml(zipFile, "META-INF/encryption.xml");
        HashSet<String> result = new HashSet<>();
        NodeList references = encryption.getElementsByTagNameNS("*", "CipherReference");
        for (int index = 0; index < references.getLength(); index++) {
            String uri = attribute((Element) references.item(index), "URI").trim();
            if (!uri.isEmpty()) result.add(resolvePath("", uri));
        }
        return result;
    }

    private static Document parseXml(ZipFile zipFile, String path) throws EpubException {
        ZipEntry entry = zipFile.getEntry(path);
        if (entry == null || entry.isDirectory()) throw invalid("A required EPUB file is missing.");
        try {
            InputLimits.validateMetadataEntry(entry, InputLimits.MAX_XML_BYTES);
        } catch (LimitExceededException exception) {
            throw new EpubException(
                    ErrorCode.INVALID, "An EPUB metadata file is too large.", exception);
        }
        try (InputStream raw = new BufferedInputStream(zipFile.getInputStream(entry), 32 * 1024)) {
            byte[] bytes = InputLimits.readAll(
                    raw, InputLimits.MAX_XML_BYTES, InputLimits.Reason.METADATA_BYTES);
            DocumentBuilder builder = secureDocumentBuilder();
            return builder.parse(new ByteArrayInputStream(stripInertHtml5Doctype(bytes)));
        } catch (EpubException exception) {
            throw exception;
        } catch (IOException | ParserConfigurationException | SAXException exception) {
            throw new EpubException(ErrorCode.INVALID, "EPUB XML could not be parsed.", exception);
        }
    }

    /**
     * XHTML commonly includes the inert HTML5 declaration. Strip only that exact declaration so
     * the hardened parser can continue rejecting every internal subset, external DTD, and entity.
     */
    private static byte[] stripInertHtml5Doctype(byte[] bytes) {
        String xml = new String(bytes, StandardCharsets.UTF_8);
        Matcher matcher = INERT_HTML5_DOCTYPE.matcher(xml);
        if (!matcher.find()) return bytes;
        return (xml.substring(0, matcher.start()) + xml.substring(matcher.end()))
                .getBytes(StandardCharsets.UTF_8);
    }

    private static DocumentBuilder secureDocumentBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        try {
            factory.setAttribute(
                    "http://javax.xml.XMLConstants/property/accessExternalDTD", "");
            factory.setAttribute(
                    "http://javax.xml.XMLConstants/property/accessExternalSchema", "");
        } catch (IllegalArgumentException ignored) {
            // Android's parser enforces the features above but may not expose JAXP attributes.
        }
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new ErrorHandler() {
            @Override
            public void warning(SAXParseException exception) {
                // Non-fatal metadata warnings do not change the parsed page model.
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
        return builder;
    }

    private static String resolvePath(String baseFile, String reference) throws EpubException {
        String value = reference.trim();
        int fragment = value.indexOf('#');
        if (fragment >= 0) value = value.substring(0, fragment);
        int query = value.indexOf('?');
        if (query >= 0) value = value.substring(0, query);
        if (!InputLimits.isIdentifierAcceptable(value)) {
            throw new EpubException(ErrorCode.UNSAFE_RESOURCE,
                    "An EPUB resource path is too large.");
        }
        if (value.isEmpty() || value.startsWith("/") || value.startsWith("\\") ||
                value.contains("\\") || isRemoteOrData(value)) {
            throw new EpubException(ErrorCode.UNSAFE_RESOURCE, "Unsafe EPUB resource path.");
        }
        value = percentDecode(value);
        if (value.startsWith("/") || value.startsWith("\\") || value.contains("\\")) {
            throw new EpubException(ErrorCode.UNSAFE_RESOURCE, "Unsafe EPUB resource path.");
        }
        String baseDirectory = "";
        int slash = baseFile.lastIndexOf('/');
        if (slash >= 0) baseDirectory = baseFile.substring(0, slash + 1);
        String combined = baseDirectory + value;
        if (!InputLimits.isIdentifierAcceptable(combined)) {
            throw new EpubException(ErrorCode.UNSAFE_RESOURCE,
                    "An EPUB resource path is too large.");
        }
        ArrayDeque<String> segments = new ArrayDeque<>();
        for (String segment : combined.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment)) continue;
            if ("..".equals(segment)) {
                if (segments.isEmpty()) {
                    throw new EpubException(ErrorCode.UNSAFE_RESOURCE,
                            "EPUB resource path escapes the publication.");
                }
                segments.removeLast();
            } else {
                if (segment.indexOf('\0') >= 0 || segment.indexOf(':') >= 0) {
                    throw new EpubException(ErrorCode.UNSAFE_RESOURCE, "Unsafe EPUB resource path.");
                }
                segments.addLast(segment);
            }
        }
        if (segments.isEmpty()) throw invalid("An EPUB resource path is empty.");
        return String.join("/", segments);
    }

    private static String percentDecode(String value) throws EpubException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(value.length());
        StringBuilder result = new StringBuilder(value.length());
        for (int index = 0; index < value.length();) {
            char character = value.charAt(index);
            if (character != '%') {
                result.append(character);
                index++;
                continue;
            }
            bytes.reset();
            while (index < value.length() && value.charAt(index) == '%') {
                if (index + 2 >= value.length()) {
                    throw invalid("An EPUB resource has invalid percent encoding.");
                }
                int high = Character.digit(value.charAt(index + 1), 16);
                int low = Character.digit(value.charAt(index + 2), 16);
                if (high < 0 || low < 0) {
                    throw invalid("An EPUB resource has invalid percent encoding.");
                }
                bytes.write((high << 4) | low);
                index += 3;
            }
            result.append(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        }
        return result.toString();
    }

    private static boolean isRemoteOrData(String value) {
        String lower = value.trim().toLowerCase(Locale.ROOT);
        if (lower.startsWith("//")) return true;
        int colon = lower.indexOf(':');
        int slash = lower.indexOf('/');
        return colon > 0 && (slash < 0 || colon < slash);
    }

    private static void requireEntry(ZipFile zipFile, String path) throws EpubException {
        ZipEntry entry = zipFile.getEntry(path);
        if (entry == null || entry.isDirectory()) throw invalid("An EPUB image is missing.");
        try {
            InputLimits.validatePageEntry(entry);
        } catch (LimitExceededException exception) {
            throw new EpubException(
                    ErrorCode.INVALID, "An EPUB page exceeds a safety limit.", exception);
        }
    }

    private static boolean isRaster(ManifestItem item) {
        return RASTER_MEDIA_TYPES.contains(item.mediaType) || isRasterPath(item.path);
    }

    private static boolean isRasterPath(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        for (String extension : RASTER_EXTENSIONS) if (lower.endsWith(extension)) return true;
        return false;
    }

    private static boolean hasExtension(String value, String... extensions) {
        String lower = value.toLowerCase(Locale.ROOT);
        for (String extension : extensions) if (lower.endsWith(extension)) return true;
        return false;
    }

    private static Set<String> tokens(String value) {
        HashSet<String> result = new HashSet<>();
        for (String token : value.trim().toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!token.isEmpty()) result.add(token);
        }
        return result;
    }

    private static Element firstElement(Element parent, String localName) {
        NodeList elements = parent.getElementsByTagNameNS("*", localName);
        return elements.getLength() == 0 ? null : (Element) elements.item(0);
    }

    private static String firstText(Element parent, String localName) {
        Element element = firstElement(parent, localName);
        return element == null ? "" : text(element).trim();
    }

    private static String text(Element element) {
        String value = element.getTextContent();
        return value == null ? "" : value;
    }

    private static String attribute(Element element, String wantedLocalName) {
        if (element.hasAttribute(wantedLocalName)) return element.getAttribute(wantedLocalName);
        NamedNodeMap attributes = element.getAttributes();
        for (int index = 0; index < attributes.getLength(); index++) {
            Node attribute = attributes.item(index);
            if (wantedLocalName.equalsIgnoreCase(localName(attribute))) {
                return attribute.getNodeValue() == null ? "" : attribute.getNodeValue();
            }
        }
        return "";
    }

    private static String localName(Node node) {
        String local = node.getLocalName();
        if (local != null && !local.isEmpty()) return local;
        String name = node.getNodeName();
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }

    private static EpubException invalid(String message) {
        return new EpubException(ErrorCode.INVALID, message);
    }

    private static final class ManifestItem {
        private final String id;
        private final String path;
        private final String mediaType;
        private final Set<String> properties;

        private ManifestItem(String id, String path, String mediaType, Set<String> properties) {
            this.id = id;
            this.path = path;
            this.mediaType = mediaType;
            this.properties = properties;
        }
    }

    private static final class SpineItem {
        private final ManifestItem item;
        private final Set<String> properties;

        private SpineItem(ManifestItem item, Set<String> properties) {
            this.item = item;
            this.properties = properties;
        }
    }
}
