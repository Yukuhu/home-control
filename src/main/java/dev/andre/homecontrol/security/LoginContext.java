package dev.andre.homecontrol.security;

import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * One browser's login, as controllers, services and stores see it. Controllers receive it as an argument; the
 * request-backed {@link RequestLoginContext} is the only code that touches the HTTP session for the login. A context
 * lives only as long as its request: work that outlives it, such as an event stream, keeps {@link #whileLoggedIn()}.
 */
public interface LoginContext {

    /** No login password is set, or this browser logged in with the current one. */
    boolean loggedIn();

    /** Throws {@link LoginRequiredException} unless {@link #loggedIn()}. */
    default void requireLogin() {
        if (!loggedIn()) {
            throw new LoginRequiredException();
        }
    }

    /** The same check for work that outlives the request, such as an event stream; false once the session ends. */
    BooleanSupplier whileLoggedIn();

    /** A key that stays the same for this browser session, starting a session if there is none. */
    String sessionKey();

    /** Logs this browser in with the given credential version under a new session id. For {@link LoginService}. */
    void startSession(String version);

    /** Ends this browser's session. For {@link LoginService}. */
    void endSession();

    /** The password version of the login this browser is remembered by, if any (see {@link RememberedLogins}). */
    Optional<String> rememberedVersion();

    /** Logs this browser back in with its remembered login, under a new session id. For {@link LoginService}. */
    void resumeSession(String version);
}
