package dev.andre.homecontrol.adapters.cast;

import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DiscoveredDevice;
import dev.andre.homecontrol.core.RemoteKey;
import dev.andre.homecontrol.discovery.MdnsBrowser;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CastAdapterTest {

    private final CastAdapter adapter = new CastAdapter(new CastDiscovery(new MdnsBrowser(false), event -> { }));

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

        assertThat(adapter.settingsFor(found)).contains(Map.of("port", "8009", "castId", "abc123", "model", "Chromecast"));
        assertThat(adapter.settingsFor(new DiscoveredDevice("androidtv", "TV", "10.0.0.5", 6466))).isEmpty();
    }

    @Test
    void readsItsSettingsBackFromADevice() {
        Device device = new Device("cast-10-0-0-9", "Kitchen", DeviceKind.CAST, "10.0.0.9",
                Map.of("cast", Map.of("port", "8010", "castId", "abc123")), Instant.EPOCH);

        assertThat(CastSettings.of(device)).isEqualTo(new CastSettings(8010, "abc123", null));
    }

    @Test
    void untilTheConnectionExistsTheHandleIsOffline() {
        Device device = new Device("cast-10-0-0-9", "Kitchen", DeviceKind.CAST, "10.0.0.9",
                Map.of("cast", Map.of("port", "8009")), Instant.EPOCH);

        try (DeviceHandle handle = adapter.connect(device, state -> { })) {
            assertThatThrownBy(() -> handle.execute(new Action.PressKey(RemoteKey.HOME)))
                    .isInstanceOf(DeviceOfflineException.class);
        }
    }
}
