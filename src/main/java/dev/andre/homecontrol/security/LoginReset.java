package dev.andre.homecontrol.security;

import dev.andre.homecontrol.storage.AtomicFiles;
import dev.andre.homecontrol.storage.DataDirectory;
import dev.andre.homecontrol.storage.SecretStore;
import dev.andre.homecontrol.storage.StorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code home-control.security.reset-login}, once. The first start with the setting removes the login and the
 * content sources' credentials and remembers that in /data, so a setting left in place never removes a new password
 * at a later restart. A start without the setting forgets that it ran.
 */
final class LoginReset {

    private static final Logger log = LoggerFactory.getLogger(LoginReset.class);

    private LoginReset() {
    }

    static void apply(boolean requested, SecretStore store, DataDirectory data) {
        Path marker = data.resolve(DataDirectory.LOGIN_RESET);
        try {
            if (!requested) {
                Files.deleteIfExists(marker);
                return;
            }
            if (Files.exists(marker)) {
                log.warn("home-control.security.reset-login is still set; it removed the login password once already"
                        + " and does nothing more. Unset it.");
                return;
            }
            store.forgetLogin();
            AtomicFiles.write(marker, "The login password was reset; unset home-control.security.reset-login.\n"
                    .getBytes(StandardCharsets.US_ASCII), true);
            log.warn("home-control.security.reset-login is set: removed the login password and the credentials of the"
                    + " connected content sources; unset it again, then set a new password and reconnect them");
        } catch (IOException e) {
            throw new StorageException("Could not record the login reset in " + marker
                    + "; check that /data is bind-mounted and writable", e);
        }
    }
}
