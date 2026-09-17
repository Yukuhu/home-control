package dev.andre.homecontrol.adapters.upnp.protocol;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class VolumeRangeTest {

    private static byte[] scpd(String max) throws IOException {
        return Files.readString(Path.of("src/test/resources/fixtures/upnp/rendering-control-scpd.xml"))
                .replace("{volumeMax}", max).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void readsTheVolumeMaximumFromTheScpd() throws IOException {
        assertThat(VolumeRange.maximum(scpd("60"))).isEqualTo(60);
        assertThat(VolumeRange.maximum(new String(scpd("60"), StandardCharsets.UTF_8)
                .replace("<name>Volume</name>", "<name>Loudness</name>").getBytes(StandardCharsets.UTF_8))).isEqualTo(100);
        assertThat(VolumeRange.maximum("garbage".getBytes(StandardCharsets.UTF_8))).isEqualTo(100);
        assertThat(VolumeRange.maximum(scpd("0"))).isEqualTo(100);
    }

    @Test
    void convertsBetweenPercentAndDeviceUnits() {
        assertThat(VolumeRange.toDevice(50, 60)).isEqualTo(30);
        assertThat(VolumeRange.toDevice(100, 60)).isEqualTo(60);
        assertThat(VolumeRange.toPercent(30, 60)).isEqualTo(50);
        assertThat(VolumeRange.toPercent(80, 60)).isEqualTo(100);
        assertThat(VolumeRange.toPercent(-3, 100)).isZero();
        assertThat(VolumeRange.toPercent(5, 0)).isEqualTo(5);
    }
}
