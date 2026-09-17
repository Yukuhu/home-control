package dev.andre.homecontrol.adapters.webos;

import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.TvInput;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WebOsPayloadsTest {

    private static JsonNode fixture(String name) throws IOException {
        return SsapMessages.JSON.readTree(Files.readString(Path.of("src/test/resources/fixtures/webos/" + name)));
    }

    private static JsonNode json(String text) {
        return SsapMessages.JSON.readTree(text);
    }

    @Test
    void readsTheWebOs6VolumeShape() throws IOException {
        DeviceState state = WebOsPayloads.volume(DeviceState.initial(), fixture("volume-webos6.json"));

        assertThat(state.volumeLevel()).isEqualTo(12);
        assertThat(state.volumeMax()).isEqualTo(100);
        assertThat(state.muted()).isFalse();
    }

    @Test
    void readsTheOlderFlatVolumeShape() throws IOException {
        DeviceState state = WebOsPayloads.volume(DeviceState.initial(), fixture("volume-webos4.json"));

        assertThat(state.volumeLevel()).isEqualTo(7);
        assertThat(state.volumeMax()).isEqualTo(100);
        assertThat(state.muted()).isTrue();
    }

    @Test
    void prefersTheInterfaceCarryingTheTvsAddress() throws IOException {
        assertThat(WebOsPayloads.macAddress(fixture("connection-info.json"), "127.0.0.1")).contains("A8:23:FE:01:02:03");
    }

    @Test
    void fallsBackToTheConnectedInterface() {
        JsonNode payload = json("{\"wiredInfo\":{\"macAddress\":\"a8:23:fe:01:02:03\",\"state\":\"disconnected\"},"
                + "\"wifiInfo\":{\"macAddress\":\"a8:23:fe:01:02:04\",\"state\":\"connected\"}}");

        assertThat(WebOsPayloads.macAddress(payload, "10.0.0.9")).contains("A8:23:FE:01:02:04");
    }

    @Test
    void anUnparseableMacIsIgnored() {
        assertThat(WebOsPayloads.macAddress(json("{\"wiredInfo\":{\"macAddress\":\"unknown\"}}"), "10.0.0.9")).isEmpty();
    }

    @Test
    void listsInputsInTheTvsOrder() throws IOException {
        assertThat(WebOsPayloads.inputs(fixture("external-inputs.json")))
                .containsExactly(new TvInput("HDMI_1", "HDMI 1"), new TvInput("HDMI_2", "PlayStation"));
        assertThat(WebOsPayloads.inputs(json("{}"))).isEmpty();
    }
}
