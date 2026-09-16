package dev.andre.homecontrol.adapters.cast.protocol;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ReceiverStatusTest {

    private static ReceiverStatus fixture(String name) throws Exception {
        return ReceiverStatus.parse(CastPayloads.parse(
                Files.readString(Path.of("src/test/resources/fixtures/cast/" + name))).path("status"));
    }

    @Test
    void theIdleScreenIsNotAForegroundApp() throws Exception {
        ReceiverStatus status = fixture("receiver-status-backdrop.json");

        assertThat(status.volumeLevel()).isEqualTo(0.25);
        assertThat(status.volumePercent()).isEqualTo(25);
        assertThat(status.muted()).isTrue();
        assertThat(status.standBy()).isFalse();
        assertThat(status.applications()).singleElement().satisfies(app -> assertThat(app.idleScreen()).isTrue());
        assertThat(status.foregroundApp()).isEmpty();
    }

    @Test
    void aRunningReceiverAppExposesItsSessionTransportAndNamespaces() throws Exception {
        ReceiverStatus status = fixture("receiver-status-default-media-receiver.json");

        ReceiverStatus.ReceiverApp app = status.foregroundApp().orElseThrow();
        assertThat(app.appId()).isEqualTo("CC1AD845");
        assertThat(app.displayName()).isEqualTo("Default Media Receiver");
        assertThat(app.sessionId()).isEqualTo("b3f1b2a4-9c55-4d0e-8f5f-2f8d7e3c9a01");
        assertThat(app.transportId()).isEqualTo("b3f1b2a4-9c55-4d0e-8f5f-2f8d7e3c9a01");
        assertThat(app.speaks(CastNamespaces.MEDIA)).isTrue();
        assertThat(status.app("CC1AD845")).contains(app);
        assertThat(status.app("233637DE")).isEmpty();
        assertThat(status.standBy()).isTrue();
        assertThat(status.volumePercent()).isEqualTo(100);
    }

    @Test
    void aStatusWithoutApplicationsOrVolumeParsesToDefaults() {
        ReceiverStatus status = ReceiverStatus.parse(CastPayloads.parse("{}"));

        assertThat(status.applications()).isEmpty();
        assertThat(status.volumeLevel()).isZero();
        assertThat(status.muted()).isFalse();
    }
}
