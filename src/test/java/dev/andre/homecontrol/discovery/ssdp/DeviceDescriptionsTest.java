package dev.andre.homecontrol.discovery.ssdp;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeviceDescriptionsTest {

    private static byte[] fixture(String name) throws IOException {
        return Files.readAllBytes(Path.of("src/test/resources/fixtures/ssdp/" + name));
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

        assertThatThrownBy(() -> DeviceDescriptions.parse(xml, URI.create("http://10.0.0.1/d.xml")))
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
}
