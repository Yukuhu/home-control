package dev.andre.homecontrol.adapters.androidtv;

import dev.andre.homecontrol.core.DeviceSecrets;
import dev.andre.homecontrol.storage.StorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

/**
 * The Android TV keystore's password. A configured one ({@code home-control.androidtv.keystore-password}, or the old
 * {@code SHIELD_KEYSTORE_PASSWORD}) is used as it is. Otherwise one is generated once and kept as a device secret, and
 * a keystore still under a password Home Control shipped is re-protected under it. The password is stored before the
 * keystore is re-protected, and every start re-protects a keystore that does not open with it, so a crash in between
 * costs nothing.
 */
final class KeystorePassword {

    static final String SECRET = DeviceSecrets.PREFIX + "androidtv.keystore-password";
    /** What application.yaml and compose.yaml shipped before the password was generated. */
    static final List<String> OLD_DEFAULTS = List.of("shield", "change-me");

    private static final Logger log = LoggerFactory.getLogger(KeystorePassword.class);

    private KeystorePassword() {
    }

    static char[] resolve(Path keystore, String configured, DeviceSecrets secrets, SecureRandom random) {
        if (configured != null && !configured.isBlank()) {
            return configured.toCharArray();
        }
        String password = secrets.deviceSecret(SECRET).orElseGet(() -> {
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            String generated = Base64.getEncoder().encodeToString(bytes);
            secrets.putDeviceSecret(SECRET, generated);
            return generated;
        });
        if (Files.exists(keystore) && !CertificateStore.opens(keystore, password.toCharArray())) {
            boolean reprotected = OLD_DEFAULTS.stream()
                    .anyMatch(old -> CertificateStore.reprotect(keystore, old.toCharArray(), password.toCharArray()));
            if (!reprotected) {
                throw new StorageException("keystore.p12 does not open with the stored password or an old default; set"
                        + " home-control.androidtv.keystore-password to the password it was created with, or delete"
                        + " keystore.p12 and pair the Android TV devices again");
            }
            log.info("Protected {} with a generated password, kept encrypted in secrets.json", keystore);
        }
        return password.toCharArray();
    }
}
