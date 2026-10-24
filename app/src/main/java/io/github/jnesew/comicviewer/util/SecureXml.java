package io.github.jnesew.comicviewer.util;

import org.w3c.dom.Document;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

/** Bounded UTF-8/UTF-16 metadata parsing on both Android's DOM implementation and the JDK. */
public final class SecureXml {
    private static final Pattern HTML5_DOCTYPE = Pattern.compile(
            "<!DOCTYPE\\s+html\\s*>", Pattern.CASE_INSENSITIVE);

    private SecureXml() { }

    public static Document parse(byte[] bytes, boolean allowHtml5Doctype)
            throws IOException, SAXException, ParserConfigurationException {
        if (bytes.length > InputLimits.MAX_XML_BYTES) throw new IOException("XML is too large.");
        String text = decode(bytes);
        if (text.indexOf('\0') >= 0) throw new SAXException("NUL is not valid XML text.");
        if (allowHtml5Doctype) text = HTML5_DOCTYPE.matcher(text).replaceAll("");
        // Android does not implement the JAXP/Xerces DTD feature switches. Reject declarations
        // before DOM parsing, after strict decoding, and parse that SAME character stream so
        // an encoding declaration cannot bypass the check. No DTD means no custom entities.
        if (text.contains("<!DOCTYPE") || text.contains("<!ENTITY")) {
            throw new SAXException("DTD and entity declarations are not supported.");
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setValidating(false);
        factory.setExpandEntityReferences(false);
        try { factory.setXIncludeAware(false); }
        catch (UnsupportedOperationException ignored) {
            // Android has no XInclude implementation; it cannot enable it.
        }
        var builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> {
            throw new SAXException("External XML resources are not supported.");
        });
        builder.setErrorHandler(new ErrorHandler() {
            @Override public void warning(SAXParseException error) { }
            @Override public void error(SAXParseException error) throws SAXException { throw error; }
            @Override public void fatalError(SAXParseException error) throws SAXException { throw error; }
        });
        return builder.parse(new InputSource(new StringReader(text)));
    }

    private static String decode(byte[] bytes) throws IOException {
        Charset charset = StandardCharsets.UTF_8;
        int offset = 0;
        if (bytes.length >= 2 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe) {
            charset = StandardCharsets.UTF_16LE; offset = 2;
        } else if (bytes.length >= 2 && bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xff) {
            charset = StandardCharsets.UTF_16BE; offset = 2;
        } else if (bytes.length >= 3 && bytes[0] == (byte) 0xef &&
                bytes[1] == (byte) 0xbb && bytes[2] == (byte) 0xbf) {
            offset = 3;
        } else if (bytes.length >= 4 && bytes[0] == 0 && bytes[1] == '<' && bytes[2] == 0) {
            charset = StandardCharsets.UTF_16BE;
        } else if (bytes.length >= 4 && bytes[0] == '<' && bytes[1] == 0 && bytes[3] == 0) {
            charset = StandardCharsets.UTF_16LE;
        }
        return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
    }
}
