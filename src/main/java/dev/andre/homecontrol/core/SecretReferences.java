package dev.andre.homecontrol.core;

import java.security.SecureRandom;
import java.util.HexFormat;

/** The random source behind {@link DeviceSecrets#newReference()}, created once. */
final class SecretReferences {

    private static final SecureRandom RANDOM = new SecureRandom();

    private SecretReferences() {
    }

    static String next() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
