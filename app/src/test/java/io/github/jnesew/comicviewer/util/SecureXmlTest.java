package io.github.jnesew.comicviewer.util;

import org.junit.Test;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public final class SecureXmlTest {
    @Test public void unicodeMetadataSurvivesSupportedEncodings() throws Exception {
        for (Charset encoding : new Charset[]{StandardCharsets.UTF_8, StandardCharsets.UTF_16,
                StandardCharsets.UTF_16BE, StandardCharsets.UTF_16LE}) {
            String text = "<?xml version=\"1.0\" encoding=\"" + encoding.name() + "\"?><title>Ääni 漫画 🦊 &amp; friends</title>";
            assertEquals("Ääni 漫画 🦊 & friends", SecureXml.parse(text.getBytes(encoding), false)
                    .getDocumentElement().getTextContent());
        }
    }

    @Test public void rejectsDtdsAndEntitiesBeforeParsingInEveryEncoding() throws Exception {
        for (Charset encoding : new Charset[]{StandardCharsets.UTF_8, StandardCharsets.UTF_16,
                StandardCharsets.UTF_16BE, StandardCharsets.UTF_16LE}) {
            for (String xml : new String[]{
                    "<!DOCTYPE x SYSTEM 'file:///does-not-exist'><x/>",
                    "<!DOCTYPE x [<!ENTITY a 'expanded'>]><x>&a;</x>",
                    "<!DOCTYPE x [<!ENTITY % remote SYSTEM 'https://example.invalid/dtd'>%remote;]><x/>",
                    "<!DOCTYPE html [<!ENTITY a 'expanded'>]><html>&a;</html>"}) {
                try { SecureXml.parse(xml.getBytes(encoding), true); fail("DTD accepted: " + encoding); }
                catch (SAXException expected) { assertEquals("DTD and entity declarations are not supported.", expected.getMessage()); }
            }
        }
    }

    @Test public void inertHtml5DeclarationIsOptionalAndNeverEnablesDtds() throws Exception {
        byte[] text = "<!DOCTYPE html><html/>".getBytes(StandardCharsets.UTF_8);
        assertEquals("html", SecureXml.parse(text, true).getDocumentElement().getNodeName());
        try { SecureXml.parse(text, false); fail("Unexpected doctype accepted"); }
        catch (SAXException expected) { }
    }

    @Test public void rejectsMalformedEncodingsAndOversizedInput() throws Exception {
        for (byte[] text : new byte[][]{{'<', 'x', '>', (byte) 0xc0, (byte) 0xbc}, new byte[InputLimits.MAX_XML_BYTES + 1]}) {
            try { SecureXml.parse(text, false); fail("Invalid XML bytes accepted"); }
            catch (IOException expected) { }
        }
        try { SecureXml.parse("<\0!DOCTYPE x><x/>".getBytes(StandardCharsets.UTF_8), false); fail("NUL accepted"); }
        catch (SAXException expected) { }
    }
}
