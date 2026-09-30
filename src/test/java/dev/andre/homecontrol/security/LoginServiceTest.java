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
        scripted.storeSecrets(Map.of("jellyfin.token", "t"), PASSWORD, PASSWORD, context(scripted, new MockHttpServletRequest()));
        return scripted;
    }

    private static LoginContext context(LoginService service, MockHttpServletRequest request) {
        return new RequestLoginContext(request, service);
    }

    private MockHttpServletRequest firstSecretStored() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        login.storeSecrets(Map.of("jellyfin.token", "t"), PASSWORD, PASSWORD, context(login, request));
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

        var secrets = Map.of("jellyfin.token", "t");
        assertThatThrownBy(() -> login.storeSecrets(secrets, "short", "short", context(login, request)))
                .isInstanceOf(PasswordRejectedException.class)
                .hasMessage("The login password needs at least 10 characters");
        assertThatThrownBy(() -> login.storeSecrets(secrets, "long enough 1", "long enough 2", context(login, request)))
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

        var otherSecret = Map.of("other.token", "u");
        var anonymous = new MockHttpServletRequest();
        assertThatThrownBy(() -> login.storeSecrets(otherSecret, null, null, context(login, anonymous)))
                .isInstanceOf(LoginRequiredException.class)
                .hasMessage("Log in first");
        assertThat(store.secret("other.token")).isEmpty();

        login.storeSecrets(Map.of("other.token", "u"), null, null, context(login, request));
        assertThat(store.secret("other.token")).contains("u");
    }

    @Test
    void authenticateChecksThePasswordAndRotatesTheSessionId() {
        firstSecretStored();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(new MockHttpSession(null, "s1"));

        assertThat(login.authenticate("wrong password", context(login, request))).isFalse();
        assertThat(request.getSession().getAttribute(LoginService.SESSION_ATTRIBUTE)).isNull();
        assertThat(login.isAuthenticated(request)).isFalse();

        assertThat(login.authenticate(PASSWORD, context(login, request))).isTrue();
        assertThat(request.getSession().getId()).isNotEqualTo("s1");
        assertThat(login.isAuthenticated(request)).isTrue();
    }

    @Test
    void anOverlongPasswordIsRefusedWithoutHashing() {
        firstSecretStored();

        assertThat(login.authenticate("x".repeat(LoginService.MAX_PASSWORD_LENGTH + 1), context(login, new MockHttpServletRequest()))).isFalse();
        assertThat(login.authenticate(null, context(login, new MockHttpServletRequest()))).isFalse();
    }

    @Test
    void loggingOutEndsTheSession() {
        MockHttpServletRequest request = firstSecretStored();

        login.logout(context(login, request));

        assertThat(login.isAuthenticated(request)).isFalse();
    }

    @Test
    void changingThePasswordLogsOutOtherBrowsers() {
        firstSecretStored();
        MockHttpServletRequest a = new MockHttpServletRequest();
        MockHttpServletRequest b = new MockHttpServletRequest();
        assertThat(login.authenticate(PASSWORD, context(login, a))).isTrue();
        assertThat(login.authenticate(PASSWORD, context(login, b))).isTrue();

        assertThatThrownBy(() -> login.changePassword("not the password", "a new password!", "a new password!", context(login, a)))
                .isInstanceOf(WrongPasswordException.class)
                .hasMessage("The current password is wrong");
        login.changePassword(PASSWORD, "a new password!", "a new password!", context(login, a));

        assertThat(login.isAuthenticated(a)).isTrue();
        assertThat(login.isAuthenticated(b)).isFalse();
        assertThat(login.authenticate(PASSWORD, context(login, new MockHttpServletRequest()))).isFalse();
        assertThat(login.authenticate("a new password!", context(login, new MockHttpServletRequest()))).isTrue();
    }

    @Test
    void removingTheLastSecretKeepsTheLogin() {
        firstSecretStored();

        login.removeSecrets(List.of("jellyfin.token"));

        assertThat(login.loginRequired()).isTrue();
    }

    @Test
    void aPasswordIsSetWithoutAnySecretAndLogsThisBrowserIn() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        login.setPassword(PASSWORD, PASSWORD, context(login, request));

        assertThat(login.loginRequired()).isTrue();
        assertThat(login.isAuthenticated(request)).isTrue();
        assertThat(login.isAuthenticated(new MockHttpServletRequest())).isFalse();
    }

    @Test
    void aSecondPasswordIsRefused() {
        login.setPassword(PASSWORD, PASSWORD, context(login, new MockHttpServletRequest()));
        var request = new MockHttpServletRequest();

        assertThatThrownBy(() -> login.setPassword("another password", "another password", context(login, request)))
                .isInstanceOf(PasswordRejectedException.class)
                .hasMessage("A login password is already set; change it instead");
    }

    @Test
    void theCurrentPasswordRemovesTheLogin() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        login.setPassword(PASSWORD, PASSWORD, context(login, request));
        HttpSession before = request.getSession(false);

        login.removePassword(PASSWORD);

        assertThat(login.loginRequired()).isFalse();
        login.setPassword("a later password", "a later password", context(login, new MockHttpServletRequest()));
        assertThat(login.isAuthenticated(before)).isFalse();
    }

    @Test
    void aWrongPasswordDoesNotRemoveTheLogin() {
        login.setPassword(PASSWORD, PASSWORD, context(login, new MockHttpServletRequest()));

        assertThatThrownBy(() -> login.removePassword("not the password"))
                .isInstanceOf(WrongPasswordException.class);
        assertThat(login.loginRequired()).isTrue();
    }

    @Test
    void theLoginStaysWhileAnAccountIsConnectedAndTheRefusalNamesIt() {
        firstSecretStored();

        assertThatThrownBy(() -> login.removePassword(PASSWORD))
                .isInstanceOf(PasswordRejectedException.class)
                .isNotInstanceOf(WrongPasswordException.class)
                .hasMessage("Disconnect Jellyfin first: its credentials need the login password");
        assertThat(login.loginRequired()).isTrue();
    }

    @Test
    void connectedAccountsAreNamedOnceEachAndDeviceSecretsAreNotAccounts() {
        store.putDeviceSecret("device.androidtv.keystore-password", "p");
        MockHttpServletRequest request = firstSecretStored();
        login.storeSecrets(Map.of("youtube.client-id", "id", "youtube.client-secret", "s", "workflow.w-0123456789ab", "x"),
                null, null, context(login, request));

        assertThat(login.connectedAccounts()).containsExactly("Jellyfin", "Workflows", "YouTube");
    }

    @Test
    void removingThePasswordIsRefusedOnceAnAccountIsConnectedMeanwhile() {
        Argon2PasswordHasher hasher = mock(Argon2PasswordHasher.class);
        given(hasher.hash(anyString())).willAnswer(call -> "hash of " + call.getArgument(0));
        LoginService scripted = new LoginService(store, hasher, new SecureRandom());
        MockHttpServletRequest request = new MockHttpServletRequest();
        scripted.setPassword(PASSWORD, PASSWORD, context(scripted, request));
        given(hasher.matches(anyString(), anyString())).willAnswer(call -> {
            store.putSecrets(Map.of("tmdb.credential", "c")); // connected while the password was being checked
            return true;
        });

        assertThatThrownBy(() -> scripted.removePassword(PASSWORD))
                .isInstanceOf(PasswordRejectedException.class)
                .hasMessage("Disconnect TMDB first: its credentials need the login password");
        assertThat(store.login()).isPresent();
    }

    @Test
    void aNewPasswordIsCheckedBeforeTheCurrentOneSoOnlyWrongGuessesAreWrongPasswords() {
        firstSecretStored();

        var anonymous = new MockHttpServletRequest();
        assertThatThrownBy(() -> login.changePassword("not the password", "short", "short", context(login, anonymous)))
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
        login.changePassword(PASSWORD, "a new password!", "a new password!", context(login, request));
        assertThat(changes).hasValue(2);
        login.logout(context(login, request));
        assertThat(changes).hasValue(3);
        login.removeSecrets(List.of("jellyfin.token"));
        assertThat(changes).hasValue(4);
    }

    @Test
    void withoutALoginEveryAttemptSucceedsWithoutStartingASession() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThat(login.authenticate("anything", context(login, request))).isTrue();
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

        assertThatThrownBy(() -> login.changePassword(PASSWORD, "a new password!", "a new password!", context(login, request)))
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

        assertThatThrownBy(() -> racing.changePassword(PASSWORD, "a new password!", "a new password!", context(racing, request)))
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
                    pool.submit(() -> limited.authenticate("slow guess", context(limited, new MockHttpServletRequest()))),
                    pool.submit(() -> limited.authenticate("slow guess", context(limited, new MockHttpServletRequest()))));
            assertThat(checking.await(5, SECONDS)).isTrue();
            var third = new MockHttpServletRequest();

            assertThatThrownBy(() -> limited.authenticate(PASSWORD, context(limited, third))).isInstanceOf(LoginBusyException.class);

            finish.countDown();
            for (Future<Boolean> guess : slow) {
                assertThat(guess.get(5, SECONDS)).isFalse();
            }
            assertThat(limited.authenticate(PASSWORD, context(limited, new MockHttpServletRequest()))).isTrue();
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
            assertThatThrownBy(() -> login.authenticate(PASSWORD, context(login, request))).isInstanceOf(LoginBusyException.class);
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

        login.logout(context(login, new MockHttpServletRequest()));
        login.logout(context(login, request));

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

    @Test
    void secretsArePermittedToALoggedInBrowser() {
        MockHttpServletRequest request = firstSecretStored();

        assertThatCode(() -> login.permitSecrets(context(login, request), null, null)).doesNotThrowAnyException();
    }

    @Test
    void onceAPasswordIsSetSecretsNeedTheLogin() {
        firstSecretStored();
        LoginContext stranger = context(login, new MockHttpServletRequest());

        assertThatThrownBy(() -> login.permitSecrets(stranger, PASSWORD, PASSWORD))
                .isInstanceOf(LoginRequiredException.class);
    }

    @Test
    void theFirstSecretsNeedAnAcceptableNewPasswordAndNothingIsStoredYet() {
        LoginContext first = context(login, new MockHttpServletRequest());

        assertThatThrownBy(() -> login.permitSecrets(first, "short", "short")).isInstanceOf(PasswordRejectedException.class);
        assertThatCode(() -> login.permitSecrets(first, PASSWORD, PASSWORD)).doesNotThrowAnyException();
        assertThat(login.loginRequired()).isFalse();
    }
}
