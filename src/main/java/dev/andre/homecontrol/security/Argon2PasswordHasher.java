package dev.andre.homecontrol.security;

import dev.andre.homecontrol.crypto.Argon2id;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Argon2id password hashes in PHC string format (OWASP minimum parameters). */
public final class Argon2PasswordHasher {

    public static final int MEMORY_KIB = 19456;
    public static final int ITERATIONS = 2;
    public static final int PARALLELISM = 1;
    static final int SALT_BYTES = 16;
    static final int HASH_BYTES = 32;
    private static final int MAX_MEMORY_KIB = 262_144;
    private static final int MAX_ITERATIONS = 10;
    private static final Pattern PHC = Pattern.compile(
            "\\$argon2id\\$v=19\\$m=(\\d{1,7}),t=(\\d{1,2}),p=(\\d{1,2})\\$([A-Za-z0-9+/]{11,88})\\$([A-Za-z0-9+/]{22,88})");
    private static final Base64.Encoder ENCODER = Base64.getEncoder().withoutPadding();

    private final SecureRandom random;

    public Argon2PasswordHasher(SecureRandom random) {
        this.random = random;
    }

    public String hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] hash = Argon2id.derive(password.getBytes(StandardCharsets.UTF_8), salt,
                MEMORY_KIB, ITERATIONS, PARALLELISM, HASH_BYTES);
        return "$argon2id$v=19$m=" + MEMORY_KIB + ",t=" + ITERATIONS + ",p=" + PARALLELISM
                + "$" + ENCODER.encodeToString(salt) + "$" + ENCODER.encodeToString(hash);
    }

    /** False for a wrong password and for any malformed or abusive hash string; never throws. */
    public boolean matches(String password, String encoded) {
        if (password == null || encoded == null) {
            return false;
        }
        Matcher matcher = PHC.matcher(encoded);
        if (!matcher.matches()) {
            return false;
        }
        int memory = Integer.parseInt(matcher.group(1));
        int iterations = Integer.parseInt(matcher.group(2));
        int parallelism = Integer.parseInt(matcher.group(3));
        if (parallelism < 1 || parallelism > 16 || iterations < 1 || iterations > MAX_ITERATIONS
                || memory < 8 * parallelism || memory > MAX_MEMORY_KIB) {
            return false;
        }
        byte[] salt;
        byte[] expected;
        try {
            salt = Base64.getDecoder().decode(matcher.group(4));
            expected = Base64.getDecoder().decode(matcher.group(5));
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (salt.length < 8 || expected.length < 16 || expected.length > 64) {
            return false;
        }
        byte[] actual = Argon2id.derive(password.getBytes(StandardCharsets.UTF_8), salt,
                memory, iterations, parallelism, expected.length);
        return MessageDigest.isEqual(actual, expected);
    }
}
