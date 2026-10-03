package dev.andre.homecontrol.security;

import dev.andre.homecontrol.config.Json;
import dev.andre.homecontrol.storage.StorageException;
import dev.andre.homecontrol.storage.VersionedJsonFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseCookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Logins that outlive the server's sessions, which live in memory and end with every restart. A browser that logs in
 * also gets a {@value #COOKIE} cookie with a random token; logins.json keeps only the token's SHA-256 hash, the version
 * of the password it logged in with, and when it expires. A token is never rotated: after a restart a page's requests
 * arrive together, each with the same token, and each is let in. The file holds nothing a browser could log in with,
 * and losing it only logs every browser out, so a damaged file remembers nobody and the next login replaces it.
 */
public class RememberedLogins {

    static final String COOKIE = "HOME_CONTROL_LOGIN";
    /** As long as the session cookie lasts (server.servlet.session.cookie.max-age). */
    static final Duration LIFETIME = Duration.ofDays(30);

    private static final Logger log = LoggerFactory.getLogger(RememberedLogins.class);
    private static final int VERSION = 1;
    private static final int TOKEN_BYTES = 32;

    private final VersionedJsonFile<List<Login>> file;
    private final Clock clock;
    private final SecureRandom random;
    private final boolean secureCookie;
    private final AtomicBoolean warned = new AtomicBoolean();
    /** Hashes of tokens a browser logged out with: never let in again, even if the file kept them. */
    private final Set<String> forgotten = new HashSet<>();

    /** One remembered browser: its token's hash, the password version it logged in with, and its end. */
    record Login(String hash, String version, Instant expires) {
    }

    public RememberedLogins(Path path, Clock clock, SecureRandom random, boolean secureCookie) {
        this.file = new VersionedJsonFile<>(path, "remembered logins", VERSION, List::of,
                RememberedLogins::read, RememberedLogins::write);
        this.clock = clock;
        this.random = random;
        this.secureCookie = secureCookie;
    }

    /**
     * A new token for a browser that just logged in with this password version; empty if it cannot be stored. Logins
     * that have expired, or were made with an earlier password, can never log anyone in again and are dropped.
     */
    public synchronized Optional<String> remember(String version) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = clock.instant();
        List<Login> next = new ArrayList<>(current().stream()
                .filter(login -> login.expires().isAfter(now) && login.version().equals(version))
                .toList());
        next.add(new Login(hash(token), version, now.plus(LIFETIME)));
        try {
            file.write(List.copyOf(next));
            return Optional.of(token);
        } catch (StorageException e) {
            log.warn("Could not remember a login; it lasts until the server restarts", e);
            return Optional.empty();
        }
    }

    /** The password version a still valid token logged in with. */
    public synchronized Optional<String> versionOf(String token) {
        return valid(hash(token)).map(Login::version);
    }

    /** Whether a token still logs a browser in, whatever password version it was made with. */
    public synchronized boolean remembers(String token) {
        return remembersHash(hash(token));
    }

    /** As {@link #remembers(String)}, for a session that keeps the hash of the token it was made from. */
    synchronized boolean remembersHash(String hash) {
        return valid(hash).isPresent();
    }

    private Optional<Login> valid(String hash) {
        if (forgotten.contains(hash)) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        return current().stream()
                .filter(login -> login.hash().equals(hash) && login.expires().isAfter(now))
                .findFirst();
    }

    /** A browser logged out: its token logs nobody in any more, even if the file cannot be written. */
    public synchronized void forget(String token) {
        String hash = hash(token);
        forgotten.add(hash);
        List<Login> remembered = current();
        if (remembered.stream().noneMatch(login -> login.hash().equals(hash))) {
            return;
        }
        try {
            file.write(remembered.stream().filter(login -> !login.hash().equals(hash)).toList());
        } catch (StorageException e) {
            log.warn("Could not forget a remembered login on disk; it stays forgotten until the server restarts", e);
        }
    }

    /** Forgets every login. Exists for the shared test context. */
    public synchronized void clear() {
        file.delete();
        forgotten.clear();
    }

    /** Secure when {@code HOME_CONTROL_SECURE_COOKIE} says so, or, as for the session cookie, over HTTPS. */
    ResponseCookie cookie(String token, boolean secureRequest) {
        return base(token, secureRequest).maxAge(LIFETIME).build();
    }

    ResponseCookie expiredCookie(boolean secureRequest) {
        return base("", secureRequest).maxAge(Duration.ZERO).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value, boolean secureRequest) {
        return ResponseCookie.from(COOKIE, value).path("/").httpOnly(true)
                .secure(secureCookie || secureRequest).sameSite("Lax");
    }

    private List<Login> current() {
        try {
            return file.read();
        } catch (StorageException e) {
            if (warned.compareAndSet(false, true)) {
                log.warn("Remembered logins are unreadable; browsers log in again, and the next login replaces them",
                        e);
            }
            return List.of();
        }
    }

    private static List<Login> read(JsonNode root) {
        if (!root.isObject()) {
            throw new IllegalArgumentException("document must be a JSON object");
        }
        List<Login> logins = new ArrayList<>();
        for (JsonNode entry : root.path("logins")) {
            String hash = entry.path("hash").asString("");
            String version = entry.path("version").asString("");
            try {
                Instant expires = Instant.parse(entry.path("expires").asString(""));
                if (!hash.isEmpty() && !version.isEmpty()) {
                    logins.add(new Login(hash, version, expires));
                }
            } catch (DateTimeParseException _) {
                // An entry without a readable end logs nobody in.
            }
        }
        return List.copyOf(logins);
    }

    private static ObjectNode write(List<Login> logins) {
        ObjectNode root = Json.MAPPER.createObjectNode();
        ArrayNode entries = root.putArray("logins");
        for (Login login : logins) {
            entries.addObject()
                    .put("hash", login.hash())
                    .put("version", login.version())
                    .put("expires", login.expires().toString());
        }
        return root;
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
