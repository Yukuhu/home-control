package dev.andre.homecontrol.device;

import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceMergeTest {

    private static final Instant NOW = Instant.parse("2026-09-16T12:00:00Z");

    @Test
    void addsTheAdapterToTheDeviceAtTheSameHostAndKeepsItsIdentity() {
        Device registered = new Device("192-168-1-60", "Living Room", DeviceKind.ANDROID_TV, "192.168.1.60",
                Map.of("androidtv", Map.of("port", "6466")), Instant.EPOCH);

        Device attached = DeviceMerge.attach(List.of(registered), "192.168.1.60", "[LG] webOS TV", DeviceKind.WEBOS,
                "webos", Map.of("clientKey", "k"), NOW, String::equalsIgnoreCase);

        assertThat(attached.id()).isEqualTo("192-168-1-60");
        assertThat(attached.name()).isEqualTo("Living Room");
        assertThat(attached.kind()).isEqualTo(DeviceKind.ANDROID_TV);
        assertThat(List.copyOf(attached.adapters().keySet())).containsExactly("androidtv", "webos");
        assertThat(attached.adapterSettings("webos")).containsEntry("clientKey", "k");
        assertThat(attached.lastSeen()).isEqualTo(NOW);
    }

    @Test
    void aDeviceThatAlreadyHasTheAdapterIsStillTheMatch() {
        Device registered = new Device("webos-192-168-1-60", "LG", DeviceKind.WEBOS, "192.168.1.60",
                Map.of("webos", Map.of("clientKey", "old", "macAddress", "A8:23:FE:01:02:03",
                        "macAddressManual", "true")),
                Instant.EPOCH);

        Device attached = DeviceMerge.attach(List.of(registered), "192.168.1.60", "LG", DeviceKind.WEBOS,
                "webos", Map.of("clientKey", "new"), NOW, String::equalsIgnoreCase);

        assertThat(attached.id()).isEqualTo("webos-192-168-1-60");
        assertThat(attached.adapterSettings("webos")).containsExactlyInAnyOrderEntriesOf(Map.of(
                "clientKey", "new", "macAddress", "A8:23:FE:01:02:03", "macAddressManual", "true"));
    }

    @Test
    void registersANewDeviceWithTheAdapterPrefixedIdWhenNoHostMatches() {
        Device registered = new Device("existing", "Something", DeviceKind.CAST, "192.168.1.60",
                Map.of("cast", Map.of()), Instant.EPOCH);

        Device attached = DeviceMerge.attach(List.of(registered), "192.168.1.61", "Samsung", DeviceKind.TIZEN,
                "tizen", Map.of(), NOW, String::equalsIgnoreCase);

        assertThat(attached.id()).isEqualTo("tizen-192-168-1-61");
        assertThat(attached.name()).isEqualTo("Samsung");
        assertThat(attached.kind()).isEqualTo(DeviceKind.TIZEN);
        assertThat(attached.host()).isEqualTo("192.168.1.61");
        assertThat(List.copyOf(attached.adapters().keySet())).containsExactly("tizen");
    }

    @Test
    void aNameMatchIsNotAHostMatch() {
        Device registered = new Device("x", "[LG] webOS TV", DeviceKind.WEBOS, "192.168.1.60",
                Map.of("cast", Map.of()), Instant.EPOCH);

        Device attached = DeviceMerge.attach(List.of(registered), "192.168.1.99", "[LG] webOS TV", DeviceKind.WEBOS,
                "webos", Map.of(), NOW, String::equalsIgnoreCase);

        assertThat(attached.id()).isNotEqualTo("x");
        assertThat(attached.host()).isEqualTo("192.168.1.99");
    }
}
