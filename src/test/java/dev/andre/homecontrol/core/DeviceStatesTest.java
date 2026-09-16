package dev.andre.homecontrol.core;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceStatesTest {

    private static final Instant EARLY = Instant.parse("2026-09-16T10:00:00Z");
    private static final Instant LATE = Instant.parse("2026-09-16T10:05:00Z");

    @Test
    void noAdaptersMeansDisconnected() {
        assertThat(DeviceStates.compose(List.of()).status()).isEqualTo(DeviceStatus.DISCONNECTED);
    }

    @Test
    void aSingleAdaptersStateIsTheDevicesState() {
        DeviceState only = new DeviceState(DeviceStatus.CONNECTED, true, "com.netflix.ninja", 12, 100, false, EARLY);

        assertThat(DeviceStates.compose(List.of(only))).isEqualTo(only);
    }

    @Test
    void theSecondaryFillsInWhatThePrimaryDoesNotReport() {
        DeviceState androidTv = new DeviceState(DeviceStatus.CONNECTED, true, null, 0, 0, false, EARLY);
        DeviceState cast = new DeviceState(DeviceStatus.CONNECTED, true, "Default Media Receiver", 30, 100, true, LATE);

        DeviceState composed = DeviceStates.compose(List.of(androidTv, cast));

        assertThat(composed.status()).isEqualTo(DeviceStatus.CONNECTED);
        assertThat(composed.currentApp()).isEqualTo("Default Media Receiver");
        assertThat(composed.volumeLevel()).isEqualTo(30);
        assertThat(composed.volumeMax()).isEqualTo(100);
        assertThat(composed.muted()).isTrue();
        assertThat(composed.updatedAt()).isEqualTo(LATE);
    }

    @Test
    void thePrimaryWinsStatusPowerAppAndVolumeWhenItReportsThem() {
        DeviceState androidTv = new DeviceState(DeviceStatus.UNPAIRED, false, "com.netflix.ninja", 12, 100, false, LATE);
        DeviceState cast = new DeviceState(DeviceStatus.CONNECTED, true, "Netflix", 50, 100, true, EARLY);

        DeviceState composed = DeviceStates.compose(List.of(androidTv, cast));

        assertThat(composed.status()).isEqualTo(DeviceStatus.UNPAIRED);
        assertThat(composed.powerOn()).isFalse();
        assertThat(composed.currentApp()).isEqualTo("com.netflix.ninja");
        assertThat(composed.volumeLevel()).isEqualTo(12);
        assertThat(composed.muted()).isFalse();
    }
}
