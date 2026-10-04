package dev.andre.homecontrol.discovery.ssdp.protocol;

import dev.andre.homecontrol.testsupport.Fixtures;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeviceDescriptionsTest {

    private static byte[] fixture(String name) throws IOException {
        return Fixtures.bytes("ssdp/" + name);
    }

    @Test
    void readsTheRootDeviceOfAnLgTv() throws IOException {
        DeviceDescription description = DeviceDescriptions.parse(fixture("lg-description.xml"),
                URI.create("http://192.168.1.60:1780/lg/description.xml"));

        assertThat(description.friendlyName()).isEqualTo("[LG] webOS TV OLED55C9PLA");
        assertThat(description.manufacturer()).isEqualTo("LG Electronics");
        assertThat(description.udn()).isEqualTo("uuid:e8d7f6a5-1234-4bcd-9ef0-a8b7c6d5e4f3");
        assertThat(description.services()).hasSize(1);
        assertThat(description.services().getFirst().controlUrl())
                .isEqualTo(URI.create("http://192.168.1.60:1780/WebOS_SecondScreen/control"));
    }

    @Test
    void resolvesRelativeServiceUrlsAgainstTheLocation() throws IOException {
        DeviceDescription description = DeviceDescriptions.parse(fixture("samsung-description.xml"),
                URI.create("http://192.168.1.61:7676/smp_15_"));

        DeviceDescription.Service service = description.service("urn:samsung.com:service:MultiScreenService")
                .orElseThrow();
        assertThat(service.scpdUrl()).isEqualTo(URI.create("http://192.168.1.61:7676/MultiScreenService.xml"));
    }

    @Test
    void findsServicesOfEmbeddedDevices() throws IOException {
        DeviceDescription description = DeviceDescriptions.parse(fixture("sonos-description.xml"),
                URI.create("http://192.168.1.70:1400/xml/device_description.xml"));

        assertThat(description.friendlyName()).isEqualTo("192.168.1.70 - Sonos One");
        DeviceDescription.Service service = description.service("urn:schemas-upnp-org:service:AVTransport")
                .orElseThrow();
        assertThat(service.controlUrl()).isEqualTo(URI.create("http://192.168.1.70:1400/MediaRenderer/AVTransport/Control"));
    }

    @Test
    void refusesADocumentTypeDeclaration() {
        byte[] xml = ("<?xml version=\"1.0\"?><!DOCTYPE root [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<root><device><friendlyName>&x;</friendlyName></device></root>")
                .getBytes(StandardCharsets.UTF_8);

        var location = URI.create("http://10.0.0.1/d.xml");
        assertThatThrownBy(() -> DeviceDescriptions.parse(xml, location))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsADocumentWithoutADevice() {
        byte[] xml = "<root/>".getBytes(StandardCharsets.UTF_8);
        URI location = URI.create("http://10.0.0.1/d.xml");

        assertThatThrownBy(() -> DeviceDescriptions.parse(xml, location))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(location.toString());
    }

    @Test
    void aServiceUrlThatIsNoUriOrMissingIsLeftEmpty() {
        byte[] xml = ("<root><device><friendlyName> </friendlyName></device><serviceList><service>"
                + "<serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>"
                + "<controlURL>/control path with spaces</controlURL><SCPDURL></SCPDURL>"
                + "</service></serviceList></root>").getBytes(StandardCharsets.UTF_8);

        DeviceDescription description = DeviceDescriptions.parse(xml, URI.create("http://10.0.0.1:1400/d.xml"));

        assertThat(description.friendlyName()).isNull();
        DeviceDescription.Service service = description.services().getFirst();
        assertThat(service.controlUrl()).isNull();
        assertThat(service.eventSubUrl()).isNull();
        assertThat(service.scpdUrl()).isNull();
    }

    @Test
    void aUrlBaseReplacesTheLocationForRelativeUrls() {
        byte[] xml = ("<root><URLBase>http://10.0.0.2:8080/</URLBase><device><friendlyName>Box</friendlyName></device>"
                + "<serviceList><service><controlURL>ctl</controlURL></service></serviceList></root>")
                .getBytes(StandardCharsets.UTF_8);

        DeviceDescription description = DeviceDescriptions.parse(xml, URI.create("http://10.0.0.1:1400/d.xml"));

        assertThat(description.services().getFirst().controlUrl()).isEqualTo(URI.create("http://10.0.0.2:8080/ctl"));
    }
}
