package dev.andre.homecontrol.crypto;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/** Argon2id, version 0x13 (RFC 9106), through BouncyCastle. Used for the login hash and the secrets key. */
public final class Argon2id {

    private Argon2id() {
    }

    public static byte[] derive(byte[] password, byte[] salt, int memoryKiB, int iterations, int parallelism, int length) {
        return derive(password, salt, null, null, memoryKiB, iterations, parallelism, length);
    }

    public static byte[] derive(byte[] password, byte[] salt, byte[] secret, byte[] associatedData,
                                int memoryKiB, int iterations, int parallelism, int length) {
        Argon2Parameters.Builder builder = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(memoryKiB)
                .withIterations(iterations)
                .withParallelism(parallelism)
                .withSalt(salt);
        if (secret != null) {
            builder.withSecret(secret);
        }
        if (associatedData != null) {
            builder.withAdditional(associatedData);
        }
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(builder.build());
        byte[] out = new byte[length];
        generator.generateBytes(password, out);
        return out;
    }
}
