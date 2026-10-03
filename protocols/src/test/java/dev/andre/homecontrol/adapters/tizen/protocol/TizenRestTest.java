package dev.andre.homecontrol.adapters.tizen.protocol;

import dev.andre.homecontrol.adapters.net.InsecureTls;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class TizenRestTest {

    private FakeTizenServer fake;
    private TizenRest rest;

    @BeforeEach
    void setUp() throws IOException {
        fake = new FakeTizenServer();
        rest = new TizenRest(InsecureTls.httpClient(Duration.ofSeconds(2)), fake.options());
    }

    @AfterEach
    void tearDown() {
        fake.close();
    }

    @Test
    void readsTheDeviceInfo() {
        TizenDeviceInfo info = rest.deviceInfo("127.0.0.1").orElseThrow();

        assertThat(info.name()).isEqualTo("[TV] Samsung 8 Series (55)");
        assertThat(info.modelName()).isEqualTo("GU55TU8079UXZG");
        assertThat(info.powerState()).isEqualTo("on");
        assertThat(info.on()).isTrue();
        assertThat(info.wifiMac()).isEqualTo("70:2A:D5:01:02:03");
        assertThat(info.tokenAuthSupport()).isTrue();
    }

    @Test
    void standbyIsNotOn() {
        fake.setPowerState("standby");

        assertThat(rest.deviceInfo("127.0.0.1").orElseThrow().on()).isFalse();
    }

    @Test
    void aSetWithoutPowerStateCountsAsOn() {
        TizenDeviceInfo info = TizenRest.parseDeviceInfo(TizenMessages.JSON.readTree("{\"device\":{\"name\":\"TV\"}}"));

        assertThat(info.on()).isTrue();
        assertThat(info.wifiMac()).isEmpty();
    }

    @Test
    void anUnreachableTvHasNoInfo() {
        fake.setRestAvailable(false);

        assertThat(rest.deviceInfo("127.0.0.1")).isEmpty();
    }

    @Test
    void aTvThatStallsItsAnswerHasNoInfoOnceTheWaitIsOver() {
        fake.stallAnswers();
        TizenRest impatient = new TizenRest(InsecureTls.httpClient(Duration.ofSeconds(2)),
                new TizenOptions(fake.port(), fake.httpPort(), fake.httpPort(), "Home Control", Duration.ofSeconds(2),
                        Duration.ofMillis(500)));

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertThat(impatient.deviceInfo("127.0.0.1")).isEmpty());
    }

    @Test
    void anOversizedBodyIsRefusedButOneJustUnderTheCapIsRead() {
        fake.setDeviceInfoPadding(TizenRest.MAX_BODY_BYTES + 10);
        assertThat(rest.deviceInfo("127.0.0.1")).isEmpty();

        fake.setDeviceInfoPadding(TizenRest.MAX_BODY_BYTES - 4096);
        assertThat(rest.deviceInfo("127.0.0.1")).isPresent();
    }

    @Test
    void appVisibilityComesFromTheApplicationsEndpoint() {
        fake.setVisible(FakeTizenServer.YOUTUBE, true);

        assertThat(rest.appVisible("127.0.0.1", FakeTizenServer.YOUTUBE)).contains(true);
        assertThat(rest.appVisible("127.0.0.1", FakeTizenServer.NETFLIX)).contains(false);
        assertThat(rest.appVisible("127.0.0.1", "nope")).isEmpty();
    }
}
