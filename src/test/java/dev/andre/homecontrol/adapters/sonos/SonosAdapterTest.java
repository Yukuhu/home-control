package dev.andre.homecontrol.adapters.sonos;

import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.DiscoveredDevice;
import dev.andre.homecontrol.discovery.ssdp.SsdpDiscovery;
import dev.andre.homecontrol.discovery.ssdp.SsdpProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class SonosAdapterTest {

    private final SsdpDiscovery ssdp = new SsdpDiscovery(new SsdpProperties(false, "239.255.255.250", 1900, 1900, 60, 2));
    private final SonosProperties properties = new SonosProperties(true, 1, 1, 1, 1, 1, 1, 2);
    private final SonosDiscovery discovery = new SonosDiscovery(ssdp, properties, event -> { });
    private final SonosAdapter adapter = new SonosAdapter(properties, discovery);

    @AfterEach
    void tearDown() {
        discovery.close();
        ssdp.close();
    }

    @Test
    void isAPairingFreeMediaRenderer() {
        assertThat(adapter.id()).isEqualTo("sonos");
        assertThat(adapter.kind()).isEqualTo(DeviceKind.SONOS);
        assertThat(adapter.capabilities(null)).containsExactlyInAnyOrder(Capability.MEDIA_RENDERER, Capability.VOLUME);
        assertThat(adapter.settingsFor(new DiscoveredDevice("sonos", "Kitchen", "10.0.0.71", 1400, Map.of("uuid", "RINCON_X"))))
                .contains(Map.of("uuid", "RINCON_X", "port", "1400"));
        assertThat(adapter.settingsFor(new DiscoveredDevice("upnp", "Kitchen", "10.0.0.71", 1400, Map.of("uuid", "RINCON_X"))))
                .isEmpty();
    }

    @Test
    void connectsARegisteredPlayer() throws Exception {
        try (FakeSonosHousehold household = new FakeSonosHousehold()) {
            FakeSonosPlayer kitchen = household.addPlayer("127.0.0.3", SonosDiscoveryTest.KITCHEN, "Kitchen");
            try (DeviceHandle handle = adapter.connect(kitchen.device("kitchen"), state -> { })) {
                await().atMost(Duration.ofSeconds(5)).until(() -> handle.state().status() == DeviceStatus.CONNECTED);
            }
        }
    }
}
