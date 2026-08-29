package dev.andre.shield.protocol;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * The pairing proof: SHA-256 over both public keys and the nonce embedded in the
 * code the TV displays.
 *
 * <p>The code is six hexadecimal characters. The first two are a check byte equal to
 * the first byte of the digest; only the last four are hashed. Verified against the
 * {@code louis49/androidtv-remote} reference implementation.
 */
public final class PairingDigest {

    private PairingDigest() {
    }

    public static byte[] compute(RSAPublicKey clientKey, RSAPublicKey serverKey, String code) {
        byte[] digest = digest(
                unsignedBytes(clientKey.getModulus()),
                unsignedBytes(clientKey.getPublicExponent()),
                unsignedBytes(serverKey.getModulus()),
                unsignedBytes(serverKey.getPublicExponent()),
                code);

        if (!matchesCheckByte(digest, code)) {
            throw new WrongCodeException("Code " + code + " does not match the device's certificate");
        }
        return digest;
    }

    public static byte[] digest(byte[] clientModulus, byte[] clientExponent,
                                byte[] serverModulus, byte[] serverExponent, String code) {
        byte[] nonce = nonce(code);
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update(clientModulus);
            sha256.update(clientExponent);
            sha256.update(serverModulus);
            sha256.update(serverExponent);
            sha256.update(nonce);
            return sha256.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static boolean matchesCheckByte(byte[] digest, String code) {
        return digest[0] == checkByte(code);
    }

    /** Big-endian magnitude bytes, without the sign byte {@link BigInteger} prepends. */
    public static byte[] unsignedBytes(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            return Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return bytes;
    }

    private static byte[] nonce(String code) {
        return HexFormat.of().parseHex(normalise(code).substring(2));
    }

    private static byte checkByte(String code) {
        return HexFormat.of().parseHex(normalise(code).substring(0, 2))[0];
    }

    private static String normalise(String code) {
        String trimmed = code == null ? "" : code.trim();
        if (trimmed.length() != 6) {
            throw new IllegalArgumentException("Pairing code must be 6 hexadecimal characters");
        }
        return trimmed.toUpperCase();
    }

    public static class WrongCodeException extends RuntimeException {
        public WrongCodeException(String message) {
            super(message);
        }
    }
}
