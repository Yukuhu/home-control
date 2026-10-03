package dev.andre.homecontrol.adapters.cast;

import dev.andre.homecontrol.adapters.cast.protocol.FakeCastReceiver;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.DiscoveredDevice;
import dev.andre.homecontrol.discovery.MdnsBrowser;
import java.time.Duration;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class CastAdapterTest {

    @Test
    void validateRejectsAPortOutOfRange() {
        Device device = new Device("x", "X", DeviceKind.CAST, "10.0.0.9",
                Map.of("cast", Map.of("host", "10.0.0.9", "port", "0")), Instant.now());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.validate(device))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("cast port must be an integer between 1 and 65535");
    }

    /** heartbeat 1 s, stale 3 s, backoff 1–2 s, command 2 s, load 5 s, media poll 1 s. */
    private static final CastProperties PROPERTIES = new CastProperties(true, Duration.ofSeconds(1),
            Duration.ofSeconds(3), Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(2),
            Duration.ofSeconds(5), Duration.ofSeconds(1));

    private final CastAdapter adapter = new CastAdapter(new CastDiscovery(new MdnsBrowser(false), event -> { }),
            PROPERTIES);

    @Test
    void aReceiverThatAnnouncesItselfIsReconnectedAtOnce() throws Exception {
        // Retries a minute apart: only the announcement can bring the receiver back within the test.
        CastProperties slowRetries = new CastProperties(true, Duration.ofSeconds(1), Duration.ofSeconds(3),
                Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofSeconds(2), Duration.ofSeconds(5),
                Duration.ofSeconds(1));
        CastDiscovery discovery = new CastDiscovery(new MdnsBrowser(false), event -> { });
        CastAdapter withDiscovery = new CastAdapter(discovery, slowRetries);
        try (FakeCastReceiver receiver = new FakeCastReceiver();
             DeviceHandle handle = withDiscovery.connect(CastSessionTest.device(receiver.port()), state -> { })) {
            await().until(() -> handle.state().status() == DeviceStatus.CONNECTED);
            receiver.dropConnection();
            await().until(() -> handle.state().status() == DeviceStatus.DISCONNECTED);

            discovery.resolved(new MdnsBrowser.MdnsService(CastDiscovery.SERVICE_TYPE, "Chromecast-1",
                    List.of(InetAddress.getByName("127.0.0.1")), receiver.port(), Map.of("fn", "Living Room TV")));

            await().atMost(Duration.ofSeconds(10)).until(() -> handle.state().status() == DeviceStatus.CONNECTED);
        }
    }

    @Test
    void isThePairingFreeCastAdapter() {
        assertThat(adapter.id()).isEqualTo("cast");
        assertThat(adapter.kind()).isEqualTo(DeviceKind.CAST);
        assertThat(adapter.credentialsBoundToDeviceId()).isFalse();
    }

    @Test
    void registersADiscoveredReceiverWithItsPortIdAndModel() {
        DiscoveredDevice found = new DiscoveredDevice("cast", "Kitchen", "10.0.0.9", 8009,
                Map.of("id", "abc123", "md", "Chromecast"));

        assertThat(adapter.settingsFor(found))
                .contains(Map.of("host", "10.0.0.9", "port", "8009", "castId", "abc123", "model", "Chromecast"));
        assertThat(adapter.settingsFor(new DiscoveredDevice("androidtv", "TV", "10.0.0.5", 6466))).isEmpty();
    }

    @Test
    void readsItsSettingsBackFromADevice() {
        Device device = new Device("cast-10-0-0-9", "Kitchen", DeviceKind.CAST, "10.0.0.9",
                Map.of("cast", Map.of("port", "8010", "castId", "abc123")), Instant.EPOCH);

        assertThat(CastSettings.of(device)).isEqualTo(new CastSettings(8010, "abc123", null, "10.0.0.9"));
    }

    @Test
    void aReceiverMergedFromAnotherAddressIsReachedAtItsOwnAddress() {
        // Merged into a TV by friendly name: the receiver is not at the TV's address.
        Device tv = new Device("10-0-0-5", "Living Room TV", DeviceKind.ANDROID_TV, "10.0.0.5",
                Map.of("androidtv", Map.of("port", "6466"),
                        "cast", Map.of("host", "10.0.0.77", "port", "8009")), Instant.EPOCH);

        assertThat(CastSettings.of(tv).host()).isEqualTo("10.0.0.77");
        assertThat(adapter.hostOf(tv)).isEqualTo("10.0.0.77");
    }

    @Test
    void recognisesItsReceiverByAddressOrCastIdNotByTheDevicesAddress() {
        Device tv = new Device("10-0-0-5", "Living Room TV", DeviceKind.ANDROID_TV, "10.0.0.5",
                Map.of("androidtv", Map.of("port", "6466"),
                        "cast", Map.of("host", "10.0.0.77", "port", "8009", "castId", "abc123")), Instant.EPOCH);

        assertThat(adapter.carries(tv, new DiscoveredDevice("cast", "TV", "10.0.0.77", 8009, Map.of()))).isTrue();
        assertThat(adapter.carries(tv, new DiscoveredDevice("cast", "TV", "10.0.0.99", 8009, Map.of("id", "abc123"))))
                .as("same receiver after a DHCP change").isTrue();
        assertThat(adapter.carries(tv, new DiscoveredDevice("cast", "Other", "10.0.0.5", 8009, Map.of("id", "zzz"))))
                .as("another receiver at the TV's own address").isFalse();
    }

    @Test
    void connectStartsASessionThatReachesTheReceiver() throws Exception {
        try (FakeCastReceiver receiver = new FakeCastReceiver();
             DeviceHandle handle = adapter.connect(CastSessionTest.device(receiver.port()), state -> { })) {
            await().until(() -> handle.state().connected());
            // The session is connected once it has sent CONNECT; the fake reads it on its own thread.
            await().untilAsserted(() -> assertThat(receiver.virtualConnections()).contains("receiver-0"));
        }
    }

    @Test
    void reportsTheForegroundAppLive() {
        assertThat(adapter.foregroundAppReporting(CastSessionTest.device(8009)))
                .isEqualTo(dev.andre.homecontrol.core.ForegroundAppReporting.LIVE);
    }

    @Test
    void declaresCastReceiverAndVolume() {
        assertThat(adapter.capabilities(CastSessionTest.device(8009)))
                .containsExactlyInAnyOrder(Capability.CAST_RECEIVER, Capability.VOLUME);
    }
}
