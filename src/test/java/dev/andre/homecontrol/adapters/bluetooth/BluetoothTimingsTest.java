package dev.andre.homecontrol.adapters.bluetooth;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class BluetoothTimingsTest {

    @Test
    void fromTakesTheConfiguredSeconds() {
        // The production defaults keep poll interval and player-start timeout equal (5 s), so this alone would
        // not catch a swap; the second assertion gives every int field, including ones from() does not read, its
        // own distinct value so misreading one of those instead is also caught.
        assertThat(BluetoothTimings.from(BluetoothProperties.defaults()))
                .isEqualTo(new BluetoothTimings(Duration.ofSeconds(5), Duration.ofSeconds(1)));

        var properties = new BluetoothProperties(true, BluetoothProperties.DEFAULT_DBUS_ADDRESS, "hci0", 11, 46, 6, 2,
                true, "mpv", null, "", 51, 7, 16, 4, 31);
        assertThat(BluetoothTimings.from(properties))
                .isEqualTo(new BluetoothTimings(Duration.ofSeconds(6), Duration.ofSeconds(2)));
    }
}
