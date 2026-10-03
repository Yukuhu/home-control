package dev.andre.homecontrol.security;

import dev.andre.homecontrol.storage.SecretKeySource;
import dev.andre.homecontrol.storage.SecretStore;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
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
    private RememberedLogins remembered;

    @BeforeEach
    void setUp() {
        SecureRandom random = new SecureRandom();
        store = new SecretStore(dir.resolve("secrets.json"),
                new SecretKeySource(null, dir.resolve("secret.key"), random), random);
        login = new LoginService(store, new Argon2PasswordHasher(random), random);
        remembered = new RememberedLogins(dir.resolve("logins.json"), Clock.systemUTC(), random, false);
        login.rememberedBy(remembered);
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

    /** A browser with a remembered login, as after a restart: the cookie, but no session the server knows. */
    private LoginContext remembering(MockHttpServletRequest request, MockHttpServletResponse response) {
        return new RequestLoginContext(request, response, login, remembered);
    }

    private static String rememberedToken(MockHttpServletResponse response) {
        Cookie cookie = response.getCookie(RememberedLogins.COOKIE);
        assertThat(cookie).as("the remembered-login cookie").isNotNull();
        return cookie.getValue();
    }

    private MockHttpServletRequest afterARestart(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(RememberedLogins.COOKIE, token));
        return request;
    }

    @Test
    void aLoginIsRememberedSoItSurvivesARestart() {
        passwordSet();
        MockHttpServletResponse response = new MockHttpServletResponse();
        login.authenticate(PASSWORD, remembering(new MockHttpServletRequest(), response));
        String token = rememberedToken(response);
        assertThat(response.getCookie(RememberedLogins.COOKIE).isHttpOnly()).isTrue();

        MockHttpServletRequest request = afterARestart(token);
        LoginContext restarted = remembering(request, new MockHttpServletResponse());
        assertThat(restarted.loggedIn()).isFalse();

        assertThat(login.resume(restarted)).isTrue();
        assertThat(restarted.loggedIn()).isTrue();
        assertThat(restarted.whileLoggedIn().getAsBoolean()).as("an event stream opened now").isTrue();
        assertThat(login.resume(remembering(afterARestart(token), new MockHttpServletResponse())))
                .as("the token is not rotated: the page's other requests carry it too").isTrue();
    }

    @Test
    void resumingASessionThatExistedGivesItANewId() {
        passwordSet();
        MockHttpServletResponse response = new MockHttpServletResponse();
        login.authenticate(PASSWORD, remembering(new MockHttpServletRequest(), response));
        MockHttpServletRequest request = afterARestart(rememberedToken(response));
        String before = request.getSession(true).getId();

        assertThat(login.resume(remembering(request, new MockHttpServletResponse()))).isTrue();

        assertThat(request.getSession(false).getId()).isNotEqualTo(before);
    }

    @Test
    void loggingOutForgetsTheRememberedLogin() {
        passwordSet();
        MockHttpServletResponse loggedIn = new MockHttpServletResponse();
        MockHttpServletRequest request = new MockHttpServletRequest();
        login.authenticate(PASSWORD, remembering(request, loggedIn));
        String token = rememberedToken(loggedIn);
        request.setCookies(new Cookie(RememberedLogins.COOKIE, token));
        MockHttpServletResponse loggedOut = new MockHttpServletResponse();

        login.logout(remembering(request, loggedOut));

        assertThat(loggedOut.getCookie(RememberedLogins.COOKIE).getMaxAge()).isZero();
        assertThat(login.resume(remembering(afterARestart(token), new MockHttpServletResponse()))).isFalse();
    }

    /** Two tabs reconnecting at once after a restart each resume a session of their own from the one token. */
    @Test
    void loggingOutEndsEverySessionResumedFromTheSameLogin() {
        passwordSet();
        MockHttpServletResponse loggedIn = new MockHttpServletResponse();
        login.authenticate(PASSWORD, remembering(new MockHttpServletRequest(), loggedIn));
        String token = rememberedToken(loggedIn);
        MockHttpServletRequest firstTab = afterARestart(token);
        MockHttpServletRequest secondTab = afterARestart(token);
        login.resume(remembering(firstTab, new MockHttpServletResponse()));
        login.resume(remembering(secondTab, new MockHttpServletResponse()));
        LoginContext second = remembering(secondTab, new MockHttpServletResponse());
        BooleanSupplier secondStream = second.whileLoggedIn();

        login.logout(remembering(firstTab, new MockHttpServletResponse()));

        assertThat(second.loggedIn()).isFalse();
        assertThat(secondStream.getAsBoolean()).isFalse();
    }

    @Test
    void aSessionFromBeforeLoginsWereRememberedStaysLoggedIn() {
        passwordSet();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true).setAttribute(LoginService.SESSION_ATTRIBUTE, store.login().orElseThrow().version());

        assertThat(remembering(request, new MockHttpServletResponse()).loggedIn()).isTrue();
    }

    @Test
    void overHttpsTheLoginCookieIsSecureLikeTheSessionCookie() {
        passwordSet();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSecure(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        login.authenticate(PASSWORD, remembering(request, response));

        assertThat(response.getCookie(RememberedLogins.COOKIE).getSecure()).isTrue();
    }

    @Test
    void aNewPasswordEndsEveryRememberedLoginButItsOwn() {
        passwordSet();
        MockHttpServletResponse phone = new MockHttpServletResponse();
        login.authenticate(PASSWORD, remembering(new MockHttpServletRequest(), phone));
        MockHttpServletResponse laptop = new MockHttpServletResponse();
        MockHttpServletRequest laptopRequest = new MockHttpServletRequest();
        login.authenticate(PASSWORD, remembering(laptopRequest, laptop));
        laptopRequest.setCookies(new Cookie(RememberedLogins.COOKIE, rememberedToken(laptop)));
        MockHttpServletResponse changed = new MockHttpServletResponse();

        login.changePassword(PASSWORD, "a new password!", "a new password!", remembering(laptopRequest, changed));

        assertThat(login.resume(remembering(afterARestart(rememberedToken(phone)), new MockHttpServletResponse())))
                .isFalse();
        assertThat(remembered.versionOf(rememberedToken(laptop))).as("the old token is forgotten").isEmpty();
        assertThat(login.resume(remembering(afterARestart(rememberedToken(changed)), new MockHttpServletResponse())))
                .isTrue();
    }

    @Test
    void withoutARememberedLoginNothingResumes() {
        passwordSet();

        assertThat(login.resume(remembering(new MockHttpServletRequest(), new MockHttpServletResponse()))).isFalse();
        assertThat(login.resume(remembering(afterARestart("forged"), new MockHttpServletResponse()))).isFalse();
        assertThat(login.resume(context(afterARestart("forged")))).as("a context that remembers nothing").isFalse();
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
