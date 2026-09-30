package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.security.Argon2PasswordHasher;
import dev.andre.homecontrol.security.LoginContext;
import dev.andre.homecontrol.security.LoginRequiredException;
import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.security.PasswordRejectedException;
import dev.andre.homecontrol.security.RequestLoginContext;
import dev.andre.homecontrol.storage.JsonFileSourceSettings;
import dev.andre.homecontrol.storage.SecretKeySource;
import dev.andre.homecontrol.storage.SecretStore;
import dev.andre.homecontrol.storage.StorageException;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JellyfinSetupServiceTest {

    static final String LOGIN_PASSWORD = "household pw 1";

    @TempDir
    Path dir;

    SecretStore secretStore;
    LoginService loginService;
    JsonFileSourceSettings sources;
    JellyfinClient client;
    JellyfinSetupService setup;
    FakeJellyfinServer fake;

    @BeforeEach
    void setUp() throws IOException {
        SecureRandom random = new SecureRandom();
        secretStore = new SecretStore(dir.resolve("secrets.json"),
                new SecretKeySource(null, dir.resolve("secret.key"), random), random);
        loginService = new LoginService(secretStore, new Argon2PasswordHasher(random), random);
        sources = new JsonFileSourceSettings(dir.resolve("sources.json"));
        client = new JellyfinClient(new JellyfinProperties(true, Duration.ofSeconds(2), Duration.ofSeconds(5), 20,
                Duration.ofSeconds(30)), "0.8.0");
        setup = new JellyfinSetupService(client, sources, secretStore, loginService);
        fake = new FakeJellyfinServer().withConnectableServer();
    }

    @AfterEach
    void tearDown() {
        try {
            if (client != null) client.close();
        } finally {
            if (fake != null) fake.close();
        }
    }

    private JellyfinSetupService.ConnectRequest passwordRequest(String loginPassword, String loginConfirmation) {
        return new JellyfinSetupService.ConnectRequest(fake.url() + "/", null, JellyfinSettings.AuthMode.PASSWORD,
                "andre", "user pw", null, loginPassword, loginConfirmation);
    }

    @Test
    void connectsWithAPasswordAndStoresOnlyTheToken() throws IOException {
        LoginContext request = new RequestLoginContext(new MockHttpServletRequest(), loginService);

        JellyfinSettings result = setup.connect(passwordRequest(LOGIN_PASSWORD, LOGIN_PASSWORD), request);

        assertThat(result.serverId()).isEqualTo(FakeJellyfinServer.SERVER_ID);
        assertThat(result.serverName()).isEqualTo("nas");
        assertThat(result.serverVersion()).isEqualTo("10.11.2");
        assertThat(result.userId()).isEqualTo(FakeJellyfinServer.USER_ID);
        assertThat(result.userName()).isEqualTo("andre");
        assertThat(result.castReceiverId()).isEqualTo("F007D354");
        assertThat(result.deviceServerUrl()).isEqualTo(result.serverUrl());
        assertThat(result.deviceId()).matches("[0-9a-f]{32}");

        assertThat(secretStore.secret(JellyfinSettings.TOKEN_SECRET)).contains(FakeJellyfinServer.ACCESS_TOKEN);

        String raw = java.nio.file.Files.readString(dir.resolve("sources.json"));
        assertThat(raw).doesNotContain(FakeJellyfinServer.ACCESS_TOKEN).doesNotContain("user pw");

        assertThat(loginService.loginRequired()).isTrue();
        assertThat(request.loggedIn()).isTrue();
    }

    @Test
    void theLoginPasswordIsCheckedBeforeContactingJellyfin() {
        LoginContext request = new RequestLoginContext(new MockHttpServletRequest(), loginService);

        var weakPassword = passwordRequest("short", "short");
        assertThatThrownBy(() -> setup.connect(weakPassword, request))
                .isInstanceOf(PasswordRejectedException.class);

        assertThat(fake.requests()).isEmpty();
        assertThat(setup.settings()).isEmpty();
        assertThat(secretStore.hasSecrets()).isFalse();
    }

    @Test
    void unauthenticatedRequestsAreRejectedBeforeTheServerUrlIsValidated() {
        setup.connect(passwordRequest(LOGIN_PASSWORD, LOGIN_PASSWORD), new RequestLoginContext(new MockHttpServletRequest(), loginService));

        LoginContext unauthenticated = new RequestLoginContext(new MockHttpServletRequest(), loginService);
        JellyfinSetupService.ConnectRequest badUrl = new JellyfinSetupService.ConnectRequest(
                "not a url", null, JellyfinSettings.AuthMode.PASSWORD, "andre", "user pw", null, null, null);

        assertThatThrownBy(() -> setup.connect(badUrl, unauthenticated)).isInstanceOf(LoginRequiredException.class);
    }

    @Test
    void connectsWithAnApiKeyByUserName() {
        fake.respond("GET", "/Users", 200, "users.json");
        LoginContext request = new RequestLoginContext(new MockHttpServletRequest(), loginService);
        JellyfinSetupService.ConnectRequest connectRequest = new JellyfinSetupService.ConnectRequest(
                fake.url() + "/", null, JellyfinSettings.AuthMode.API_KEY, "andre", null, "api-key-123",
                LOGIN_PASSWORD, LOGIN_PASSWORD);

        JellyfinSettings result = setup.connect(connectRequest, request);

        assertThat(result.userId()).isEqualTo(FakeJellyfinServer.USER_ID);
        assertThat(result.castReceiverId()).isEqualTo("6F511C87");
        FakeJellyfinServer.Recorded usersRequest = fake.last("GET", "/Users");
        assertThat(usersRequest.header("authorization")).contains("Token=\"api-key-123\"");
        assertThat(secretStore.secret(JellyfinSettings.TOKEN_SECRET)).contains("api-key-123");
    }

    @Test
    void aSecretPersistenceFailureRevokesTheNewPasswordToken() throws IOException {
        preventSecretPersistence();
        LoginContext request = new RequestLoginContext(new MockHttpServletRequest(), loginService);

        assertThatThrownBy(() -> setup.connect(passwordRequest(LOGIN_PASSWORD, LOGIN_PASSWORD), request))
                .isInstanceOf(StorageException.class).hasMessageContaining("Could not write")
                .hasMessageNotContaining(FakeJellyfinServer.ACCESS_TOKEN).hasMessageNotContaining("user pw");

        assertThat(fake.requests("POST", "/Users/AuthenticateByName")).hasSize(1);
        assertThat(fake.requests("POST", "/Sessions/Logout")).hasSize(1);
        assertThat(fake.last("POST", "/Sessions/Logout").header("authorization"))
                .contains("Token=\"" + FakeJellyfinServer.ACCESS_TOKEN + "\"");
        assertNoConnectionWasPersisted();
    }

    @Test
    void aSecretPersistenceFailureDoesNotRevokeAnExternallyManagedApiKey() throws IOException {
        preventSecretPersistence();
        fake.respond("GET", "/Users", 200, "users.json");
        LoginContext request = new RequestLoginContext(new MockHttpServletRequest(), loginService);
        JellyfinSetupService.ConnectRequest connectRequest = new JellyfinSetupService.ConnectRequest(
                fake.url() + "/", null, JellyfinSettings.AuthMode.API_KEY, "andre", null, "api-key-123",
                LOGIN_PASSWORD, LOGIN_PASSWORD);

        assertThatThrownBy(() -> setup.connect(connectRequest, request))
                .isInstanceOf(StorageException.class).hasMessageContaining("Could not write")
                .hasMessageNotContaining("api-key-123");

        assertThat(fake.requests("GET", "/Users")).hasSize(1);
        assertThat(fake.last("GET", "/Users").header("authorization")).contains("Token=\"api-key-123\"");
        assertThat(fake.requests("POST", "/Sessions/Logout")).isEmpty();
        assertNoConnectionWasPersisted();
    }

    @Test
    void aFailedTokenRevocationPreservesTheSecretPersistenceError() throws IOException {
        preventSecretPersistence();
        fake.respondJson("POST", "/Sessions/Logout", 500, "{}");
        LoginContext request = new RequestLoginContext(new MockHttpServletRequest(), loginService);

        assertThatThrownBy(() -> setup.connect(passwordRequest(LOGIN_PASSWORD, LOGIN_PASSWORD), request))
                .isInstanceOf(StorageException.class).hasMessageContaining("Could not write");

        assertThat(fake.requests("POST", "/Sessions/Logout")).hasSize(1);
        assertNoConnectionWasPersisted();
    }

    private void preventSecretPersistence() throws IOException {
        // Atomic replacement cannot overwrite a nonempty directory, even when tests run as root.
        Path secretFile = Files.createDirectory(dir.resolve("secrets.json"));
        Files.writeString(secretFile.resolve("existing-entry"), "keep");
    }

    private void assertNoConnectionWasPersisted() {
        assertThat(setup.settings()).isEmpty();
        assertThat(setup.connection()).isEmpty();
        assertThat(secretStore.secret(JellyfinSettings.TOKEN_SECRET)).isEmpty();
        assertThat(loginService.loginRequired()).isFalse();
        assertThat(dir.resolve("sources.json")).doesNotExist();
    }

    @Test
    void anUnknownUserIsNamed() {
        fake.respond("GET", "/Users", 200, "users.json");
        LoginContext request = new RequestLoginContext(new MockHttpServletRequest(), loginService);
        JellyfinSetupService.ConnectRequest connectRequest = new JellyfinSetupService.ConnectRequest(
                fake.url() + "/", null, JellyfinSettings.AuthMode.API_KEY, "nobody", null, "api-key-123",
                LOGIN_PASSWORD, LOGIN_PASSWORD);

        assertThatThrownBy(() -> setup.connect(connectRequest, request))
                .isInstanceOf(JellyfinException.class)
                .extracting(e -> ((JellyfinException) e).kind())
                .isEqualTo(ContentSourceException.Kind.UNAUTHORIZED);
        assertThatThrownBy(() -> setup.connect(connectRequest, request))
                .hasMessage("No Jellyfin user named 'nobody'");
        assertThat(setup.settings()).isEmpty();
    }

    @Test
    void reconnectingKeepsTheDeviceIdAndLinksAndNeedsTheLogin() {
        LoginContext first = new RequestLoginContext(new MockHttpServletRequest(), loginService);
        JellyfinSettings connected = setup.connect(passwordRequest(LOGIN_PASSWORD, LOGIN_PASSWORD), first);
        setup.save(connected.withSessionLink("shield", "jf-dev").withPlayer("shield", JellyfinSettings.Player.VLC));

        LoginContext unauthenticated = new RequestLoginContext(new MockHttpServletRequest(), loginService);
        var reconnect = passwordRequest(null, null);
        assertThatThrownBy(() -> setup.connect(reconnect, unauthenticated))
                .isInstanceOf(LoginRequiredException.class);

        JellyfinSettings again = setup.connect(passwordRequest(null, null), first);

        assertThat(again.deviceId()).isEqualTo(connected.deviceId());
        assertThat(again.sessionLinks()).containsEntry("shield", "jf-dev");
        assertThat(again.player("shield")).isEqualTo(JellyfinSettings.Player.VLC);
        assertThat(fake.requests("POST", "/Sessions/Logout")).isEmpty();
    }

    @Test
    void checkReportsServerVersionAndUser() {
        setup.connect(passwordRequest(LOGIN_PASSWORD, LOGIN_PASSWORD), new RequestLoginContext(new MockHttpServletRequest(), loginService));

        assertThat(setup.check()).isEqualTo("Connected to nas (Jellyfin 10.11.2) as Andre");
    }

    @Test
    void disconnectRevokesAPasswordTokenAndKeepsTheLogin() {
        setup.connect(passwordRequest(LOGIN_PASSWORD, LOGIN_PASSWORD), new RequestLoginContext(new MockHttpServletRequest(), loginService));

        setup.disconnect();

        FakeJellyfinServer.Recorded logout = fake.last("POST", "/Sessions/Logout");
        assertThat(logout.header("authorization")).contains("Token=\"" + FakeJellyfinServer.ACCESS_TOKEN + "\"");
        assertThat(setup.settings()).isEmpty();
        assertThat(loginService.loginRequired()).isTrue();
    }

    @Test
    void disconnectStillWorksWhenJellyfinIsDown() {
        setup.connect(passwordRequest(LOGIN_PASSWORD, LOGIN_PASSWORD), new RequestLoginContext(new MockHttpServletRequest(), loginService));
        fake.close();

        setup.disconnect();

        assertThat(setup.settings()).isEmpty();
    }

    @Test
    void disconnectDoesNotRevokeAnExternallyManagedApiKey() {
        fake.respond("GET", "/Users", 200, "users.json");
        LoginContext request = new RequestLoginContext(new MockHttpServletRequest(), loginService);
        setup.connect(new JellyfinSetupService.ConnectRequest(fake.url() + "/", null,
                JellyfinSettings.AuthMode.API_KEY, "andre", null, "api-key-123", LOGIN_PASSWORD, LOGIN_PASSWORD), request);

        setup.disconnect();

        assertThat(fake.requests("POST", "/Sessions/Logout")).isEmpty();
        assertThat(setup.settings()).isEmpty();
        assertThat(setup.connection()).isEmpty();
        assertThat(secretStore.secret(JellyfinSettings.TOKEN_SECRET)).isEmpty();
        assertThat(loginService.loginRequired()).isTrue();
    }

    @Test
    void linkingASessionReplacesItsPreviousDeviceAndBlankUnlinks() {
        setup.connect(passwordRequest(LOGIN_PASSWORD, LOGIN_PASSWORD), new RequestLoginContext(new MockHttpServletRequest(), loginService));

        setup.link("jf-1", "shield");
        setup.link("jf-1", "bedroom");
        assertThat(setup.settings().orElseThrow().sessionLinks()).containsExactlyEntriesOf(Map.of("bedroom", "jf-1"));

        setup.link("jf-1", "");
        assertThat(setup.settings().orElseThrow().sessionLinks()).isEmpty();
    }
}
