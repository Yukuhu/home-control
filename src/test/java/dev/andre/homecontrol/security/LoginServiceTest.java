package dev.andre.homecontrol.security;

import dev.andre.homecontrol.storage.SecretKeySource;
import dev.andre.homecontrol.storage.SecretStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginServiceTest {

    static final String PASSWORD = "household password";

    @TempDir
    Path dir;

    SecretStore store;
    LoginService login;

    @BeforeEach
    void setUp() {
        SecureRandom random = new SecureRandom();
        store = new SecretStore(dir.resolve("secrets.json"),
                new SecretKeySource(null, dir.resolve("secret.key"), random), random);
        login = new LoginService(store, new Argon2PasswordHasher(random), random);
    }

    private MockHttpServletRequest firstSecretStored() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        login.storeSecrets(Map.of("jellyfin.token", "t"), PASSWORD, PASSWORD, request);
        return request;
    }

    @Test
    void noLoginIsRequiredWhileThereAreNoSecrets() {
        assertThat(login.loginRequired()).isFalse();
        assertThat(login.isAuthenticated(new MockHttpServletRequest())).isTrue();
    }

    @Test
    void theFirstSecretNeedsAValidNewPassword() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThatThrownBy(() -> login.storeSecrets(Map.of("jellyfin.token", "t"), "short", "short", request))
                .isInstanceOf(PasswordRejectedException.class)
                .hasMessage("The login password needs at least 10 characters");
        assertThatThrownBy(() -> login.storeSecrets(Map.of("jellyfin.token", "t"), "long enough 1", "long enough 2", request))
                .isInstanceOf(PasswordRejectedException.class)
                .hasMessage("The two passwords do not match");
        assertThat(store.hasSecrets()).isFalse();
        assertThat(request.getSession(false)).isNull();
    }

    @Test
    void storingTheFirstSecretSetsTheLoginAndLogsThisBrowserIn() {
        MockHttpServletRequest request = firstSecretStored();

        assertThat(login.loginRequired()).isTrue();
        assertThat(login.isAuthenticated(request)).isTrue();
        assertThat(login.isAuthenticated(new MockHttpServletRequest())).isFalse();
    }

    @Test
    void laterSecretsNeedAnAuthenticatedRequest() {
        MockHttpServletRequest request = firstSecretStored();

        assertThatThrownBy(() -> login.storeSecrets(Map.of("other.token", "u"), null, null, new MockHttpServletRequest()))
                .isInstanceOf(LoginRequiredException.class)
                .hasMessage("Log in first");
        assertThat(store.secret("other.token")).isEmpty();

        login.storeSecrets(Map.of("other.token", "u"), null, null, request);
        assertThat(store.secret("other.token")).contains("u");
    }

    @Test
    void authenticateChecksThePasswordAndRotatesTheSessionId() {
        firstSecretStored();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(new MockHttpSession(null, "s1"));

        assertThat(login.authenticate("wrong password", request)).isFalse();
        assertThat(request.getSession().getAttribute(LoginService.SESSION_ATTRIBUTE)).isNull();
        assertThat(login.isAuthenticated(request)).isFalse();

        assertThat(login.authenticate(PASSWORD, request)).isTrue();
        assertThat(request.getSession().getId()).isNotEqualTo("s1");
        assertThat(login.isAuthenticated(request)).isTrue();
    }

    @Test
    void anOverlongPasswordIsRefusedWithoutHashing() {
        firstSecretStored();

        assertThat(login.authenticate("x".repeat(LoginService.MAX_PASSWORD_LENGTH + 1), new MockHttpServletRequest())).isFalse();
        assertThat(login.authenticate(null, new MockHttpServletRequest())).isFalse();
    }

    @Test
    void loggingOutEndsTheSession() {
        MockHttpServletRequest request = firstSecretStored();

        login.logout(request);

        assertThat(login.isAuthenticated(request)).isFalse();
    }

    @Test
    void changingThePasswordLogsOutOtherBrowsers() {
        firstSecretStored();
        MockHttpServletRequest a = new MockHttpServletRequest();
        MockHttpServletRequest b = new MockHttpServletRequest();
        assertThat(login.authenticate(PASSWORD, a)).isTrue();
        assertThat(login.authenticate(PASSWORD, b)).isTrue();

        assertThatThrownBy(() -> login.changePassword("not the password", "a new password!", "a new password!", a))
                .isInstanceOf(WrongPasswordException.class)
                .hasMessage("The current password is wrong");
        login.changePassword(PASSWORD, "a new password!", "a new password!", a);

        assertThat(login.isAuthenticated(a)).isTrue();
        assertThat(login.isAuthenticated(b)).isFalse();
        assertThat(login.authenticate(PASSWORD, new MockHttpServletRequest())).isFalse();
        assertThat(login.authenticate("a new password!", new MockHttpServletRequest())).isTrue();
    }

    @Test
    void removingTheLastSecretEndsTheLoginRequirement() {
        firstSecretStored();

        login.removeSecrets(List.of("jellyfin.token"));

        assertThat(login.loginRequired()).isFalse();
    }

    @Test
    void aNewPasswordIsCheckedBeforeTheCurrentOneSoOnlyWrongGuessesAreWrongPasswords() {
        firstSecretStored();

        assertThatThrownBy(() -> login.changePassword("not the password", "short", "short", new MockHttpServletRequest()))
                .isInstanceOf(PasswordRejectedException.class)
                .isNotInstanceOf(WrongPasswordException.class)
                .hasMessage("The login password needs at least 10 characters");
    }

    @Test
    void aSessionIsCheckedWithoutARequest() {
        MockHttpSession deviceOnly = new MockHttpSession();
        assertThat(login.isAuthenticated(deviceOnly)).isTrue();

        MockHttpServletRequest request = firstSecretStored();
        MockHttpSession session = (MockHttpSession) request.getSession(false);

        assertThat(login.isAuthenticated(deviceOnly)).isFalse();
        assertThat(login.isAuthenticated((MockHttpSession) null)).isFalse();
        assertThat(login.isAuthenticated(session)).isTrue();
        session.invalidate();
        assertThat(login.isAuthenticated(session)).isFalse();
    }

    @Test
    void listenersHearEveryChangeOfWhoIsLoggedIn() {
        AtomicInteger changes = new AtomicInteger();
        login.onChange(changes::incrementAndGet);

        MockHttpServletRequest request = firstSecretStored();
        assertThat(changes).hasValue(1);
        login.changePassword(PASSWORD, "a new password!", "a new password!", request);
        assertThat(changes).hasValue(2);
        login.logout(request);
        assertThat(changes).hasValue(3);
        login.removeSecrets(List.of("jellyfin.token"));
        assertThat(changes).hasValue(4);
    }
}
