package dev.andre.homecontrol.adapters.upnp;

import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.DiscoveredDevice;
import dev.andre.homecontrol.discovery.ssdp.SsdpDiscovery;
import dev.andre.homecontrol.discovery.ssdp.SsdpProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class UpnpAdapterTest {

    private final SsdpDiscovery ssdp = new SsdpDiscovery(new SsdpProperties(false, "239.255.255.250", 1900, 1900, 60, 2));
    private final UpnpDiscovery discovery = new UpnpDiscovery(ssdp, event -> { });
    private final UpnpAdapter adapter = new UpnpAdapter(new UpnpProperties(true, 1, 1, 1, 1, 1, 2), discovery);

    @AfterEach
    void tearDown() {
        discovery.close();
        ssdp.close();
    }

    @Test
    void isAPairingFreeMediaRenderer() {
        Map<String, String> attributes = Map.of("udn", "uuid:x", "location", "http://10.0.0.30:49152/d.xml", "model", "Acme");

        assertThat(adapter.id()).isEqualTo("upnp");
        assertThat(adapter.kind()).isEqualTo(DeviceKind.UPNP);
        assertThat(adapter.capabilities(null)).containsExactlyInAnyOrder(Capability.MEDIA_RENDERER, Capability.VOLUME);
        assertThat(adapter.settingsFor(new DiscoveredDevice("upnp", "Kitchen Speaker", "10.0.0.30", 49152, attributes)))
                .contains(attributes);
        assertThat(adapter.settingsFor(new DiscoveredDevice("cast", "Kitchen Speaker", "10.0.0.30", 8009, attributes))).isEmpty();
    }

    @Test
    void connectsARegisteredRenderer() throws Exception {
        List<DeviceState> states = new CopyOnWriteArrayList<>();
        try (FakeUpnpRenderer fake = new FakeUpnpRenderer();
             DeviceHandle handle = adapter.connect(fake.device("kitchen"), states::add)) {
            await().atMost(Duration.ofSeconds(5)).until(() -> handle.state().status() == DeviceStatus.CONNECTED);
        }
    }
}
