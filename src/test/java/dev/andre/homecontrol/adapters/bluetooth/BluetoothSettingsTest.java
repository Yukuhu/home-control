package dev.andre.homecontrol.adapters.bluetooth;

import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BluetoothSettingsTest {

    @Test
    void normalizesAndRoundTrips() {
        BluetoothSettings settings = new BluetoothSettings("aa-bb-cc-dd-ee-ff", "00:1a:7d:da:71:13", null);
        assertThat(settings.address()).isEqualTo("AA:BB:CC:DD:EE:FF");
        assertThat(settings.adapter()).isEqualTo("00:1A:7D:DA:71:13");
        assertThat(settings.audioDevice()).isEqualTo("");
        assertThat(settings.toMap()).containsExactly(Map.entry("address", "AA:BB:CC:DD:EE:FF"),
                Map.entry("adapter", "00:1A:7D:DA:71:13"));

        BluetoothSettings withAudio = settings.withAudioDevice("pulse/bluez_output.AA_BB_CC_DD_EE_FF.1");
        assertThat(withAudio.toMap()).containsEntry("audioDevice", "pulse/bluez_output.AA_BB_CC_DD_EE_FF.1");

        Device device = new Device("bluetooth-aa-bb-cc-dd-ee-ff", "JBL", DeviceKind.BLUETOOTH, "AA:BB:CC:DD:EE:FF",
                Map.of(BluetoothSettings.ADAPTER_ID, withAudio.toMap()), Instant.now());
        assertThat(BluetoothSettings.of(device)).isEqualTo(withAudio);
    }

    @Test
    void deviceIdIsDerivedFromTheMac() {
        assertThat(BluetoothSettings.deviceId("AA:BB:CC:DD:EE:FF")).isEqualTo("bluetooth-aa-bb-cc-dd-ee-ff");
    }

    @Test
    void refusesBadInput() {
        assertThatThrownBy(() -> new BluetoothSettings("nope", null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Not a MAC address");

        assertThatThrownBy(() -> new BluetoothSettings("AA:BB:CC:DD:EE:FF", null, "pulse/x; rm -rf"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("audio device");
        assertThatThrownBy(() -> new BluetoothSettings("AA:BB:CC:DD:EE:FF", null, "pulse/x y"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("audio device");
        assertThatThrownBy(() -> new BluetoothSettings("AA:BB:CC:DD:EE:FF", null, "pulse/x\ny"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("audio device");
    }
}
