package dev.andre.homecontrol.adapters.cast;

import dev.andre.homecontrol.core.DeviceDiscoveredEvent;
import dev.andre.homecontrol.core.DiscoveredDevice;
import dev.andre.homecontrol.discovery.MdnsBrowser;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class CastDiscoveryTest {

    private final List<Object> published = new CopyOnWriteArrayList<>();
    private final CastDiscovery discovery = new CastDiscovery(new MdnsBrowser(false), published::add);

    private static MdnsBrowser.MdnsService service(String name, String host, Map<String, String> txt) throws Exception {
        return new MdnsBrowser.MdnsService(CastDiscovery.SERVICE_TYPE, name, List.of(InetAddress.getByName(host)), 8009, txt);
    }

    @Test
    void usesTheFriendlyNameAndKeepsIdentityAttributes() throws Exception {
        DiscoveredDevice device = CastDiscovery.toDevice(service("SHIELD-Android-TV-6f1c", "192.168.1.50",
                Map.of("id", "6f1c0e2a9b", "md", "SHIELD Android TV", "fn", "Living Room TV", "ca", "463365", "rs", ""))).orElseThrow();

        assertThat(device).isEqualTo(new DiscoveredDevice("cast", "Living Room TV", "192.168.1.50", 8009,
                Map.of("id", "6f1c0e2a9b", "md", "SHIELD Android TV", "fn", "Living Room TV", "ca", "463365")));
    }

    @Test
    void fallsBackToTheInstanceNameWithoutAFriendlyName() throws Exception {
        assertThat(CastDiscovery.toDevice(service("Chromecast-abc", "10.0.0.9", Map.of())).orElseThrow().name())
                .isEqualTo("Chromecast-abc");
    }

    @Test
    void ignoresCastGroups() throws Exception {
        assertThat(CastDiscovery.toDevice(service("Google-Cast-Group-1", "10.0.0.9", Map.of("ca", "4196645", "md", "Google Cast Group"))))
                .isEmpty();
        assertThat(CastDiscovery.toDevice(service("g", "10.0.0.9", Map.of("ca", "32")))).isEmpty();
    }

    @Test
    void publishesOnceForANewReceiverAndForgetsItWhenRemoved() throws Exception {
        MdnsBrowser.MdnsService kitchen = service("Chromecast-abc", "10.0.0.9", Map.of("fn", "Kitchen"));

        discovery.resolved(kitchen);
        discovery.resolved(kitchen);

        assertThat(discovery.devices()).extracting(DiscoveredDevice::name).containsExactly("Kitchen");
        assertThat(published).singleElement().isInstanceOfSatisfying(DeviceDiscoveredEvent.class,
                event -> assertThat(event.device().host()).isEqualTo("10.0.0.9"));

        discovery.removed("Chromecast-abc");

        assertThat(discovery.devices()).isEmpty();
    }
}
