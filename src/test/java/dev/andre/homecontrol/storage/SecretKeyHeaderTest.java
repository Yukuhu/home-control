package dev.andre.homecontrol.storage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecretKeyHeaderTest {

    private static SecretKeySource.KeyHeader passphrase(byte[] salt, int memoryKiB, int iterations, int parallelism) {
        return new SecretKeySource.KeyHeader(SecretKeySource.SOURCE_PASSPHRASE, salt, memoryKiB, iterations, parallelism);
    }

    @Test
    void headersCompareTheirSaltByContent() {
        var header = passphrase(new byte[] {1, 2, 3}, 19456, 2, 1);
        var same = passphrase(new byte[] {1, 2, 3}, 19456, 2, 1);

        assertThat(header).isEqualTo(same).hasSameHashCodeAs(same)
                .isNotEqualTo(passphrase(new byte[] {1, 2, 4}, 19456, 2, 1))
                .isNotEqualTo(passphrase(new byte[] {1, 2, 3}, 1024, 2, 1))
                .isNotEqualTo(passphrase(new byte[] {1, 2, 3}, 19456, 3, 1))
                .isNotEqualTo(passphrase(new byte[] {1, 2, 3}, 19456, 2, 2))
                .isNotEqualTo(new SecretKeySource.KeyHeader(SecretKeySource.SOURCE_KEY_FILE, new byte[] {1, 2, 3}, 19456, 2, 1))
                .isNotEqualTo("header");
        assertThat(new SecretKeySource.KeyHeader(SecretKeySource.SOURCE_KEY_FILE, null, 0, 0, 0))
                .isEqualTo(new SecretKeySource.KeyHeader(SecretKeySource.SOURCE_KEY_FILE, null, 0, 0, 0));
    }

    @Test
    void aPrintedHeaderNamesTheSaltLengthNotItsBytes() {
        assertThat(passphrase(new byte[] {42, 43}, 19456, 2, 1)).hasToString(
                "KeyHeader[source=HOME_CONTROL_SECRET, salt=2 bytes, memoryKiB=19456, iterations=2, parallelism=1]");
        assertThat(new SecretKeySource.KeyHeader(SecretKeySource.SOURCE_KEY_FILE, null, 0, 0, 0)).hasToString(
                "KeyHeader[source=secret.key, salt=none, memoryKiB=0, iterations=0, parallelism=0]");
    }
}
