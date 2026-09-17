package dev.andre.homecontrol.adapters.upnp.protocol;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UpnpXmlTest {

    @Test
    void escapesTheFiveXmlCharacters() {
        assertThat(UpnpXml.escape("a&b<c>d\"e'f")).isEqualTo("a&amp;b&lt;c&gt;d&quot;e&apos;f");
        assertThat(UpnpXml.escape(null)).isEmpty();
    }

    @Test
    void readsElementsIgnoringPrefixes() {
        Element root = UpnpXml.parse("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>"
                + "<u:GetVolumeResponse xmlns:u=\"urn:x\"><CurrentVolume>35</CurrentVolume><Note>&lt;b&gt;</Note>"
                + "</u:GetVolumeResponse></s:Body></s:Envelope>");

        Element response = UpnpXml.firstDescendant(root, "GetVolumeResponse").orElseThrow();
        assertThat(UpnpXml.childElements(response)).extracting(UpnpXml::localName).containsExactly("CurrentVolume", "Note");
        assertThat(UpnpXml.childText(response, "Note")).contains("<b>");
        assertThat(UpnpXml.descendants(root, "Body")).hasSize(1);
        assertThat(UpnpXml.firstDescendant(root, "Envelope")).containsSame(root);
    }

    @Test
    void refusesADocumentTypeDeclaration() {
        assertThatThrownBy(() -> UpnpXml.parse(
                "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><r>&x;</r>"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> UpnpXml.parse("not xml")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UpnpXml.parse(new byte[0])).isInstanceOf(IllegalArgumentException.class);
    }
}
