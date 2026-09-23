package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.security.Argon2PasswordHasher;
import dev.andre.homecontrol.security.LoginRequiredException;
import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.security.PasswordRejectedException;
import dev.andre.homecontrol.storage.JsonFileSourceSettings;
import dev.andre.homecontrol.storage.SecretKeySource;
import dev.andre.homecontrol.storage.SecretStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.IOException;
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
        client = new JellyfinClient(new JellyfinProperties(true, 2, 5, 20), "0.8.0");
        setup = new JellyfinSetupService(client, sources, secretStore, loginService);
        fake = new FakeJellyfinServer().withConnectableServer();
    }

    private JellyfinSetupService.ConnectRequest passwordRequest(String loginPassword, String loginConfirmation) {
        return new JellyfinSetupService.ConnectRequest(fake.url() + "/", null, JellyfinSettings.AuthMode.PASSWORD,
                "andre", "user pw", null, loginPassword, loginConfirmation);
    }

    @Test
    void connectsWithAPasswordAndStoresOnlyTheToken() throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();

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
        assertThat(loginService.isAuthenticated(request)).isTrue();
    }

    @Test
    void theLoginPasswordIsCheckedBeforeContactingJellyfin() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThatThrownBy(() -> setup.connect(passwordRequest("short", "short"), request))
                .isInstanceOf(PasswordRejectedException.class);

        assertThat(fake.requests()).isEmpty();
        assertThat(setup.settings()).isEmpty();
        assertThat(secretStore.hasSecrets()).isFalse();
    }

    @Test
    void unauthenticatedRequestsAreRejectedBeforeTheServerUrlIsValidated() {
        setup.connect(passwordRequest(LOGIN_PASSWORD, LOGIN_PASSWORD), new MockHttpServletRequest());

        MockHttpServletRequest unauthenticated = new MockHttpServletRequest();
        JellyfinSetupService.ConnectRequest badUrl = new JellyfinSetupService.ConnectRequest(
                "not a url", null, JellyfinSettings.AuthMode.PASSWORD, "andre", "user pw", null, null, null);

        assertThatThrownBy(() -> setup.connect(badUrl, unauthenticated)).isInstanceOf(LoginRequiredException.class);
    }

    @Test
    void connectsWithAnApiKeyByUserName() {
        fake.respond("GET", "/Users", 200, "users.json");
        MockHttpServletRequest request = new MockHttpServletRequest();
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
    void anUnknownUserIsNamed() {
        fake.respond("GET", "/Users", 200, "users.json");
        MockHttpServletRequest request = new MockHttpServletRequest();
        JellyfinSetupService.ConnectRequest connectRequest = new JellyfinSetupService.ConnectRequest(
                fake.url() + "/", null, JellyfinSettings.AuthMode.API_KEY, "nobody", null, "api-key-123",
                LOGIN_PASSWORD, LOGIN_PASSWORD);

        assertThatThrownBy(() -> setup.connect(connectRequest, request))
                .isInstanceOf(JellyfinException.class)
                .extracting(e -> ((JellyfinException) e).kind())
                .isEqualTo(JellyfinException.Kind.USER_NOT_FOUND);
        assertThatThrownBy(() -> setup.connect(connectRequest, request))
                .hasMessage("No Jellyfin user named 'nobody'");
        assertThat(setup.settings()).isEmpty();
    }

    @Test
    void reconnectingKeepsTheDeviceIdAndLinksAndNeedsTheLogin() {
        MockHttpServletRequest first = new MockHttpServletRequest();
        JellyfinSettings connected = setup.connect(passwordRequest(LOGIN_PASSWORD, LOGIN_PASSWORD), first);
        setup.save(connected.withSessionLink("shield", "jf-dev").withPlayer("shield", JellyfinSettings.Player.VLC));

        MockHttpServletRequest unauthenticated = new MockHttpServletRequest();
        assertThatThrownBy(() -> setup.connect(passwordRequest(null, null), unauthenticated))
                .isInstanceOf(LoginRequiredException.class);

        JellyfinSettings again = setup.connect(passwordRequest(null, null), first);

        assertThat(again.deviceId()).isEqualTo(connected.deviceId());
        assertThat(again.sessionLinks()).containsEntry("shield", "jf-dev");
        assertThat(again.player("shield")).isEqualTo(JellyfinSettings.Player.VLC);
        assertThat(fake.requests("POST", "/Sessions/Logout")).isEmpty();
    }

    @Test
    void checkReportsServerVersionAndUser() {
        setup.connect(passwordRequest(LOGIN_PASSWORD, LOGIN_PASSWORD), new MockHttpServletRequest());

        assertThat(setup.check()).isEqualTo("Connected to nas (Jellyfin 10.11.2) as Andre");
    }

    @Test
    void disconnectRevokesAPasswordTokenAndEndsTheLoginRequirement() {
        setup.connect(passwordRequest(LOGIN_PASSWORD, LOGIN_PASSWORD), new MockHttpServletRequest());

        setup.disconnect();

        FakeJellyfinServer.Recorded logout = fake.last("POST", "/Sessions/Logout");
        assertThat(logout.header("authorization")).contains("Token=\"" + FakeJellyfinServer.ACCESS_TOKEN + "\"");
        assertThat(setup.settings()).isEmpty();
        assertThat(loginService.loginRequired()).isFalse();
    }

    @Test
    void disconnectStillWorksWhenJellyfinIsDown() {
        setup.connect(passwordRequest(LOGIN_PASSWORD, LOGIN_PASSWORD), new MockHttpServletRequest());
        fake.close();

        setup.disconnect();

        assertThat(setup.settings()).isEmpty();
    }

    @Test
    void linkingASessionReplacesItsPreviousDeviceAndBlankUnlinks() {
        setup.connect(passwordRequest(LOGIN_PASSWORD, LOGIN_PASSWORD), new MockHttpServletRequest());

        setup.link("jf-1", "shield");
        setup.link("jf-1", "bedroom");
        assertThat(setup.settings().orElseThrow().sessionLinks()).containsExactlyEntriesOf(Map.of("bedroom", "jf-1"));

        setup.link("jf-1", "");
        assertThat(setup.settings().orElseThrow().sessionLinks()).isEmpty();
    }
}
