package dev.andre.homecontrol.adapters.upnp;

import dev.andre.homecontrol.core.DeviceDiscoveredEvent;
import dev.andre.homecontrol.core.DiscoveredDevice;
import dev.andre.homecontrol.discovery.ssdp.DeviceDescription;
import dev.andre.homecontrol.discovery.ssdp.FakeSsdpResponder;
import dev.andre.homecontrol.discovery.ssdp.SsdpDiscovery;
import dev.andre.homecontrol.discovery.ssdp.SsdpProperties;
import dev.andre.homecontrol.discovery.ssdp.SsdpService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class UpnpDiscoveryTest {

    private FakeSsdpResponder responder;
    private FakeUpnpRenderer fake;
    private SsdpDiscovery ssdp;
    private final List<Object> events = new CopyOnWriteArrayList<>();
    private UpnpDiscovery discovery;

    @BeforeEach
    void setUp() throws IOException {
        responder = new FakeSsdpResponder();
        fake = new FakeUpnpRenderer();
        responder.answer(UpnpDiscovery.SEARCH_TARGET, fake.searchResponse());
        ssdp = new SsdpDiscovery(new SsdpProperties(true, "127.0.0.1", responder.port(), 0, 1, 1));
        ssdp.start();
        discovery = new UpnpDiscovery(ssdp, events::add, true);
    }

    @AfterEach
    void tearDown() {
        discovery.close();
        ssdp.close();
        fake.close();
        responder.close();
    }

    private DiscoveredDevice expected() {
        return new DiscoveredDevice("upnp", "Kitchen Speaker", "127.0.0.1", fake.port(), Map.of(
                "udn", FakeUpnpRenderer.UDN, "location", fake.location().toString(), "model", "Acme Audio StreamBox 2"));
    }

    @Test
    void findsARendererWithItsDescription() {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(discovery.devices()).containsExactly(expected()));
    }

    @Test
    void publishesOneDiscoveryEventPerChange() {
        await().atMost(Duration.ofSeconds(8)).until(() -> !events.isEmpty());
        int searches = responder.searches();
        await().atMost(Duration.ofSeconds(10)).until(() -> responder.searches() >= searches + 4);

        assertThat(events).containsExactly(new DeviceDiscoveredEvent(expected()));
    }

    @Test
    void knowsTheLatestLocationOfAUdn() {
        await().atMost(Duration.ofSeconds(5)).until(() -> !discovery.devices().isEmpty());

        assertThat(discovery.location(FakeUpnpRenderer.UDN)).contains(fake.location());
        assertThat(discovery.location("uuid:nope")).isEmpty();
    }

    @Test
    void tellsListenersWhichRendererAnnouncedItself() {
        List<String> udns = new CopyOnWriteArrayList<>();
        discovery.onAlive(udns::add);

        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> assertThat(udns).contains(FakeUpnpRenderer.UDN));
    }

    @Test
    void skipsSonosPlayersWhileTheSonosModuleHandlesThem() {
        DeviceDescription.Service avTransport = new DeviceDescription.Service("urn:schemas-upnp-org:service:AVTransport:1", "av",
                URI.create("http://10.0.0.71:1400/MediaRenderer/AVTransport/Control"), null, null);
        SsdpService sonos = new SsdpService("uuid:RINCON_000E58A0B1C201400_MR::" + UpnpDiscovery.SEARCH_TARGET,
                UpnpDiscovery.SEARCH_TARGET, "10.0.0.71", URI.create("http://10.0.0.71:1400/xml/device_description.xml"),
                Map.of(), Instant.MAX, new DeviceDescription("Kitchen", "Sonos, Inc.", "One", "uuid:RINCON_000E58A0B1C201400_MR",
                List.of(avTransport)));
        SsdpService generic = new SsdpService("uuid:5f9e::" + UpnpDiscovery.SEARCH_TARGET, UpnpDiscovery.SEARCH_TARGET,
                "10.0.0.30", URI.create("http://10.0.0.30:49152/d.xml"), Map.of(), Instant.MAX,
                new DeviceDescription("Speaker", "Acme", "X", "uuid:5f9e", List.of(avTransport)));

        assertThat(UpnpDiscovery.toDevice(sonos, true)).isEmpty();
        assertThat(UpnpDiscovery.toDevice(sonos, false)).isPresent();
        assertThat(UpnpDiscovery.toDevice(generic, true)).isPresent();
        assertThat(UpnpDiscovery.toDevice(generic, false)).isPresent();
    }

    @Test
    void ignoresDevicesWithoutAvTransport() {
        URI location = URI.create("http://10.0.0.9:49152/d.xml");
        DeviceDescription renderingOnly = new DeviceDescription("TV", "Acme", "X", "uuid:x", List.of(
                new DeviceDescription.Service("urn:schemas-upnp-org:service:RenderingControl:1", "rc",
                        URI.create("http://10.0.0.9:49152/rc"), null, null)));

        assertThat(UpnpDiscovery.toDevice(new SsdpService("uuid:x::" + UpnpDiscovery.SEARCH_TARGET, UpnpDiscovery.SEARCH_TARGET,
                "10.0.0.9", location, Map.of(), Instant.MAX, renderingOnly), true)).isEmpty();
        assertThat(UpnpDiscovery.toDevice(new SsdpService("uuid:x::" + UpnpDiscovery.SEARCH_TARGET, UpnpDiscovery.SEARCH_TARGET,
                "10.0.0.9", location, Map.of(), Instant.MAX, null), true)).isEmpty();
    }
}
