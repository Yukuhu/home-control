package dev.andre.homecontrol.security;

import dev.andre.homecontrol.storage.LoginCredential;
import dev.andre.homecontrol.storage.SecretStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** The single household password (spec §9). A login exists exactly when secrets exist. */
public class LoginService {

    private static final Logger log = LoggerFactory.getLogger(LoginService.class);

    public static final int MIN_PASSWORD_LENGTH = 10;
    public static final int MAX_PASSWORD_LENGTH = 1024;
    static final String SESSION_ATTRIBUTE = LoginService.class.getName() + ".version";

    private final SecretStore store;
    private final Argon2PasswordHasher hasher;
    private final SecureRandom random;
    /** Each verification holds ~19 MiB; two at a time bounds memory under a login flood. */
    private final Semaphore verifications = new Semaphore(2);
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public LoginService(SecretStore store, Argon2PasswordHasher hasher, SecureRandom random) {
        this.store = store;
        this.hasher = hasher;
        this.random = random;
    }

    public boolean loginRequired() {
        return store.login().isPresent();
    }

    public boolean isAuthenticated(HttpServletRequest request) {
        return isAuthenticated(request.getSession(false));
    }

    /** For work that outlives its request, such as a state stream; false once the session is invalidated. */
    public boolean isAuthenticated(HttpSession session) {
        Optional<LoginCredential> login = store.login();
        if (login.isEmpty()) {
            return true;
        }
        if (session == null) {
            return false;
        }
        try {
            return login.get().version().equals(session.getAttribute(SESSION_ATTRIBUTE));
        } catch (IllegalStateException invalidated) {
            return false;
        }
    }

    /**
     * Runs after anything that can change who is logged in: the first secret, a password change,
     * a logout, removing secrets. Listeners re-check what they hand out (e.g. open state streams).
     */
    public void onChange(Runnable listener) {
        listeners.add(listener);
    }

    public boolean authenticate(String password, HttpServletRequest request) {
        Optional<LoginCredential> login = store.login();
        if (login.isEmpty()) {
            return true;
        }
        if (!verify(password, login.get())) {
            return false;
        }
        startSession(request, login.get());
        return true;
    }

    public void logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            try {
                session.invalidate();
            } catch (IllegalStateException alreadyInvalidated) {
                // nothing left to end
            }
        }
        changed();
    }

    public void checkNewPassword(String password, String confirmation) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new PasswordRejectedException("The login password needs at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        if (password.length() > MAX_PASSWORD_LENGTH) {
            throw new PasswordRejectedException("The login password can have at most " + MAX_PASSWORD_LENGTH + " characters");
        }
        if (!password.equals(confirmation)) {
            throw new PasswordRejectedException("The two passwords do not match");
        }
    }

    /**
     * The first secrets need a new login password and log this browser in; later secrets need an
     * already authenticated request. Nothing is stored before the password is accepted.
     */
    public void storeSecrets(Map<String, String> secrets, String newPassword, String confirmation,
                             HttpServletRequest request) {
        synchronized (this) {
            if (store.login().isPresent()) {
                if (!isAuthenticated(request)) {
                    throw new LoginRequiredException();
                }
                store.putSecrets(secrets);
                return;
            }
            checkNewPassword(newPassword, confirmation);
            LoginCredential credential = newCredential(newPassword);
            store.putFirstSecrets(secrets, credential);
            startSession(request, credential);
        }
        changed();
    }

    public void removeSecrets(Collection<String> names) {
        synchronized (this) {
            store.removeSecrets(names);
        }
        changed();
    }

    /**
     * The new password is checked first, so a {@link WrongPasswordException} always means a wrong
     * guess of the current one. The slow checks run outside the lock; the login is only replaced if
     * nobody changed it meanwhile.
     */
    public void changePassword(String current, String next, String confirmation, HttpServletRequest request) {
        LoginCredential login = store.login()
                .orElseThrow(() -> new PasswordRejectedException("There is no login password to change"));
        checkNewPassword(next, confirmation);
        if (!verify(current, login)) {
            throw new WrongPasswordException("The current password is wrong");
        }
        LoginCredential credential = newCredential(next);
        synchronized (this) {
            if (!store.login().map(login::equals).orElse(false)) {
                throw new PasswordRejectedException("The password was changed meanwhile; try again");
            }
            store.replaceLogin(credential);
            startSession(request, credential);
        }
        changed();
    }

    /** The change already happened; a failing listener must not turn it into an error for the user. */
    private void changed() {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                log.warn("A login change listener failed", e);
            }
        }
    }

    private boolean verify(String password, LoginCredential login) {
        if (password == null || password.length() > MAX_PASSWORD_LENGTH) {
            return false;
        }
        boolean acquired;
        try {
            acquired = verifications.tryAcquire(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LoginBusyException();
        }
        if (!acquired) {
            throw new LoginBusyException();
        }
        try {
            return hasher.matches(password, login.passwordHash());
        } finally {
            verifications.release();
        }
    }

    private LoginCredential newCredential(String password) {
        byte[] version = new byte[16];
        random.nextBytes(version);
        return new LoginCredential(hasher.hash(password), Base64.getUrlEncoder().withoutPadding().encodeToString(version));
    }

    private static void startSession(HttpServletRequest request, LoginCredential credential) {
        HttpSession session = request.getSession(true);
        request.changeSessionId(); // no session fixation
        session.setAttribute(SESSION_ATTRIBUTE, credential.version());
    }
}
