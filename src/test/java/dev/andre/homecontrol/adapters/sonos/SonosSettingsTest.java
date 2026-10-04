package dev.andre.homecontrol.adapters.sonos;

import dev.andre.homecontrol.adapters.sonos.protocol.SonosEndpoints;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SonosSettingsTest {

    private static Device device(Map<String, Map<String, String>> adapters) {
        return new Device("sonos-kitchen", "Kitchen", DeviceKind.SONOS, "192.168.1.41", adapters,
                Instant.parse("2026-10-04T12:00:00Z"));
    }

    @Test
    void aStoredPortThatIsNoNumberFallsBackToTheSonosPort() {
        SonosSettings settings = SonosSettings.of(device(Map.of("sonos", Map.of("uuid", "RINCON_1", "port", "x"))));

        assertThat(settings.uuid()).isEqualTo("RINCON_1");
        assertThat(settings.port()).isEqualTo(SonosEndpoints.DEFAULT_PORT);
    }

    @Test
    void withoutAStoredPortTheSonosPortIsUsed() {
        assertThat(SonosSettings.of(device(Map.of("sonos", Map.of("uuid", "RINCON_1")))).port())
                .isEqualTo(SonosEndpoints.DEFAULT_PORT);
    }

    @Test
    void aDeviceWithoutTheAdapterHasNoSettings() {
        Device upnpOnly = device(Map.of("upnp", Map.of("udn", "uuid:1")));

        assertThatThrownBy(() -> SonosSettings.of(upnpOnly))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Device sonos-kitchen has no sonos adapter");
    }

    @Test
    void aSpeakerWithoutAUuidStoresOnlyItsPort() {
        assertThat(new SonosSettings(null, 1443).toMap()).containsExactly(Map.entry("port", "1443"));
    }
}
