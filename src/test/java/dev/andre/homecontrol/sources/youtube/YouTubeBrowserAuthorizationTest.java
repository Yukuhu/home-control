package dev.andre.homecontrol.sources.youtube;

import dev.andre.homecontrol.storage.JsonFileSourceSettings;
import dev.andre.homecontrol.storage.LoginCredential;
import dev.andre.homecontrol.storage.SecretKeySource;
import dev.andre.homecontrol.storage.SecretStore;
import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.security.Argon2PasswordHasher;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.ForwardedHeaderFilter;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.anyString;

class YouTubeBrowserAuthorizationTest {
    @TempDir Path directory;
    FakeGoogleServer google;
    MutableClock clock;
    SecretStore secrets;
    GoogleTokens tokens;
    JsonFileSourceSettings settings;
    YouTubeAuthorizationService authorization;
    final URI callback = URI.create("https://home.example.com/setup/sources/youtube/callback");

    @BeforeEach
    void setUp() throws Exception {
        google = new FakeGoogleServer();
        google.respond("POST", "/oauth/token", FakeGoogleServer.Canned.fixture(200, "oauth-token-granted.json"));
        clock = MutableClock.at(Instant.parse("2026-09-22T10:00:00Z"));
        var random = new SecureRandom();
        secrets = new SecretStore(directory.resolve("secrets.json"),
                new SecretKeySource(null, directory.resolve("secret.key"), random), random);
        secrets.putFirstSecrets(Map.of(YouTubeSettings.CLIENT_ID, "cid", YouTubeSettings.CLIENT_SECRET, "secret"),
                new LoginCredential("test-hash", "test-version"));
        var client = new GoogleOAuthClient(new YouTubeHttp(google.properties()), URI.create(google.base() + "/oauth"), clock);
        tokens = new GoogleTokens(client, secrets, clock);
        settings = new JsonFileSourceSettings(directory.resolve("sources.json"));
        authorization = new YouTubeAuthorizationService(client, secrets, tokens, settings, clock, false);
    }

    @AfterEach
    void close() { google.close(); }

    String start() {
        URI url = authorization.startBrowser(callback, "browser-session");
        return Arrays.stream(url.getRawQuery().split("&")).filter(s -> s.startsWith("state="))
                .map(s -> URLDecoder.decode(s.substring(6), StandardCharsets.UTF_8)).findFirst().orElseThrow();
    }

    @Test
    void expiredBrowserConsentCannotExchangeOrStoreTokens() {
        String state = start();
        clock.advance(Duration.ofMinutes(10));
        authorization.completeBrowser("browser-session", state, "code", null);
        assertThat(authorization.status().state()).isEqualTo(YouTubeAuthorizationService.State.EXPIRED);
        assertThat(google.count("/oauth/token")).isZero();
        assertThat(tokens.hasRefreshToken()).isFalse();
    }

    @Test
    void restartingConsentInvalidatesThePreviousCallback() {
        String old = start();
        String current = start();
        assertThat(current).isNotEqualTo(old);
        assertThatThrownBy(() -> authorization.completeBrowser("browser-session", old, "old-code", null))
                .isInstanceOf(YouTubeException.class);
        assertThat(google.count("/oauth/token")).isZero();
        authorization.completeBrowser("browser-session", current, "new-code", null);
        assertThat(tokens.hasRefreshToken()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"access_token\":\"access-secret\"}",
            "{\"refresh_token\":\"refresh-secret\"}",
            "{\"access_token\":\"access-secret\",\"refresh_token\":\"refresh-secret\",\"scope\":\"openid\"}"
    })
    void incompleteOrInsufficientGrantsPreserveTheExistingAccount(String body) {
        secrets.putSecrets(Map.of(YouTubeSettings.REFRESH_TOKEN, "old-refresh"));
        google.respond("POST", "/oauth/token", FakeGoogleServer.Canned.json(200, body));
        String state = start();
        authorization.completeBrowser("browser-session", state, "code-secret", null);
        assertThat(authorization.status().state()).isEqualTo(YouTubeAuthorizationService.State.FAILED);
        assertThat(authorization.status().message()).doesNotContain("access-secret", "refresh-secret", "code-secret");
        assertThat(secrets.secret(YouTubeSettings.REFRESH_TOKEN)).contains("old-refresh");
        assertThatThrownBy(() -> authorization.completeBrowser("browser-session", state, "code-secret", null))
                .isInstanceOf(YouTubeException.class);
        assertThat(google.count("/oauth/token")).isEqualTo(1);
    }

    @Test
    void providerErrorsDoNotEchoSubmittedSecrets() {
        google.respond("POST", "/oauth/token", FakeGoogleServer.Canned.json(400,
                "{\"error\":\"code-secret\",\"error_description\":\"secret\"}"));
        authorization.completeBrowser("browser-session", start(), "code-secret", null);
        assertThat(authorization.status().state()).isEqualTo(YouTubeAuthorizationService.State.FAILED);
        assertThat(authorization.status().message()).doesNotContain("secret");
        assertThat(tokens.hasRefreshToken()).isFalse();
    }

    @Test
    void missingCodeFailsWithoutCallingGoogle() {
        authorization.completeBrowser("browser-session", start(), null, null);
        assertThat(authorization.status().state()).isEqualTo(YouTubeAuthorizationService.State.FAILED);
        assertThat(google.count("/oauth/token")).isZero();
    }

    @Test
    void disconnectCannotLeaveANewSignInUsingDeletedCredentials() throws Exception {
        secrets.putSecrets(Map.of(YouTubeSettings.REFRESH_TOKEN, "refresh-token"));
        var revoking = new CountDownLatch(1);
        var finishRevoke = new CountDownLatch(1);
        var oauth = mock(GoogleOAuthClient.class);
        doAnswer(call -> {
            revoking.countDown();
            assertThat(finishRevoke.await(5, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(oauth).revoke(anyString());
        var random = new SecureRandom();
        var login = new LoginService(secrets, new Argon2PasswordHasher(random), random);
        var service = new YouTubeSetupService(secrets, login, settings, oauth, tokens, authorization,
                mock(ObjectProvider.class), mock(QuotaLedger.class), mock(ObjectProvider.class),
                mock(ObjectProvider.class), mock(ObjectProvider.class));
        var disconnectError = new AtomicReference<Throwable>();
        Thread disconnect = Thread.ofPlatform().start(() -> {
            try { service.disconnect(); } catch (Throwable failure) { disconnectError.set(failure); }
        });
        assertThat(revoking.await(5, TimeUnit.SECONDS)).isTrue();
        var startResult = new AtomicReference<Object>();
        Thread start = Thread.ofPlatform().unstarted(() -> {
            try { startResult.set(authorization.startBrowser(callback, "browser-session")); }
            catch (YouTubeException failure) { startResult.set(failure); }
        });
        try {
            start.start();
            await().atMost(Duration.ofSeconds(3)).until(() -> start.getState() == Thread.State.BLOCKED || startResult.get() != null);
        } finally {
            finishRevoke.countDown();
            disconnect.join(5000);
            start.join(5000);
        }
        assertThat(disconnectError.get()).isNull();
        assertThat(startResult.get()).isInstanceOf(YouTubeException.class);
        assertThat(authorization.status().state()).isEqualTo(YouTubeAuthorizationService.State.IDLE);
        assertThat(tokens.hasRefreshToken()).isFalse();
    }

    @Test
    void aDifferentAccountClearsTheOldLibraryButPreservesDeviceSettings() {
        settings.put(YouTubeSettings.SOURCE_ID, new YouTubeSettings(clock.instant(), "old-channel", "Old account", true,
                Map.of("PLold", "Old playlist"), Set.of("tv"), "remote").toMap());
        authorization.completeBrowser("browser-session", start(), "code", null);
        var connected = YouTubeSettings.from(settings.get(YouTubeSettings.SOURCE_ID));
        assertThat(connected.channelId()).isNull();
        assertThat(connected.playlists()).isEmpty();
        assertThat(connected.watchLater()).isFalse();
        assertThat(connected.loungeDevices()).containsExactly("tv");
        assertThat(connected.loungeRemoteId()).isEqualTo("remote");
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://192.168.1.10:8080", "https://192.168.1.10", "http://home.example.com", "https://home.local"})
    void unsupportedBrowserOriginsExplainTheDeviceAlternative(String origin) {
        var preparedArg191_0 = URI.create(origin + "/setup/sources/youtube/callback");
        assertThatThrownBy(() -> YouTubeOAuthCallback.requireSupported(preparedArg191_0))
                .isInstanceOf(YouTubeException.class).hasMessageContaining("device code");
    }

    @Test
    void callbackUsesTheProxyOriginWhenForwardedHeadersAreEnabled() throws Exception {
        var request = new MockHttpServletRequest("GET", "/setup");
        request.setServerName("backend");
        request.setServerPort(8080);
        request.addHeader("X-Forwarded-Host", "home.example.com");
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-Port", "443");
        var result = new AtomicReference<URI>();
        new ForwardedHeaderFilter().doFilter(request, new MockHttpServletResponse(),
                (req, res) -> result.set(YouTubeOAuthCallback.uri((jakarta.servlet.http.HttpServletRequest) req)));
        assertThat(result.get()).isEqualTo(callback);
        assertThat(YouTubeOAuthCallback.supported(result.get())).isTrue();
    }
}
