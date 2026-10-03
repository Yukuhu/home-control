package dev.andre.homecontrol.security;

import dev.andre.homecontrol.testsupport.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** Logins that outlive the server's sessions: a token in the browser, only its hash in logins.json. */
class RememberedLoginsTest {

    @TempDir
    Path dir;

    private final MutableClock clock = MutableClock.at(Instant.parse("2026-10-03T08:00:00Z"));
    private Path file;

    @BeforeEach
    void setUp() {
        file = dir.resolve("logins.json");
    }

    private RememberedLogins logins(boolean secureCookie) {
        return new RememberedLogins(file, clock, new SecureRandom(), secureCookie);
    }

    private RememberedLogins logins() {
        return logins(false);
    }

    @Test
    void aTokenIsRememberedWithItsPasswordVersionAndOnlyItsHashIsStored() throws Exception {
        String token = logins().remember("v1").orElseThrow();

        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        assertThat(logins().versionOf(token)).as("a new instance, as after a restart").contains("v1");
        String stored = Files.readString(file);
        assertThat(stored).doesNotContain(token).contains(sha256(token)).contains("\"v1\"");
    }

    @Test
    void anUnknownTokenOrAMissingFileRemembersNobody() {
        assertThat(logins().versionOf("not-a-token")).isEmpty();

        logins().remember("v1");

        assertThat(logins().versionOf("not-a-token")).isEmpty();
    }

    @Test
    void aForgottenTokenIsNotRememberedAndTheOthersAre() {
        RememberedLogins logins = logins();
        String phone = logins.remember("v1").orElseThrow();
        String tablet = logins.remember("v1").orElseThrow();

        logins.forget(phone);

        assertThat(logins().versionOf(phone)).isEmpty();
        assertThat(logins().versionOf(tablet)).contains("v1");
    }

    @Test
    void aTokenIsForgottenEvenWhenTheFileCannotBeWritten() throws Exception {
        assumeFalse("root".equals(System.getProperty("user.name")), "root may write everywhere");
        RememberedLogins logins = logins();
        String token = logins.remember("v1").orElseThrow();
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            logins.forget(token);

            assertThat(logins.versionOf(token)).isEmpty();
            assertThat(logins.remembers(token)).isFalse();
        } finally {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void aLoginIsRememberedForThirtyDaysFromWhenItWasMade() throws Exception {
        RememberedLogins logins = logins();
        String old = logins.remember("v1").orElseThrow();

        clock.advance(Duration.ofDays(30).minusSeconds(1));
        assertThat(logins.versionOf(old)).contains("v1");
        clock.advance(Duration.ofSeconds(1));
        assertThat(logins.versionOf(old)).isEmpty();

        logins.remember("v1");
        assertThat(Files.readString(file)).as("an expired login is dropped at the next write")
                .doesNotContain(sha256(old));
    }

    @Test
    void aLoginWithANewPasswordDropsTheLoginsOfEarlierOnes() throws Exception {
        RememberedLogins logins = logins();
        String before = logins.remember("v1").orElseThrow();

        logins.remember("v2");

        assertThat(Files.readString(file)).doesNotContain(sha256(before)).doesNotContain("\"v1\"");
    }

    @Test
    void aDamagedFileRemembersNobodyAndTheNextLoginReplacesIt() throws Exception {
        Files.writeString(file, "{ not json");
        RememberedLogins logins = logins();

        assertThat(logins.versionOf("any")).isEmpty();
        String token = logins.remember("v2").orElseThrow();

        assertThat(logins().versionOf(token)).contains("v2");
    }

    @Test
    void theCookieLastsAsLongAsTheLoginAndScriptsCannotReadIt() {
        RememberedLogins logins = logins();

        assertThat(logins.cookie("abc", false).toString())
                .startsWith("HOME_CONTROL_LOGIN=abc; Path=/; Max-Age=2592000; Expires=")
                .endsWith("; HttpOnly; SameSite=Lax")
                .doesNotContain("Secure");
        assertThat(logins(true).cookie("abc", false).toString()).as("HOME_CONTROL_SECURE_COOKIE").contains("; Secure;");
        assertThat(logins.cookie("abc", true).toString()).as("a request over HTTPS").contains("; Secure;");
        assertThat(logins.expiredCookie(false).toString()).startsWith("HOME_CONTROL_LOGIN=; Path=/; Max-Age=0;");
    }

    @Test
    void clearForgetsEveryLogin() {
        RememberedLogins logins = logins();
        String token = logins.remember("v1").orElseThrow();

        logins.clear();

        assertThat(logins.versionOf(token)).isEmpty();
        assertThat(file).doesNotExist();
    }

    private static String sha256(String token) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8)));
    }
}
