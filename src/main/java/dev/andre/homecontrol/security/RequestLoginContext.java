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

    /**
     * Also remembers the login in a new token, replacing the one this browser had. The session keeps the token's hash,
     * so that forgetting the token ends it as well.
     */
    @Override
    public void startSession(String version) {
        HttpSession session = loggedInSession(version);
        session.removeAttribute(LoginService.TOKEN_ATTRIBUTE);
        if (remembers()) {
            token().ifPresent(remembered::forget);
            remembered.remember(version).ifPresent(token -> {
                session.setAttribute(LoginService.TOKEN_ATTRIBUTE, RememberedLogins.hash(token));
                setCookie(remembered.cookie(token, request.isSecure()));
            });
        }
    }

    /** Every session resumed from one token ends with it: two tabs reconnecting after a restart get one each. */
    @Override
    public void resumeSession(String version) {
        HttpSession session = loggedInSession(version);
        token().ifPresent(token -> session.setAttribute(LoginService.TOKEN_ATTRIBUTE, RememberedLogins.hash(token)));
    }

    private HttpSession loggedInSession(String version) {
        HttpSession session = request.getSession(true);
        request.changeSessionId(); // no session fixation
        session.setAttribute(LoginService.SESSION_ATTRIBUTE, version);
        return session;
    }

    /** Forgets the token first: a request arriving meanwhile must not resume the login that is ending. */
    @Override
    public boolean endSession() {
        boolean stored = !remembers() || token().map(remembered::forget).orElse(true);
        HttpSession session = request.getSession(false);
        if (session != null) {
            try {
                session.invalidate();
            } catch (IllegalStateException _) {
                // nothing left to end
            }
        }
        if (remembers()) {
            setCookie(remembered.expiredCookie(request.isSecure()));
        }
        return stored;
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
                .filter(cookie -> RememberedLogins.COOKIE_NAME.equals(cookie.getName()))
                .map(Cookie::getValue)
                .filter(value -> !value.isEmpty())
                .findFirst();
    }

    private void setCookie(ResponseCookie cookie) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
