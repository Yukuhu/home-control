package dev.andre.homecontrol.security;

import dev.andre.homecontrol.storage.LoginCredential;
import dev.andre.homecontrol.storage.SecretKeySource;
import dev.andre.homecontrol.storage.SecretStore;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;

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

    /** Real Argon2 timing cannot stage races or a full verifier; this hasher is scripted per test. */
    private LoginService withScriptedHasher(Argon2PasswordHasher hasher) {
        given(hasher.hash(anyString())).willAnswer(call -> "hash of " + call.getArgument(0));
        LoginService scripted = new LoginService(store, hasher, new SecureRandom());
        scripted.storeSecrets(Map.of("jellyfin.token", "t"), PASSWORD, PASSWORD, new MockHttpServletRequest());
        return scripted;
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

        var preparedArg54_0 = Map.of("jellyfin.token", "t");
        assertThatThrownBy(() -> login.storeSecrets(preparedArg54_0, "short", "short", request))
                .isInstanceOf(PasswordRejectedException.class)
                .hasMessage("The login password needs at least 10 characters");
        var preparedArg57_0 = Map.of("jellyfin.token", "t");
        assertThatThrownBy(() -> login.storeSecrets(preparedArg57_0, "long enough 1", "long enough 2", request))
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

        var preparedArg77_0 = Map.of("other.token", "u");
        var preparedArg77_3 = new MockHttpServletRequest();
        assertThatThrownBy(() -> login.storeSecrets(preparedArg77_0, null, null, preparedArg77_3))
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

        var preparedArg150_3 = new MockHttpServletRequest();
        assertThatThrownBy(() -> login.changePassword("not the password", "short", "short", preparedArg150_3))
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

    @Test
    void withoutALoginEveryAttemptSucceedsWithoutStartingASession() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThat(login.authenticate("anything", request)).isTrue();
        assertThat(request.getSession(false)).isNull();
    }

    @Test
    void aNewPasswordHasAnUpperLengthLimit() {
        String longest = "x".repeat(LoginService.MAX_PASSWORD_LENGTH);
        String tooLong = longest + "x";

        assertThatThrownBy(() -> login.checkNewPassword(tooLong, tooLong))
                .isInstanceOf(PasswordRejectedException.class)
                .hasMessage("The login password can have at most 1024 characters");
        assertThatCode(() -> login.checkNewPassword(longest, longest)).doesNotThrowAnyException();
    }

    @Test
    void thereIsNoPasswordToChangeBeforeTheFirstSecret() {
        var request = new MockHttpServletRequest();

        assertThatThrownBy(() -> login.changePassword(PASSWORD, "a new password!", "a new password!", request))
                .isInstanceOf(PasswordRejectedException.class)
                .hasMessage("There is no login password to change");
        assertThat(store.hasSecrets()).isFalse();
    }

    @Test
    void aPasswordChangedWhileTheCurrentOneWasCheckedIsNotOverwritten() {
        Argon2PasswordHasher hasher = mock(Argon2PasswordHasher.class);
        LoginService racing = withScriptedHasher(hasher);
        LoginCredential meanwhile = new LoginCredential("hash of another browser's password", "other-version");
        given(hasher.matches(PASSWORD, "hash of " + PASSWORD)).willAnswer(call -> {
            store.replaceLogin(meanwhile); // another browser finishes its own change during this check
            return true;
        });
        var request = new MockHttpServletRequest();

        assertThatThrownBy(() -> racing.changePassword(PASSWORD, "a new password!", "a new password!", request))
                .isInstanceOf(PasswordRejectedException.class)
                .isNotInstanceOf(WrongPasswordException.class)
                .hasMessage("The password was changed meanwhile; try again");
        assertThat(store.login()).contains(meanwhile);
        assertThat(request.getSession(false)).isNull();
    }

    @Test
    void atMostTwoPasswordChecksRunAtOnce() throws Exception {
        Argon2PasswordHasher hasher = mock(Argon2PasswordHasher.class);
        LoginService limited = withScriptedHasher(hasher);
        CountDownLatch checking = new CountDownLatch(2);
        CountDownLatch finish = new CountDownLatch(1);
        given(hasher.matches(eq("slow guess"), anyString())).willAnswer(call -> {
            checking.countDown();
            return !finish.await(10, SECONDS); // false: a wrong guess
        });
        given(hasher.matches(PASSWORD, "hash of " + PASSWORD)).willReturn(true);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> slow = List.of(
                    pool.submit(() -> limited.authenticate("slow guess", new MockHttpServletRequest())),
                    pool.submit(() -> limited.authenticate("slow guess", new MockHttpServletRequest())));
            assertThat(checking.await(5, SECONDS)).isTrue();
            var third = new MockHttpServletRequest();

            assertThatThrownBy(() -> limited.authenticate(PASSWORD, third)).isInstanceOf(LoginBusyException.class);

            finish.countDown();
            for (Future<Boolean> guess : slow) {
                assertThat(guess.get(5, SECONDS)).isFalse();
            }
            assertThat(limited.authenticate(PASSWORD, new MockHttpServletRequest())).isTrue();
        } finally {
            finish.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void anInterruptedWaitForAPasswordCheckIsBusyAndKeepsTheInterrupt() {
        firstSecretStored();
        var request = new MockHttpServletRequest();
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> login.authenticate(PASSWORD, request)).isInstanceOf(LoginBusyException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void loggingOutWithoutALiveSessionStillTellsListeners() {
        AtomicInteger changes = new AtomicInteger();
        login.onChange(changes::incrementAndGet);
        HttpSession endedElsewhere = mock(HttpSession.class);
        willThrow(new IllegalStateException("already invalidated")).given(endedElsewhere).invalidate();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(endedElsewhere);

        login.logout(new MockHttpServletRequest());
        login.logout(request);

        assertThat(changes).hasValue(2);
    }

    @Test
    void aFailingListenerNeitherUndoesTheChangeNorSilencesTheOthers() {
        AtomicInteger changes = new AtomicInteger();
        login.onChange(() -> {
            throw new IllegalStateException("stream already closed");
        });
        login.onChange(changes::incrementAndGet);

        MockHttpServletRequest request = firstSecretStored();

        assertThat(login.isAuthenticated(request)).isTrue();
        assertThat(changes).hasValue(1);
    }
}
