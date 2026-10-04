package dev.andre.homecontrol.adapters.upnp;

import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UpnpSettingsTest {

    private static Device device(Map<String, Map<String, String>> adapters) {
        return new Device("renderer", "Kitchen Speaker", DeviceKind.UPNP, "192.168.1.20", adapters,
                Instant.parse("2026-10-04T12:00:00Z"));
    }

    @Test
    void readsTheStoredUdnLocationAndModel() {
        UpnpSettings settings = UpnpSettings.of(device(Map.of("upnp", Map.of("udn", "uuid:1",
                "location", "http://192.168.1.20:49152/description.xml", "model", "StreamBox"))));

        assertThat(settings.udn()).isEqualTo("uuid:1");
        assertThat(settings.location()).isEqualTo(URI.create("http://192.168.1.20:49152/description.xml"));
        assertThat(settings.model()).isEqualTo("StreamBox");
        assertThat(settings.toMap()).containsOnlyKeys("udn", "location", "model");
    }

    @Test
    void aStoredLocationThatIsNoUriIsLeftOut() {
        UpnpSettings settings = UpnpSettings.of(device(Map.of("upnp", Map.of("udn", "uuid:1",
                "location", "http://bad host/description.xml"))));

        assertThat(settings.location()).isNull();
        assertThat(settings.model()).isNull();
        assertThat(settings.toMap()).containsOnlyKeys("udn");
    }

    @Test
    void aDeviceWithoutTheAdapterHasNoSettings() {
        Device sonosOnly = device(Map.of("sonos", Map.of("uuid", "RINCON_1")));

        assertThatThrownBy(() -> UpnpSettings.of(sonosOnly))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Device renderer has no upnp adapter");
    }

    @Test
    void nothingKnownWritesNoSettings() {
        assertThat(new UpnpSettings(null, null, null).toMap()).isEmpty();
    }
}
