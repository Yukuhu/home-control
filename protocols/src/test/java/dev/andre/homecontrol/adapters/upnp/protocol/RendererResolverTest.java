package dev.andre.homecontrol.adapters.upnp.protocol;

import dev.andre.homecontrol.testsupport.Fixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RendererResolverTest {

    private final RendererResolver resolver = new RendererResolver(SoapClient.httpClient(Duration.ofSeconds(1)),
            Duration.ofSeconds(1));
    private FakeUpnpRenderer renderer;

    @BeforeEach
    void start() throws IOException {
        renderer = new FakeUpnpRenderer();
    }

    @AfterEach
    void stop() {
        renderer.close();
    }

    private static String description() throws IOException {
        return Fixtures.read("upnp/renderer-description.xml");
    }

    private RendererResolver.Renderer resolve() throws IOException {
        return resolver.resolve(renderer.location(), renderer.host(), FakeUpnpRenderer.UDN);
    }

    @Test
    void findsTheServicesAndTheVolumeRange() throws IOException {
        renderer.setVolumeMax(50);

        RendererResolver.Renderer found = resolve();

        assertThat(found.avTransport().controlUrl().getPath()).isEqualTo("/upnp/control/AVTransport1");
        assertThat(found.renderingControl()).isNotNull();
        assertThat(found.volumeMax()).isEqualTo(50);
    }

    @Test
    void aLocationOffTheDevicesHostIsRefusedUnread() throws Exception {
        try (FakeUpnpRenderer impostor = new FakeUpnpRenderer("127.0.0.2", FakeUpnpRenderer.Layout.GENERIC)) {
            assertThatThrownBy(() -> resolver.resolve(impostor.location(), renderer.host(), FakeUpnpRenderer.UDN))
                    .isInstanceOf(IOException.class);
            assertThat(impostor.requestedPaths()).isEmpty();
        }
    }

    @Test
    void aDescriptionOfAnotherDeviceIsRefused() throws Exception {
        renderer.overrideDescription(description().replace(FakeUpnpRenderer.UDN, "uuid:00000000-0000-0000-0000-000000000bad"));

        assertThatThrownBy(this::resolve).isInstanceOf(IOException.class).hasMessageContaining("another device");
    }

    @Test
    void noLocationIsRefusedUnread() {
        String host = renderer.host();

        assertThatThrownBy(() -> resolver.resolve(null, host, FakeUpnpRenderer.UDN))
                .isInstanceOf(IOException.class)
                .hasMessage("No description address on the device's own host");
        assertThat(renderer.requestedPaths()).isEmpty();
    }

    @Test
    void withoutAnExpectedUdnAnyDescriptionOnTheHostIsAccepted() throws Exception {
        renderer.overrideDescription(description().replace(FakeUpnpRenderer.UDN, "uuid:00000000-0000-0000-0000-000000000new"));

        RendererResolver.Renderer found = resolver.resolve(renderer.location(), renderer.host(), null);

        assertThat(found.avTransport().controlUrl().getPath()).isEqualTo("/upnp/control/AVTransport1");
    }

    @Test
    void aDescriptionWithoutAUdnIsNotTheExpectedDevice() throws Exception {
        renderer.overrideDescription(description().replace("<UDN>" + FakeUpnpRenderer.UDN + "</UDN>", ""));

        assertThatThrownBy(this::resolve).isInstanceOf(IOException.class).hasMessageContaining("another device");
    }

    @Test
    void anAvTransportOverHttpsLeavesNothingToControl() throws Exception {
        renderer.overrideDescription(description().replace("<controlURL>/upnp/control/AVTransport1</controlURL>",
                "<controlURL>https://" + renderer.host() + "/upnp/control/AVTransport1</controlURL>"));

        assertThatThrownBy(this::resolve).isInstanceOf(IOException.class).hasMessage("No usable AVTransport service");
    }

    @Test
    void aDescriptionThatIsNotXmlIsUnreadable() {
        renderer.overrideDescription("<html>");

        assertThatThrownBy(this::resolve).isInstanceOf(IOException.class).hasMessageContaining("Unreadable");
    }

    @Test
    void anAvTransportOnAnotherHostLeavesNothingToControl() throws Exception {
        renderer.overrideDescription(description().replace("<controlURL>/upnp/control/AVTransport1</controlURL>",
                "<controlURL>http://192.0.2.1:1/upnp/control/AVTransport1</controlURL>"));

        assertThatThrownBy(this::resolve).isInstanceOf(IOException.class).hasMessageContaining("AVTransport");
    }

    @Test
    void aServiceWithAMalformedTypeIsLeftOut() throws Exception {
        renderer.overrideDescription(description().replace(
                "<serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>",
                "<serviceType>urn:schemas-upnp-org:service:RenderingControl:1#x</serviceType>"));

        assertThat(resolve().renderingControl()).isNull();
    }

    @Test
    void anScpdOffTheDescriptionsHostKeepsTheDefaultVolumeRange() throws Exception {
        renderer.setVolumeMax(50);
        renderer.overrideDescription(description().replace("<SCPDURL>/scpd/RenderingControl1.xml</SCPDURL>",
                "<SCPDURL>http://192.0.2.1:1/scpd/RenderingControl1.xml</SCPDURL>"));

        assertThat(resolve().volumeMax()).isEqualTo(VolumeRange.DEFAULT_MAXIMUM);
        assertThat(renderer.requestedPaths()).doesNotContain("/scpd/RenderingControl1.xml");
    }

    @Test
    void anScpdThatCannotBeReadKeepsTheDefaultVolumeRange() throws Exception {
        renderer.setVolumeMax(50);
        renderer.overrideDescription(description().replace("<SCPDURL>/scpd/RenderingControl1.xml</SCPDURL>",
                "<SCPDURL>/scpd/missing.xml</SCPDURL>"));

        assertThat(resolve().volumeMax()).isEqualTo(VolumeRange.DEFAULT_MAXIMUM);
        assertThat(renderer.requestedPaths()).contains("/scpd/missing.xml");
    }
}
