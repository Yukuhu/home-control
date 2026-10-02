package dev.andre.homecontrol.testsupport;

import org.junit.jupiter.api.Test;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FixturesTest {

    @Test
    void aRecordingIsReadAsText() throws IOException {
        assertThat(Fixtures.read("upnp/didl-track.xml")).contains("Bunny Song");
    }

    @Test
    void aRecordingIsReadAsItsBytes() throws IOException {
        assertThat(Fixtures.bytes("ssdp/lg-description.xml"))
                .startsWith("<?xml".getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void aMissingRecordingFailsWithItsName() {
        assertThatThrownBy(() -> Fixtures.read("upnp/no-such-recording.xml"))
                .isInstanceOf(FileNotFoundException.class)
                .hasMessageContaining("fixtures/upnp/no-such-recording.xml");
    }
}
