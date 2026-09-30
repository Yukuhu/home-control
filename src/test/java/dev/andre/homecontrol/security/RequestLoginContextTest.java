package dev.andre.homecontrol.security;

import dev.andre.homecontrol.storage.SecretKeySource;
import dev.andre.homecontrol.storage.SecretStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The login of the browser behind a request, kept in its HTTP session as it always was. */
class RequestLoginContextTest {

    private static final String PASSWORD = "household password";

    @TempDir
    Path dir;

    private SecretStore store;
    private LoginService login;

    @BeforeEach
    void setUp() {
        SecureRandom random = new SecureRandom();
        store = new SecretStore(dir.resolve("secrets.json"),
                new SecretKeySource(null, dir.resolve("secret.key"), random), random);
        login = new LoginService(store, new Argon2PasswordHasher(random), random);
    }

    private LoginContext context(MockHttpServletRequest request) {
        return new RequestLoginContext(request, login);
    }

    private void passwordSet() {
        login.setPassword(PASSWORD, PASSWORD, context(new MockHttpServletRequest()));
    }

    @Test
    void withoutAPasswordEveryBrowserIsLoggedIn() {
        assertThat(context(new MockHttpServletRequest()).loggedIn()).isTrue();
    }

    @Test
    void withAPasswordABrowserWithoutASessionIsNotLoggedIn() {
        passwordSet();
        LoginContext context = context(new MockHttpServletRequest());

        assertThat(context.loggedIn()).isFalse();
        assertThatThrownBy(context::requireLogin).isInstanceOf(LoginRequiredException.class).hasMessage("Log in first");
    }

    @Test
    void loggingInStartsASessionUnderANewId() {
        passwordSet();
        MockHttpServletRequest request = new MockHttpServletRequest();
        String before = request.getSession(true).getId();
        LoginContext context = context(request);

        assertThat(login.authenticate(PASSWORD, context)).isTrue();

        assertThat(context.loggedIn()).isTrue();
        assertThat(request.getSession(false).getId()).isNotEqualTo(before);
    }

    @Test
    void aBrowserLoggedInBeforeTheUpgradeIsStillLoggedIn() {
        passwordSet();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true).setAttribute(LoginService.SESSION_ATTRIBUTE, store.login().orElseThrow().version());

        assertThat(context(request).loggedIn()).isTrue();
    }

    @Test
    void aPasswordChangedElsewhereEndsThisLogin() {
        passwordSet();
        LoginContext mine = context(new MockHttpServletRequest());
        login.authenticate(PASSWORD, mine);
        BooleanSupplier stream = mine.whileLoggedIn();
        LoginContext other = context(new MockHttpServletRequest());
        login.authenticate(PASSWORD, other);

        login.changePassword(PASSWORD, "a new password!", "a new password!", other);

        assertThat(mine.loggedIn()).isFalse();
        assertThat(stream.getAsBoolean()).isFalse();
        assertThat(other.loggedIn()).isTrue();
    }

    @Test
    void loggingOutEndsTheSession() {
        passwordSet();
        LoginContext context = context(new MockHttpServletRequest());
        login.authenticate(PASSWORD, context);
        BooleanSupplier stream = context.whileLoggedIn();

        login.logout(context);

        assertThat(context.loggedIn()).isFalse();
        assertThat(stream.getAsBoolean()).isFalse();
    }

    @Test
    void theSessionKeyIsTheSessionIdAndStartsASession() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        LoginContext context = context(request);

        String key = context.sessionKey();

        assertThat(request.getSession(false)).isNotNull();
        assertThat(key).isEqualTo(request.getSession(false).getId()).isEqualTo(context.sessionKey());
    }
}
