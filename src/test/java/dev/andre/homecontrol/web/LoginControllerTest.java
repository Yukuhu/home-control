package dev.andre.homecontrol.web;

import dev.andre.homecontrol.security.LoginBusyException;
import dev.andre.homecontrol.security.LoginRateLimiter;
import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.security.PasswordRejectedException;
import dev.andre.homecontrol.security.WrongPasswordException;
import dev.andre.homecontrol.testsupport.FakeLoginContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The rate limiter must count every checked guess and nothing else: a busy verifier, a rejected
 * new password or an unexpected failure gives the reservation back. One failure per address
 * blocks here, so a leaked reservation shows up as a blocked address.
 */
class LoginControllerTest {

    private static final String ADDRESS = "192.168.1.20";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-26T12:00:00Z"), ZoneOffset.UTC);

    private final LoginService login = mock(LoginService.class);
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final FakeLoginContext context = FakeLoginContext.loggedOutBrowser();
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final ExtendedModelMap model = new ExtendedModelMap();

    private LoginRateLimiter limiter;
    private LoginController controller;

    @BeforeEach
    void loginRequired() {
        given(login.loginRequired()).willReturn(true);
        request.setRemoteAddr(ADDRESS);
        useWindow(Duration.ofMinutes(15));
    }

    private void useWindow(Duration window) {
        limiter = new LoginRateLimiter(CLOCK, 1, 100, window);
        controller = new LoginController(login, limiter);
    }

    private String submit(String password) {
        return controller.submit(password, "/devices", request, context, response, model);
    }

    /** Typed as {@code Map<String, Object>} so AssertJ can compare entries; Spring hands out {@code Map<String, ?>}. */
    private static Map<String, Object> flash(RedirectAttributesModelMap redirect) {
        return new HashMap<>(redirect.getFlashAttributes());
    }

    @Test
    void theLoginPageSendsVisitorsHomeWhenThereIsNothingToLogInTo() {
        given(login.loginRequired()).willReturn(false);

        assertThat(controller.page("/devices", context, model)).isEqualTo("redirect:/");
        assertThat(submit("guess")).isEqualTo("redirect:/");
        verify(login, never()).authenticate(any(), any());
    }

    @Test
    void theLoginPageSendsAnAlreadyLoggedInBrowserOnToItsSafeNextPage() {
        FakeLoginContext loggedIn = FakeLoginContext.loggedInBrowser();

        assertThat(controller.page("/devices", loggedIn, model)).isEqualTo("redirect:/devices");
        assertThat(controller.page("//evil.example", loggedIn, model)).isEqualTo("redirect:/");
    }

    @Test
    void theLoginPageRemembersOnlyASafeNextPage() {
        assertThat(controller.page("https://evil.example/", context, model)).isEqualTo("login");
        assertThat(model.getAttribute("next")).isEqualTo("/");
    }

    @Test
    void theRightPasswordFollowsNextAndClearsTheAddress() {
        given(login.authenticate("right", context)).willReturn(true);

        assertThat(submit("right")).isEqualTo("redirect:/devices");
        assertThat(limiter.blockedFor(ADDRESS)).isEmpty();
    }

    @Test
    void aWrongPasswordCountsAsAGuessAndTheNextAttemptWaits() {
        given(login.authenticate("wrong", context)).willReturn(false);

        assertThat(submit("wrong")).isEqualTo("login");
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(model.getAttribute("error")).isEqualTo("Wrong password");

        MockHttpServletResponse second = new MockHttpServletResponse();
        assertThat(controller.submit("wrong", "/devices", request, context, second, model)).isEqualTo("login");
        assertThat(second.getStatus()).isEqualTo(429);
        assertThat(second.getHeader("Retry-After")).isEqualTo("900");
        assertThat(model.getAttribute("error")).isEqualTo("Too many attempts. Try again in 15 minutes.");
        verify(login, times(1)).authenticate(any(), any());
    }

    @Test
    void theWaitIsRoundedUpToWholeMinutesAndAtLeastOneSecond() {
        useWindow(Duration.ofSeconds(30));
        given(login.authenticate("wrong", context)).willReturn(false);
        submit("wrong");

        MockHttpServletResponse blocked = new MockHttpServletResponse();
        controller.submit("wrong", "/", request, context, blocked, model);

        assertThat(blocked.getHeader("Retry-After")).isEqualTo("30");
        assertThat(model.getAttribute("error")).isEqualTo("Too many attempts. Try again in 1 minute.");
    }

    @Test
    void aBusyVerifierIsNotCountedAsAGuess() {
        willThrow(new LoginBusyException()).given(login).authenticate("right", context);

        assertThat(submit("right")).isEqualTo("login");
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(model.getAttribute("error"))
                .isEqualTo("The server is busy checking other logins; try again in a moment");
        assertThat(limiter.blockedFor(ADDRESS)).isEmpty();
    }

    @Test
    void anUnexpectedFailureIsNotCountedAsAGuessAndStillSurfaces() {
        willThrow(new IllegalStateException("disk gone")).given(login).authenticate("right", context);

        assertThatThrownBy(() -> submit("right")).isInstanceOf(IllegalStateException.class).hasMessage("disk gone");
        assertThat(limiter.blockedFor(ADDRESS)).isEmpty();
    }

    @Test
    void loggingOutGoesToTheLoginPageOnlyWhileALoginExists() {
        given(login.logout(context)).willReturn(true);
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

        assertThat(controller.logout(context, redirect)).isEqualTo("redirect:/login");

        given(login.loginRequired()).willReturn(false);
        assertThat(controller.logout(context, redirect)).isEqualTo("redirect:/");
        verify(login, times(2)).logout(context);
        assertThat(redirect.getFlashAttributes()).isEmpty();
    }

    @Test
    void aLogoutThatCouldNotBeSavedSaysWhatThatMeans() {
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

        assertThat(controller.logout(context, redirect)).isEqualTo("redirect:/login");

        assertThat(redirect.getFlashAttributes().get("error")).asString()
                .contains("could not be saved").contains("restart").contains("Change the password");
    }

    @Test
    void aSuccessfulPasswordChangeClearsTheAddress() {
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

        assertThat(controller.changePassword("old", "new", "new", request, context, redirect)).isEqualTo("redirect:/setup");

        verify(login).changePassword("old", "new", "new", context);
        assertThat(flash(redirect))
                .containsEntry("loginMessage", "Password changed. Other browsers need to log in again.");
        assertThat(limiter.blockedFor(ADDRESS)).isEmpty();
    }

    @Test
    void aWrongCurrentPasswordIsAGuessAndTheNextChangeWaits() {
        willThrow(new WrongPasswordException("The current password is wrong"))
                .given(login).changePassword("guess", "new", "new", context);
        RedirectAttributesModelMap first = new RedirectAttributesModelMap();

        controller.changePassword("guess", "new", "new", request, context, first);

        assertThat(flash(first)).containsEntry("loginError", "The current password is wrong");
        RedirectAttributesModelMap second = new RedirectAttributesModelMap();
        assertThat(controller.changePassword("guess", "new", "new", request, context, second)).isEqualTo("redirect:/setup");
        assertThat(flash(second)).containsEntry("loginError", "Too many attempts. Try again in 15 minutes.");
        verify(login, times(1)).changePassword(any(), any(), any(), any());
    }

    @Test
    void aRejectedNewPasswordOrABusyVerifierIsNotAGuess() {
        willThrow(new PasswordRejectedException("The two passwords do not match"))
                .given(login).changePassword("old", "new", "other", context);
        willThrow(new LoginBusyException()).given(login).changePassword("old", "new", "new", context);

        RedirectAttributesModelMap rejected = new RedirectAttributesModelMap();
        controller.changePassword("old", "new", "other", request, context, rejected);
        RedirectAttributesModelMap busy = new RedirectAttributesModelMap();
        controller.changePassword("old", "new", "new", request, context, busy);

        assertThat(flash(rejected)).containsEntry("loginError", "The two passwords do not match");
        assertThat(flash(busy))
                .containsEntry("loginError", "The server is busy checking other logins; try again in a moment");
        assertThat(limiter.blockedFor(ADDRESS)).isEmpty();
    }

    @Test
    void settingAPasswordReportsSuccessAndIsNotAGuess() {
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

        assertThat(controller.setPassword("household password", "household password", context, redirect))
                .isEqualTo("redirect:/setup");

        verify(login).setPassword("household password", "household password", context);
        assertThat(flash(redirect)).containsEntry("loginMessage",
                "Password set. Every browser now needs it to open Home Control.");
        assertThat(limiter.blockedFor(ADDRESS)).isEmpty();
    }

    @Test
    void aRejectedNewPasswordIsShown() {
        willThrow(new PasswordRejectedException("The two passwords do not match"))
                .given(login).setPassword("household password", "different", context);
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

        controller.setPassword("household password", "different", context, redirect);

        assertThat(flash(redirect)).containsEntry("loginError", "The two passwords do not match");
    }

    @Test
    void removingThePasswordReportsSuccessAndClearsTheAddress() {
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

        assertThat(controller.removePassword("household password", request, redirect)).isEqualTo("redirect:/setup");

        verify(login).removePassword("household password");
        assertThat(flash(redirect)).containsEntry("loginMessage",
                "Password removed. Anyone on your network can open Home Control.");
        assertThat(limiter.blockedFor(ADDRESS)).isEmpty();
    }

    @Test
    void aWrongCurrentPasswordIsAGuessAndTheNextRemovalWaits() {
        willThrow(new WrongPasswordException("The current password is wrong")).given(login).removePassword("guess");
        RedirectAttributesModelMap first = new RedirectAttributesModelMap();

        controller.removePassword("guess", request, first);

        assertThat(flash(first)).containsEntry("loginError", "The current password is wrong");
        RedirectAttributesModelMap second = new RedirectAttributesModelMap();
        controller.removePassword("household password", request, second);
        assertThat(flash(second)).containsEntry("loginError", "Too many attempts. Try again in 15 minutes.");
        verify(login, times(1)).removePassword(any());
    }

    @Test
    void aRefusalWhileAccountsAreConnectedIsNotAGuess() {
        willThrow(new PasswordRejectedException("Disconnect Jellyfin first: its credentials need the login password"))
                .given(login).removePassword("household password");
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

        controller.removePassword("household password", request, redirect);

        assertThat(flash(redirect)).containsEntry("loginError",
                "Disconnect Jellyfin first: its credentials need the login password");
        assertThat(limiter.blockedFor(ADDRESS)).isEmpty();
    }

    @Test
    void anUnexpectedFailureWhileChangingThePasswordIsNotAGuess() {
        willThrow(new IllegalStateException("disk gone")).given(login).changePassword("old", "new", "new", context);
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

        assertThatThrownBy(() -> controller.changePassword("old", "new", "new", request, context, redirect))
                .isInstanceOf(IllegalStateException.class);
        assertThat(limiter.blockedFor(ADDRESS)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/devices", "/setup#connections", "/?device=living-room&x=%2F%2F", "/a/b.c"})
    void keepsSameApplicationPaths(String next) {
        assertThat(LoginController.safeNext(next)).isEqualTo(next);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "devices", "https://evil.example/", "//evil.example", "/\\evil.example",
            "/a\\b", "/a\tb", "/a\r\nSet-Cookie:x=y", "/a\u0000", "/a\u007f", "/{x}", "/a}"})
    void replacesEverythingElseWithTheHomePage(String next) {
        assertThat(LoginController.safeNext(next)).isEqualTo("/");
    }
}
