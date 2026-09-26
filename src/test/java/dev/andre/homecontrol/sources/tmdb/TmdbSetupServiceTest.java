package dev.andre.homecontrol.sources.tmdb;

import dev.andre.homecontrol.security.Argon2PasswordHasher;
import dev.andre.homecontrol.security.LoginRequiredException;
import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.security.PasswordRejectedException;
import dev.andre.homecontrol.storage.JsonFileSourceSettings;
import dev.andre.homecontrol.storage.SecretKeySource;
import dev.andre.homecontrol.storage.SecretStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TmdbSetupServiceTest {

    static final String LOGIN_PASSWORD = "household pw 1";
    static final Instant NOW = Instant.parse("2026-09-16T10:00:00Z");

    @TempDir
    Path dir;

    SecretStore secretStore;
    LoginService loginService;
    JsonFileSourceSettings sources;
    TmdbClient client;
    TmdbSetupService setup;
    FakeTmdbServer fake;

    @BeforeEach
    void setUp() throws IOException {
        SecureRandom random = new SecureRandom();
        secretStore = new SecretStore(dir.resolve("secrets.json"),
                new SecretKeySource(null, dir.resolve("secret.key"), random), random);
        loginService = new LoginService(secretStore, new Argon2PasswordHasher(random), random);
        sources = new JsonFileSourceSettings(dir.resolve("sources.json"));
        fake = new FakeTmdbServer().withStandardResponses();
        TmdbProperties properties = new TmdbProperties(true, fake.apiBase(), null, 1, 2, 20, 40,
                Duration.ofHours(24), Duration.ofHours(24), null);
        client = new TmdbClient(properties);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        setup = new TmdbSetupService(client, sources, secretStore, loginService, clock);
    }

    @AfterEach
    void tearDown() {
        fake.close();
    }

    @Test
    void connectsWithABearerTokenAndSetsTheLoginPassword() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        TmdbSettings result = setup.connect(
                new TmdbSetupService.ConnectRequest(FakeTmdbServer.READ_TOKEN, LOGIN_PASSWORD, LOGIN_PASSWORD), request);

        assertThat(result.credentialKind()).isEqualTo(TmdbCredential.Kind.BEARER);
        assertThat(result.connectedAt()).isEqualTo(NOW);
        assertThat(secretStore.secret(TmdbSettings.CREDENTIAL_SECRET)).contains(FakeTmdbServer.READ_TOKEN);
        assertThat(sources.get(TmdbSettings.SOURCE_ID)).containsEntry("credentialKind", "BEARER")
                .containsEntry("connectedAt", "2026-09-16T10:00:00Z");
        assertThat(loginService.loginRequired()).isTrue();
        assertThat(fake.count("GET", "/3/authentication")).isEqualTo(1);
        assertThat(fake.last("GET", "/3/authentication").header("authorization"))
                .isEqualTo("Bearer " + FakeTmdbServer.READ_TOKEN);
    }

    @Test
    void connectsWithAnApiKey() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        TmdbSettings result = setup.connect(
                new TmdbSetupService.ConnectRequest(FakeTmdbServer.API_KEY, LOGIN_PASSWORD, LOGIN_PASSWORD), request);

        assertThat(result.credentialKind()).isEqualTo(TmdbCredential.Kind.API_KEY);
        assertThat(secretStore.secret(TmdbSettings.CREDENTIAL_SECRET)).contains(FakeTmdbServer.API_KEY);
    }

    @Test
    void aRejectedCredentialStoresNothing() {
        fake.respond("GET", "/3/authentication", 401, "authentication-invalid.json");
        MockHttpServletRequest request = new MockHttpServletRequest();

        var preparedArg96_0 = new TmdbSetupService.ConnectRequest(FakeTmdbServer.READ_TOKEN, LOGIN_PASSWORD, LOGIN_PASSWORD);
        assertThatThrownBy(() -> setup.connect(preparedArg96_0, request))
                .isInstanceOf(TmdbException.class)
                .extracting(e -> ((TmdbException) e).kind())
                .isEqualTo(TmdbException.Kind.UNAUTHORIZED);

        assertThat(secretStore.hasSecrets()).isFalse();
        assertThat(setup.settings()).isEmpty();
        assertThat(loginService.loginRequired()).isFalse();
    }

    @Test
    void aMalformedCredentialIsInvalidInput() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        var preparedArg111_0 = new TmdbSetupService.ConnectRequest("nope", LOGIN_PASSWORD, LOGIN_PASSWORD);
        assertThatThrownBy(() -> setup.connect(preparedArg111_0, request))
                .isInstanceOf(TmdbException.class)
                .hasMessage("Paste the API Read Access Token or the API key from your TMDB account settings")
                .extracting(e -> ((TmdbException) e).kind())
                .isEqualTo(TmdbException.Kind.INVALID_INPUT);

        assertThat(fake.requests()).isEmpty();
    }

    @Test
    void theFirstSecretNeedsAGoodPassword() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        var preparedArg125_0 = new TmdbSetupService.ConnectRequest(FakeTmdbServer.READ_TOKEN, "short", "short");
        assertThatThrownBy(() -> setup.connect(preparedArg125_0, request))
                .isInstanceOf(PasswordRejectedException.class);

        assertThat(secretStore.hasSecrets()).isFalse();
    }

    @Test
    void laterConnectsNeedALoggedInBrowser() {
        setup.connect(new TmdbSetupService.ConnectRequest(FakeTmdbServer.READ_TOKEN, LOGIN_PASSWORD, LOGIN_PASSWORD),
                new MockHttpServletRequest());

        var preparedArg137_0 = new TmdbSetupService.ConnectRequest(FakeTmdbServer.API_KEY, null, null);
        var preparedArg137_1 = new MockHttpServletRequest();
        assertThatThrownBy(() -> setup.connect(preparedArg137_0, preparedArg137_1))
                .isInstanceOf(LoginRequiredException.class);
    }

    @Test
    void checkSaysWhatWorks() {
        setup.connect(new TmdbSetupService.ConnectRequest(FakeTmdbServer.READ_TOKEN, LOGIN_PASSWORD, LOGIN_PASSWORD),
                new MockHttpServletRequest());

        assertThat(setup.check()).isEqualTo("TMDB accepted the read access token");
    }

    @Test
    void checkFailsWhenNotConnected() {
        assertThatThrownBy(setup::check)
                .isInstanceOf(TmdbException.class)
                .hasMessage("TMDB is not connected")
                .extracting(e -> ((TmdbException) e).kind())
                .isEqualTo(TmdbException.Kind.INVALID_INPUT);
    }

    @Test
    void disconnectRemovesSecretAndSettings() {
        setup.connect(new TmdbSetupService.ConnectRequest(FakeTmdbServer.READ_TOKEN, LOGIN_PASSWORD, LOGIN_PASSWORD),
                new MockHttpServletRequest());

        setup.disconnect();

        assertThat(setup.credential()).isEmpty();
        assertThat(setup.settings()).isEmpty();
        assertThat(loginService.loginRequired()).isFalse();
    }

    @Test
    void credentialNeedsBothSettingsAndSecret() {
        setup.connect(new TmdbSetupService.ConnectRequest(FakeTmdbServer.READ_TOKEN, LOGIN_PASSWORD, LOGIN_PASSWORD),
                new MockHttpServletRequest());

        loginService.removeSecrets(java.util.List.of(TmdbSettings.CREDENTIAL_SECRET));

        assertThat(setup.credential()).isEmpty();
    }
}
