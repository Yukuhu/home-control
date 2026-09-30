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

/** The single household password (spec §9): set and removed on purpose, required while it exists. */
public class LoginService {

    private static final Logger log = LoggerFactory.getLogger(LoginService.class);

    public static final int MIN_PASSWORD_LENGTH = 10;
    public static final int MAX_PASSWORD_LENGTH = 1024;
    static final String SESSION_ATTRIBUTE = LoginService.class.getName() + ".version";
    /** What the Account section calls each kind of account credential, by the first part of its name. */
    private static final Map<String, String> ACCOUNTS = Map.of("jellyfin", "Jellyfin", "youtube", "YouTube",
            "tmdb", "TMDB", "sports", "Sports", "workflow", "Workflows");

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
        } catch (IllegalStateException _) {
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

    public boolean authenticate(String password, LoginContext context) {
        Optional<LoginCredential> login = store.login();
        if (login.isEmpty()) {
            return true;
        }
        if (!verify(password, login.get())) {
            return false;
        }
        context.startSession(login.get().version());
        return true;
    }

    public void logout(LoginContext context) {
        context.endSession();
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
     * The first account credentials need a new login password and log this browser in; later ones need a
     * logged-in browser. Nothing is stored before the password is accepted.
     */
    public void storeSecrets(Map<String, String> secrets, String newPassword, String confirmation,
                             LoginContext context) {
        synchronized (this) {
            if (store.login().isPresent()) {
                if (!context.loggedIn()) {
                    throw new LoginRequiredException();
                }
                store.putSecrets(secrets);
                return;
            }
            checkNewPassword(newPassword, confirmation);
            LoginCredential credential = newCredential(newPassword);
            store.putFirstSecrets(secrets, credential);
            context.startSession(credential.version());
        }
        changed();
    }

    /**
     * Before work that ends in {@link #storeSecrets}: this browser is logged in, or, while no password is set, the new
     * one is acceptable. Throws what {@code storeSecrets} would, before anything is fetched or stored.
     */
    public void permitSecrets(LoginContext context, String newPassword, String confirmation) {
        if (loginRequired()) {
            context.requireLogin();
        } else {
            checkNewPassword(newPassword, confirmation);
        }
    }

    public void removeSecrets(Collection<String> names) {
        synchronized (this) {
            store.removeSecrets(names);
        }
        changed();
    }

    /** The sources whose credentials need the login, by name, each once, sorted. */
    public List<String> connectedAccounts() {
        return store.accountCredentialNames().stream().map(LoginService::account).distinct().sorted().toList();
    }

    private static String account(String secretName) {
        int dot = secretName.indexOf('.');
        String kind = dot < 0 ? secretName : secretName.substring(0, dot);
        return ACCOUNTS.getOrDefault(kind, kind);
    }

    /** Sets the first login password and logs this browser in. The slow hash runs outside the lock. */
    public void setPassword(String password, String confirmation, LoginContext context) {
        checkNewPassword(password, confirmation);
        LoginCredential credential = newCredential(password);
        synchronized (this) {
            if (store.login().isPresent()) {
                throw new PasswordRejectedException("A login password is already set; change it instead");
            }
            store.setLogin(credential);
            context.startSession(credential.version());
        }
        changed();
    }

    /**
     * Removes the login password. The accounts are checked first, so a refusal never costs a guess. The current
     * password is checked outside the lock. The login is then removed only if it was not changed meanwhile and no
     * account was connected meanwhile. Sessions need no ending: without a login every browser is let in, and a
     * session's version matches no later password.
     */
    public void removePassword(String current) {
        LoginCredential login = store.login()
                .orElseThrow(() -> new PasswordRejectedException("There is no login password to remove"));
        refuseWhileAccountsAreConnected();
        if (!verify(current, login)) {
            throw new WrongPasswordException("The current password is wrong");
        }
        synchronized (this) {
            if (!store.login().map(login::equals).orElse(false)) {
                throw new PasswordRejectedException("The password was changed meanwhile; try again");
            }
            refuseWhileAccountsAreConnected();
            store.removeLogin();
        }
        changed();
    }

    private void refuseWhileAccountsAreConnected() {
        List<String> accounts = connectedAccounts();
        if (accounts.isEmpty()) {
            return;
        }
        String names = accounts.size() == 1 ? accounts.getFirst()
                : String.join(", ", accounts.subList(0, accounts.size() - 1)) + " and " + accounts.getLast();
        throw new PasswordRejectedException("Disconnect " + names + " first: "
                + (accounts.size() == 1 ? "its" : "their") + " credentials need the login password");
    }

    /**
     * The new password is checked first, so a {@link WrongPasswordException} always means a wrong
     * guess of the current one. The slow checks run outside the lock; the login is only replaced if
     * nobody changed it meanwhile.
     */
    public void changePassword(String current, String next, String confirmation, LoginContext context) {
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
            context.startSession(credential.version());
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
        } catch (InterruptedException _) {
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
}
