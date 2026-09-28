package dev.andre.homecontrol.adapters.bluetooth;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class BluetoothTimingsTest {

    @Test
    void fromTakesTheConfiguredSeconds() {
        assertThat(BluetoothTimings.from(BluetoothProperties.defaults()))
                .isEqualTo(new BluetoothTimings(Duration.ofSeconds(5), Duration.ofSeconds(1)));
    }
}
