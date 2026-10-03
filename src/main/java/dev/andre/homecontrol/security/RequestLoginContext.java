package dev.andre.homecontrol.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

import java.util.Arrays;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * The login of the browser behind one request, kept in its HTTP session as {@link LoginService} always kept it. A login
 * is also remembered (see {@link RememberedLogins}), so that a browser stays logged in when the server restarts and
 * its sessions are gone.
 */
public final class RequestLoginContext implements LoginContext {

    private final HttpServletRequest request;
    private final HttpServletResponse response;
    private final LoginService login;
    private final RememberedLogins remembered;

    /** A context that remembers nothing beyond the session. */
    public RequestLoginContext(HttpServletRequest request, LoginService login) {
        this.request = request;
        this.response = null;
        this.login = login;
        this.remembered = null;
    }

    RequestLoginContext(HttpServletRequest request, HttpServletResponse response, LoginService login,
                        RememberedLogins remembered) {
        this.request = request;
        this.response = response;
        this.login = login;
        this.remembered = remembered;
    }

    @Override
    public boolean loggedIn() {
        return login.isAuthenticated(request);
    }

    @Override
    public BooleanSupplier whileLoggedIn() {
        HttpSession session = request.getSession(false);
        return () -> login.isAuthenticated(session);
    }

    @Override
    public String sessionKey() {
        return request.getSession(true).getId();
    }

    /** Also remembers the login in a new token, replacing the one this browser had. */
    @Override
    public void startSession(String version) {
        loggedInSession(version);
        if (remembers()) {
            token().ifPresent(remembered::forget);
            remembered.remember(version).ifPresent(token -> setCookie(remembered.cookie(token)));
        }
    }

    @Override
    public void resumeSession(String version) {
        loggedInSession(version);
    }

    private void loggedInSession(String version) {
        HttpSession session = request.getSession(true);
        request.changeSessionId(); // no session fixation
        session.setAttribute(LoginService.SESSION_ATTRIBUTE, version);
    }

    @Override
    public void endSession() {
        HttpSession session = request.getSession(false);
        if (session != null) {
            try {
                session.invalidate();
            } catch (IllegalStateException _) {
                // nothing left to end
            }
        }
        if (remembers()) {
            token().ifPresent(remembered::forget);
            setCookie(remembered.expiredCookie());
        }
    }

    @Override
    public Optional<String> rememberedVersion() {
        return remembered == null ? Optional.empty() : token().flatMap(remembered::versionOf);
    }

    private boolean remembers() {
        return remembered != null && response != null;
    }

    private Optional<String> token() {
        Cookie[] cookies = request.getCookies();
        return cookies == null ? Optional.empty() : Arrays.stream(cookies)
                .filter(cookie -> RememberedLogins.COOKIE.equals(cookie.getName()))
                .map(Cookie::getValue)
                .filter(value -> !value.isEmpty())
                .findFirst();
    }

    private void setCookie(ResponseCookie cookie) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
