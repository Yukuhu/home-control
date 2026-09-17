package dev.andre.homecontrol.adapters.tizen;

import dev.andre.homecontrol.adapters.net.InsecureTls;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class TizenRestTest {

    private FakeTizenServer fake;
    private TizenRest rest;

    static TizenProperties properties(FakeTizenServer fake) {
        return new TizenProperties(true, fake.port(), fake.httpPort(), fake.httpPort(), "Home Control", 2, 2, 2, 1, 0);
    }

    @BeforeEach
    void setUp() throws IOException {
        fake = new FakeTizenServer();
        rest = new TizenRest(InsecureTls.httpClient(Duration.ofSeconds(2)), properties(fake));
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
        assertThat(info.macAddress()).contains("70:2A:D5:01:02:03");
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
        assertThat(info.macAddress()).isEmpty();
    }

    @Test
    void anUnreachableTvHasNoInfo() {
        fake.setRestAvailable(false);
        long started = System.nanoTime();

        assertThat(rest.deviceInfo("127.0.0.1")).isEmpty();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
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
