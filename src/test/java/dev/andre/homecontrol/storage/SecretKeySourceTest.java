package dev.andre.homecontrol.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretKeySourceTest {

    @TempDir
    Path dir;

    private SecretKeySource source(String passphrase) {
        return new SecretKeySource(passphrase, dir.resolve("secret.key"), new SecureRandom());
    }

    @Test
    void aKeyFileIsCreatedOnlyWhenWritingAndReusedAfterwards() throws Exception {
        assertThat(source(null).usesPassphrase()).isFalse();
        assertThat(source("").usesPassphrase()).isFalse();
        assertThat(source("   ").usesPassphrase()).isFalse();
        assertThat(Files.exists(dir.resolve("secret.key"))).isFalse();

        SecretKeySource keys = source(null);
        SecretKeySource.Keyed first = keys.forWriting(null);
        SecretKeySource.Keyed second = keys.forWriting(null);

        assertThat(second.key()).isEqualTo(first.key());
        assertThat(first.header().source()).isEqualTo(SecretKeySource.SOURCE_KEY_FILE);
        try (var files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("secret.key");
        }
        assertThat(source(null).keyFor(first.header())).isEqualTo(first.key());
    }

    @Test
    void aPassphraseKeepsItsSaltAcrossWrites() {
        SecretKeySource keys = source("correct horse battery staple");

        SecretKeySource.Keyed first = keys.forWriting(null);
        SecretKeySource.Keyed second = keys.forWriting(first.header());

        assertThat(first.header().source()).isEqualTo(SecretKeySource.SOURCE_PASSPHRASE);
        assertThat(second.header().salt()).isEqualTo(first.header().salt());
        assertThat(second.key()).isEqualTo(first.key());

        SecretKeySource.KeyHeader keyFileHeader = new SecretKeySource.KeyHeader(SecretKeySource.SOURCE_KEY_FILE, null, 0, 0, 0);
        SecretKeySource.Keyed migrated = keys.forWriting(keyFileHeader);
        assertThat(migrated.header().source()).isEqualTo(SecretKeySource.SOURCE_PASSPHRASE);
        assertThat(migrated.header().salt()).hasSize(16);
        assertThat(Files.exists(dir.resolve("secret.key"))).isFalse();
    }

    @Test
    void aKeyFileOfTheWrongLengthIsRefused() throws Exception {
        Files.writeString(dir.resolve("secret.key"), Base64.getEncoder().encodeToString(new byte[16]) + "\n");

        assertThatThrownBy(() -> source(null).forWriting(null))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("secret.key");
    }
}
