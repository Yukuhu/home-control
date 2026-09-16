package dev.andre.homecontrol.crypto;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class Argon2idTest {

    private static byte[] filled(int length, int value) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    @Test
    void reproducesTheRfc9106Argon2idTestVector() {
        byte[] tag = Argon2id.derive(filled(32, 1), filled(16, 2), filled(8, 3), filled(12, 4), 32, 3, 4, 32);

        assertThat(HexFormat.of().formatHex(tag))
                .isEqualTo("0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659");
    }

    @Test
    void matchesTheReferenceImplementationForAPlainPasswordAndSalt() {
        byte[] tag = Argon2id.derive("password".getBytes(StandardCharsets.UTF_8),
                "somesalt".getBytes(StandardCharsets.UTF_8), 65536, 2, 1, 32);

        // $argon2id$v=19$m=65536,t=2,p=1$c29tZXNhbHQ$CTFhFdXPJO1aFaMaO6Mm5c8y7cJHAph8ArZWb2GRPPc
        assertThat(java.util.Base64.getEncoder().withoutPadding().encodeToString(tag))
                .isEqualTo("CTFhFdXPJO1aFaMaO6Mm5c8y7cJHAph8ArZWb2GRPPc");
    }
}
