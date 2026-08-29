package dev.andre.shield.protocol;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PairingDigestTest {

    private static final byte[] CLIENT_MODULUS = HexFormat.of().parseHex("A1A2A3");
    private static final byte[] SERVER_MODULUS = HexFormat.of().parseHex("B1B2B3");
    private static final byte[] EXPONENT = HexFormat.of().parseHex("010001");

    @Test
    void digestMatchesTheReferenceVector() {
        // SHA-256 over A1A2A3 | 010001 | B1B2B3 | 010001 | B2C3
        byte[] digest = PairingDigest.digest(
                CLIENT_MODULUS, EXPONENT, SERVER_MODULUS, EXPONENT, "70B2C3");

        assertThat(HexFormat.of().formatHex(digest))
                .isEqualTo("70d96b97cd3727547f93cdf73cf7d701291cf97af7f78632db7e0f96f301d4df");
    }

    @Test
    void digestMatchesASecondReferenceVector() {
        // SHA-256 over A1A2A3 | 010001 | B1B2B3 | 010001 | FFEE
        byte[] digest = PairingDigest.digest(
                CLIENT_MODULUS, EXPONENT, SERVER_MODULUS, EXPONENT, "1EFFEE");

        assertThat(HexFormat.of().formatHex(digest))
                .isEqualTo("1ece4613ef7d5015baa717c8001a4c21cf7f3c08a250204d317dfe2e0a69c357");
    }

    @Test
    void onlyTheLastFourHexCharactersOfTheCodeEnterTheDigest() {
        // The first two characters are a check byte, not input.
        assertThat(PairingDigest.digest(CLIENT_MODULUS, EXPONENT, SERVER_MODULUS, EXPONENT, "70B2C3"))
                .isEqualTo(PairingDigest.digest(CLIENT_MODULUS, EXPONENT, SERVER_MODULUS, EXPONENT, "FFB2C3"));
    }

    @Test
    void acceptsACodeWhoseCheckByteMatches() {
        byte[] digest = PairingDigest.digest(CLIENT_MODULUS, EXPONENT, SERVER_MODULUS, EXPONENT, "70B2C3");
        assertThat(PairingDigest.matchesCheckByte(digest, "70B2C3")).isTrue();
    }

    @Test
    void rejectsACodeWhoseCheckByteDisagrees() {
        byte[] digest = PairingDigest.digest(CLIENT_MODULUS, EXPONENT, SERVER_MODULUS, EXPONENT, "AAB2C3");
        assertThat(PairingDigest.matchesCheckByte(digest, "AAB2C3")).isFalse();
    }

    @Test
    void stripsTheSignByteFromAModulusWithAHighBitSet() {
        // A 2048-bit modulus with the top bit set: toByteArray() would return 257 bytes.
        BigInteger modulus = BigInteger.ONE.shiftLeft(2047).add(BigInteger.ONE);

        assertThat(modulus.toByteArray()).hasSize(257);
        assertThat(PairingDigest.unsignedBytes(modulus)).hasSize(256);
        assertThat(PairingDigest.unsignedBytes(modulus)[0]).isEqualTo((byte) 0x80);
    }

    @Test
    void leavesTheStandardExponentUntouched() {
        assertThat(PairingDigest.unsignedBytes(BigInteger.valueOf(65537)))
                .containsExactly((byte) 0x01, (byte) 0x00, (byte) 0x01);
    }

    @Test
    void rejectsAMalformedCode() {
        assertThatThrownBy(() -> PairingDigest.digest(
                CLIENT_MODULUS, EXPONENT, SERVER_MODULUS, EXPONENT, "12345"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
