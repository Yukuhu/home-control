package dev.andre.homecontrol.security;

import dev.andre.homecontrol.storage.DataDirectory;
import dev.andre.homecontrol.storage.LoginCredential;
import dev.andre.homecontrol.storage.SecretStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A forgotten login password: {@code home-control.security.reset-login} removes it without the device pairings. */
class LoginResetTest {

    @TempDir
    Path dir;

    private SecretStore start(boolean resetLogin) {
        SecurityProperties properties = new SecurityProperties(null, List.of(), 5, 50, Duration.ofMinutes(15), List.of(),
                resetLogin, List.of());
        return new SecurityConfiguration().secretStore(new DataDirectory(dir), properties, new SecureRandom());
    }

    @Test
    void theResetSettingRemovesTheLoginAndTheSourcesCredentialsAtStartup() {
        SecretStore before = start(false);
        before.putDeviceSecret("device.webos.0123456789abcdef.client-key", "k");
        before.putFirstSecrets(Map.of("jellyfin.token", "t"), new LoginCredential("h", "v1"));

        SecretStore after = start(true);

        assertThat(after.login()).isEmpty();
        assertThat(after.names()).containsExactly("device.webos.0123456789abcdef.client-key");
    }

    @Test
    void theResetRunsOnceSoASettingLeftInPlaceKeepsTheNewPassword() {
        start(false).putFirstSecrets(Map.of("jellyfin.token", "t"), new LoginCredential("h", "v1"));
        start(true);

        start(true).putFirstSecrets(Map.of("jellyfin.token", "t2"), new LoginCredential("h2", "v2"));
        SecretStore restartedWithTheSettingStillSet = start(true);

        assertThat(restartedWithTheSettingStillSet.login()).contains(new LoginCredential("h2", "v2"));
        assertThat(restartedWithTheSettingStillSet.secret("jellyfin.token")).contains("t2");
    }

    @Test
    void aStartWithoutTheSettingArmsTheResetAgain() {
        start(true);
        start(false).setLogin(new LoginCredential("h", "v1"));

        assertThat(start(true).login()).isEmpty();
    }

    @Test
    void withoutTheSettingTheLoginStays() {
        start(false).putFirstSecrets(Map.of("jellyfin.token", "t"), new LoginCredential("h", "v1"));

        assertThat(start(false).login()).isPresent();
    }
}
