package dev.andre.homecontrol.security;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.assertj.core.api.Assertions.assertThat;

class Argon2PasswordHasherTest {

    private static final String KNOWN_HASH =
            "$argon2id$v=19$m=19456,t=2,p=1$c29tZXNhbHQ$PL01amPyeUuxG7H0vIr5X+qHkZvWnHmGBGXFYvh8z2E";

    private final Argon2PasswordHasher hasher = new Argon2PasswordHasher(new SecureRandom());

    @Test
    void hashesWithTheOwaspParametersInPhcFormat() {
        String first = hasher.hash("correct horse battery");
        String second = hasher.hash("correct horse battery");

        assertThat(first).matches("^\\$argon2id\\$v=19\\$m=19456,t=2,p=1\\$[A-Za-z0-9+/]{22}\\$[A-Za-z0-9+/]{43}$");
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void verifiesTheKnownHashOfPassword() {
        assertThat(hasher.matches("password", KNOWN_HASH)).isTrue();
        assertThat(hasher.matches("Password", KNOWN_HASH)).isFalse();
    }

    @Test
    void roundTripsAFreshHash() {
        String password = "a household password";
        String hash = hasher.hash(password);

        assertThat(hasher.matches(password, hash)).isTrue();
        assertThat(hasher.matches(password + "x", hash)).isFalse();
    }

    @Test
    void rejectsMalformedOrAbusiveHashesWithoutThrowing() {
        assertThat(hasher.matches("password", null)).isFalse();
        assertThat(hasher.matches("password", "")).isFalse();
        assertThat(hasher.matches("password",
                "$argon2i$v=19$m=19456,t=2,p=1$c29tZXNhbHQ$PL01amPyeUuxG7H0vIr5X+qHkZvWnHmGBGXFYvh8z2E")).isFalse();
        assertThat(hasher.matches("password",
                "$argon2id$v=16$m=19456,t=2,p=1$c29tZXNhbHQ$PL01amPyeUuxG7H0vIr5X+qHkZvWnHmGBGXFYvh8z2E")).isFalse();
        assertThat(hasher.matches("password",
                "$argon2id$v=19$m=9999999,t=2,p=1$c29tZXNhbHQ$PL01amPyeUuxG7H0vIr5X+qHkZvWnHmGBGXFYvh8z2E")).isFalse();
        assertThat(hasher.matches("password",
                "$argon2id$v=19$m=19456,t=0,p=1$c29tZXNhbHQ$PL01amPyeUuxG7H0vIr5X+qHkZvWnHmGBGXFYvh8z2E")).isFalse();
        assertThat(hasher.matches("password",
                "$argon2id$v=19$m=19456,t=2,p=1$!!$PL01amPyeUuxG7H0vIr5X+qHkZvWnHmGBGXFYvh8z2E")).isFalse();
    }
}
