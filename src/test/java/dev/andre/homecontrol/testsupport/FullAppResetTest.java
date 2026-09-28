package dev.andre.homecontrol.testsupport;

import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.security.LoginRateLimiter;
import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.sources.pinned.PinnedShortcuts;
import dev.andre.homecontrol.sources.sports.SportsSettings;
import dev.andre.homecontrol.sources.sports.SportsSettingsService;
import dev.andre.homecontrol.sources.youtube.QuotaLedger;
import dev.andre.homecontrol.storage.SecretStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** One reset brings the shared application back to a fresh install, whatever a test class left behind. */
class FullAppResetTest extends FullAppTest {

    private static final String PASSWORD = "reset-test-password";

    @Autowired
    ApplicationContext context;

    @Autowired
    DeviceManager devices;

    @Autowired
    LoginService login;

    @Autowired
    SecretStore secrets;

    @Autowired
    LoginRateLimiter limiter;

    @Autowired
    PinnedShortcuts pins;

    @Autowired
    SportsSettingsService sports;

    @Autowired
    QuotaLedger quota;

    @Test
    void resetBringsTheApplicationBackToAFreshInstall() throws Exception {
        devices.adopt(new Device("reset-probe", "Probe", DeviceKind.ANDROID_TV, "127.0.0.1",
                Map.of("androidtv", Map.of()), Instant.now()));
        login.storeSecrets(Map.of("jellyfin.token", "0123456789abcdef"), PASSWORD, PASSWORD, new MockHttpServletRequest());
        for (int i = 0; i < 5; i++) {
            limiter.failed("127.0.0.1");
        }
        pins.add("https://www.netflix.com/title/1", "Stranger Things");
        sports.update(current -> current.withTimeZone("Europe/Berlin"));
        quota.charge(QuotaLedger.Call.VIDEOS_LIST);
        try (HttpClient http = HttpClient.newHttpClient()) {
            http.send(HttpRequest.newBuilder(SharedFakes.tmdb().url().resolve("/3/probe")).build(),
                    HttpResponse.BodyHandlers.discarding());
        }
        assertThat(login.loginRequired()).isTrue();
        assertThat(limiter.blockedFor("127.0.0.1")).isPresent();
        assertThat(dataDir().resolve("pinned.json")).exists();
        assertThat(SharedFakes.tmdb().requests()).isNotEmpty();

        FullAppReset.reset(context);

        assertThat(devices.devices()).isEmpty();
        assertThat(login.loginRequired()).isFalse();
        assertThat(secrets.names()).isEmpty();
        assertThat(limiter.blockedFor("127.0.0.1")).isEmpty();
        assertThat(pins.all()).isEmpty();
        assertThat(sports.current()).isEqualTo(SportsSettings.empty());
        assertThat(quota.usage().units()).isZero();
        for (String file : new String[]{"secrets.json", "secret.key", "sources.json", "sports.json", "pinned.json",
                "youtube-quota.json"}) {
            assertThat(dataDir().resolve(file)).doesNotExist();
        }
        assertThat(SharedFakes.tmdb().requests()).isEmpty();
    }
}
