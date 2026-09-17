package dev.andre.homecontrol.adapters.cast;

import dev.andre.homecontrol.adapters.cast.protocol.FakeCastReceiver;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DiscoveredDevice;
import dev.andre.homecontrol.discovery.MdnsBrowser;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class CastAdapterTest {

    private final CastAdapter adapter = new CastAdapter(new CastDiscovery(new MdnsBrowser(false), event -> { }),
            CastSessionTest.PROPERTIES);

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
            assertThat(receiver.virtualConnections()).contains("receiver-0");
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
