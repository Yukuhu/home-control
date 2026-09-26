package dev.andre.homecontrol.storage;

import dev.andre.homecontrol.crypto.Argon2id;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * The AES-256 key of {@code secrets.json}. With {@code HOME_CONTROL_SECRET} set the key is
 * Argon2id(secret, salt); otherwise 32 random bytes in {@code secret.key}, created on the first
 * write so a device-only deployment never gets the file.
 */
public final class SecretKeySource {

    public static final String SOURCE_PASSPHRASE = "HOME_CONTROL_SECRET";
    public static final String SOURCE_KEY_FILE = "secret.key";
    static final int KEY_BYTES = 32;
    static final int SALT_BYTES = 16;
    static final int KDF_MEMORY_KIB = 19456;
    static final int KDF_ITERATIONS = 2;
    static final int KDF_PARALLELISM = 1;

    /** What {@code secrets.json} records about its key. Salt and costs are set for the passphrase source only. */
    public record KeyHeader(String source, byte[] salt, int memoryKiB, int iterations, int parallelism) {
    }

    record Keyed(KeyHeader header, SecretKey key) {
    }

    private final String passphrase;
    private final Path keyFile;
    private final SecureRandom random;
    private KeyHeader derivedFor;
    private SecretKey derivedKey;

    public SecretKeySource(String passphrase, Path keyFile, SecureRandom random) {
        this.passphrase = passphrase;
        this.keyFile = keyFile;
        this.random = random;
    }

    public boolean usesPassphrase() {
        return passphrase != null && !passphrase.isBlank();
    }

    synchronized SecretKey keyFor(KeyHeader header) {
        return switch (header.source()) {
            case SOURCE_PASSPHRASE -> {
                if (!usesPassphrase()) {
                    throw new StorageException("secrets.json is encrypted with HOME_CONTROL_SECRET, which is not set;"
                            + " set it to the value it had when the secrets were saved", null);
                }
                yield derive(header);
            }
            case SOURCE_KEY_FILE -> {
                if (!Files.exists(keyFile)) {
                    throw new StorageException("secrets.json is encrypted with " + keyFile + ", which is missing;"
                            + " restore that file, or delete secrets.json and reconnect your content sources", null);
                }
                yield readKeyFile();
            }
            default -> throw new StorageException("secrets.json names an unknown key source", null);
        };
    }

    synchronized Keyed forWriting(KeyHeader current) {
        if (usesPassphrase()) {
            KeyHeader header = current != null && SOURCE_PASSPHRASE.equals(current.source())
                    ? current : newPassphraseHeader();
            return new Keyed(header, derive(header));
        }
        SecretKey key = Files.exists(keyFile) ? readKeyFile() : createKeyFile();
        return new Keyed(new KeyHeader(SOURCE_KEY_FILE, null, 0, 0, 0), key);
    }

    private KeyHeader newPassphraseHeader() {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        return new KeyHeader(SOURCE_PASSPHRASE, salt, KDF_MEMORY_KIB, KDF_ITERATIONS, KDF_PARALLELISM);
    }

    private SecretKey derive(KeyHeader header) {
        if (derivedFor != null && Arrays.equals(derivedFor.salt(), header.salt())
                && derivedFor.memoryKiB() == header.memoryKiB() && derivedFor.iterations() == header.iterations()
                && derivedFor.parallelism() == header.parallelism()) {
            return derivedKey;
        }
        byte[] secret = passphrase.getBytes(StandardCharsets.UTF_8);
        byte[] key = Argon2id.derive(secret, header.salt(),
                header.memoryKiB(), header.iterations(), header.parallelism(), KEY_BYTES);
        Arrays.fill(secret, (byte) 0);
        derivedFor = header;
        derivedKey = new SecretKeySpec(key, "AES");
        Arrays.fill(key, (byte) 0);
        return derivedKey;
    }

    private SecretKey readKeyFile() {
        byte[] key = null;
        try {
            key = Base64.getDecoder().decode(Files.readString(keyFile, StandardCharsets.US_ASCII).strip());
        } catch (IOException | IllegalArgumentException _) {
            // no cause: a decoding error could quote the file's content
            throw new StorageException("Could not read " + keyFile + "; it must hold " + KEY_BYTES
                    + " base64-encoded bytes", null);
        }
        try {
            if (key.length != KEY_BYTES) {
                throw new StorageException(keyFile + " must hold " + KEY_BYTES + " base64-encoded bytes", null);
            }
            return new SecretKeySpec(key, "AES");
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    private SecretKey createKeyFile() {
        byte[] key = new byte[KEY_BYTES];
        random.nextBytes(key);
        try {
            OwnerOnlyFiles.write(keyFile, (Base64.getEncoder().encodeToString(key) + "\n").getBytes(StandardCharsets.US_ASCII),
                    ".secret-key-", false);
            return new SecretKeySpec(key, "AES");
        } catch (IOException e) {
            throw new StorageException("Could not create " + keyFile + "; check that /data is bind-mounted and writable", e);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }
}
