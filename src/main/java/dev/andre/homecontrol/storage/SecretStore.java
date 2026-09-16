package dev.andre.homecontrol.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * API keys, tokens and the login hash, encrypted with AES-256-GCM in {@code secrets.json} (spec §8, §9).
 * Invariant: secrets exist if and only if a login exists — secrets are never stored unprotected, and
 * removing the last one returns the deployment to device-only. No message or log line carries a
 * secret, the hash or the key.
 */
public class SecretStore {

    private static final Logger log = LoggerFactory.getLogger(SecretStore.class);

    static final String FORMAT = "home-control-secrets";
    static final int VERSION = 1;
    static final String CIPHER = "AES-256-GCM";
    private static final byte[] AAD = "home-control/secrets/v1".getBytes(StandardCharsets.US_ASCII);
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9.-]{0,63}");
    private static final int MAX_VALUE_CHARS = 16_384;
    private static final int MAX_KDF_MEMORY_KIB = 262_144;

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Path file;
    private final SecretKeySource keys;
    private final SecureRandom random;
    private SecretKeySource.KeyHeader header;
    private LoginCredential login;
    private Map<String, String> secrets = Map.of();

    public SecretStore(Path file, SecretKeySource keys, SecureRandom random) {
        this.file = file;
        this.keys = keys;
        this.random = random;
        load();
        if (header != null && keys.usesPassphrase() && SecretKeySource.SOURCE_KEY_FILE.equals(header.source())) {
            write(login, secrets);
            log.info("Re-encrypted {} with HOME_CONTROL_SECRET; secret.key is no longer needed", file);
        }
    }

    public synchronized Optional<String> secret(String name) {
        return Optional.ofNullable(secrets.get(name));
    }

    public synchronized boolean hasSecrets() {
        return !secrets.isEmpty();
    }

    public synchronized Set<String> names() {
        return Set.copyOf(secrets.keySet());
    }

    public synchronized Optional<LoginCredential> login() {
        return Optional.ofNullable(login);
    }

    /** Adds or replaces secrets. A login must already exist. */
    public synchronized void putSecrets(Map<String, String> values) {
        validate(values);
        if (login == null) {
            throw new IllegalStateException("Set a login password before storing secrets");
        }
        Map<String, String> next = new HashMap<>(secrets);
        next.putAll(values);
        write(login, next);
    }

    /** Stores the first secrets and the login that protects them in one atomic write. */
    public synchronized void putFirstSecrets(Map<String, String> values, LoginCredential newLogin) {
        validate(values);
        if (newLogin == null) {
            throw new IllegalArgumentException("A login is required");
        }
        if (!secrets.isEmpty() || login != null) {
            throw new IllegalStateException("Secrets already exist; log in to add more");
        }
        write(newLogin, values);
    }

    /** Removing the last secret also removes the login. */
    public synchronized void removeSecrets(Collection<String> names) {
        Map<String, String> next = new HashMap<>(secrets);
        names.forEach(next::remove);
        if (next.size() == secrets.size()) {
            return;
        }
        write(next.isEmpty() ? null : login, next);
    }

    public synchronized void replaceLogin(LoginCredential newLogin) {
        if (login == null || newLogin == null) {
            throw new IllegalStateException("There is no login to replace");
        }
        write(newLogin, secrets);
    }

    private static void validate(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("No secrets given");
        }
        values.forEach((name, value) -> {
            if (name == null || !NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("Invalid secret name");
            }
            if (value == null || value.isEmpty() || value.length() > MAX_VALUE_CHARS) {
                throw new IllegalArgumentException("Invalid value for secret " + name);
            }
        });
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        SecretKeySource.KeyHeader parsed;
        byte[] nonce;
        byte[] ciphertext;
        try {
            JsonNode root = mapper.readTree(Files.readAllBytes(file));
            if (root == null || !FORMAT.equals(root.path("format").asString(""))
                    || root.path("version").asInt(0) != VERSION || !CIPHER.equals(root.path("cipher").asString(""))) {
                throw new StorageException(file + " is not a version " + VERSION + " Home Control secrets file", null);
            }
            parsed = parseHeader(root.path("key"));
            nonce = Base64.getDecoder().decode(root.path("nonce").asString(""));
            ciphertext = Base64.getDecoder().decode(root.path("ciphertext").asString(""));
        } catch (IOException | JacksonException | IllegalArgumentException e) {
            throw new StorageException("Could not read " + file + "; it is not a readable Home Control secrets file", e);
        }
        if (nonce.length != NONCE_BYTES) {
            throw new StorageException(file + " has an invalid nonce", null);
        }
        byte[] plaintext = decrypt(keys.keyFor(parsed), nonce, ciphertext, parsed);
        try {
            JsonNode document = mapper.readTree(plaintext);
            JsonNode loginNode = document.path("login");
            LoginCredential loaded = loginNode.isObject()
                    ? new LoginCredential(loginNode.path("passwordHash").asString(""), loginNode.path("version").asString(""))
                    : null;
            Map<String, String> values = new LinkedHashMap<>();
            document.path("secrets").properties().forEach(entry -> values.put(entry.getKey(), entry.getValue().asString("")));
            header = parsed;
            login = loaded;
            secrets = Map.copyOf(values);
        } catch (JacksonException e) {
            // no cause: a parser message could quote the decrypted content
            throw new StorageException(file + " was decrypted but its content is not valid", null);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    private SecretKeySource.KeyHeader parseHeader(JsonNode key) {
        String source = key.path("source").asString("");
        if (!SecretKeySource.SOURCE_PASSPHRASE.equals(source)) {
            return new SecretKeySource.KeyHeader(source, null, 0, 0, 0);
        }
        int memory = key.path("memoryKiB").asInt(0);
        int iterations = key.path("iterations").asInt(0);
        int parallelism = key.path("parallelism").asInt(0);
        byte[] salt = Base64.getDecoder().decode(key.path("salt").asString(""));
        if (!"argon2id".equals(key.path("kdf").asString("")) || memory < 8 * Math.max(1, parallelism)
                || memory > MAX_KDF_MEMORY_KIB || iterations < 1 || iterations > 10
                || parallelism < 1 || parallelism > 8 || salt.length < 16) {
            throw new StorageException(file + " has invalid key derivation parameters", null);
        }
        return new SecretKeySource.KeyHeader(source, salt, memory, iterations, parallelism);
    }

    private byte[] decrypt(SecretKey key, byte[] nonce, byte[] ciphertext, SecretKeySource.KeyHeader parsed) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(AAD);
            return cipher.doFinal(ciphertext);
        } catch (AEADBadTagException e) {
            throw new StorageException(SecretKeySource.SOURCE_PASSPHRASE.equals(parsed.source())
                    ? file + " could not be decrypted: HOME_CONTROL_SECRET is not the value the secrets were saved with"
                    : file + " could not be decrypted with secret.key; the key file belongs to a different secrets.json", e);
        } catch (GeneralSecurityException e) {
            throw new StorageException("Could not decrypt " + file, e);
        }
    }

    private void write(LoginCredential nextLogin, Map<String, String> nextSecrets) {
        ObjectNode document = mapper.createObjectNode();
        if (nextLogin == null) {
            document.putNull("login");
        } else {
            ObjectNode loginNode = document.putObject("login");
            loginNode.put("passwordHash", nextLogin.passwordHash());
            loginNode.put("version", nextLogin.version());
        }
        ObjectNode secretsNode = document.putObject("secrets");
        new TreeMap<>(nextSecrets).forEach(secretsNode::put);
        byte[] plaintext = mapper.writeValueAsBytes(document);

        SecretKeySource.Keyed keyed;
        byte[] nonce = new byte[NONCE_BYTES];
        byte[] ciphertext;
        try {
            keyed = keys.forWriting(header);
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keyed.key(), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(AAD);
            ciphertext = cipher.doFinal(plaintext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM is not available in this JVM", e);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }

        ObjectNode root = mapper.createObjectNode();
        root.put("format", FORMAT);
        root.put("version", VERSION);
        ObjectNode key = root.putObject("key");
        SecretKeySource.KeyHeader h = keyed.header();
        key.put("source", h.source());
        if (SecretKeySource.SOURCE_PASSPHRASE.equals(h.source())) {
            key.put("kdf", "argon2id");
            key.put("memoryKiB", h.memoryKiB());
            key.put("iterations", h.iterations());
            key.put("parallelism", h.parallelism());
            key.put("salt", Base64.getEncoder().encodeToString(h.salt()));
        }
        root.put("cipher", CIPHER);
        root.put("nonce", Base64.getEncoder().encodeToString(nonce));
        root.put("ciphertext", Base64.getEncoder().encodeToString(ciphertext));

        try {
            OwnerOnlyFiles.write(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root), ".secrets-", true);
        } catch (IOException e) {
            throw new StorageException("Could not write " + file + "; check that /data is bind-mounted and writable", e);
        }
        header = h;
        login = nextLogin;
        secrets = Map.copyOf(nextSecrets);
    }
}
