package dev.andre.homecontrol.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import java.util.function.BooleanSupplier;

/**
 * The login of the browser behind one request, kept in its HTTP session as {@link LoginService} always kept it, so a
 * browser logged in before an upgrade stays logged in.
 */
public final class RequestLoginContext implements LoginContext {

    private final HttpServletRequest request;
    private final LoginService login;

    public RequestLoginContext(HttpServletRequest request, LoginService login) {
        this.request = request;
        this.login = login;
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

    @Override
    public void startSession(String version) {
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
    }
}
