package dev.andre.homecontrol.adapters.androidtv;

import dev.andre.homecontrol.adapters.androidtv.protocol.ClientCertificate;
import dev.andre.homecontrol.storage.AtomicFiles;
import dev.andre.homecontrol.storage.StorageException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * PKCS12-backed storage for pairing credentials, one entry per device alias. The password is asked for once, the
 * first time the keystore is opened or written, so an install that never pairs an Android TV needs none.
 */
public class CertificateStore {

    private static final String KEYSTORE_TYPE = "PKCS12";

    private final Path file;
    private final Supplier<char[]> passwordSource;
    private char[] password;

    public CertificateStore(Path file, char[] password) {
        this(file, () -> password);
    }

    public CertificateStore(Path file, Supplier<char[]> password) {
        this.file = file;
        this.passwordSource = password;
    }

    /** Callers hold this store's monitor. */
    private char[] password() {
        if (password == null) {
            password = passwordSource.get();
        }
        return password;
    }

    public synchronized void verifyReadable() {
        if (Files.notExists(file)) {
            return;
        }
        password(); // a password that cannot be resolved fails with its own message
        try {
            openOrEmpty();
        } catch (Exception e) {
            throw new StorageException(
                    "Could not read keystore " + file
                            + "; check the keystore password and file permissions",
                    e);
        }
    }

    public synchronized Optional<ClientCertificate> load(String alias) {
        try {
            KeyStore keyStore = openOrEmpty();
            if (!keyStore.containsAlias(alias)) {
                return Optional.empty();
            }
            PrivateKey privateKey = (PrivateKey) keyStore.getKey(alias, password());
            Certificate certificate = keyStore.getCertificate(alias);
            KeyPair keyPair = new KeyPair(certificate.getPublicKey(), privateKey);
            return Optional.of(new ClientCertificate(keyPair, (X509Certificate) certificate));
        } catch (Exception e) {
            throw new StorageException(
                    "Could not read keystore " + file
                            + "; check the keystore password and file permissions",
                    e);
        }
    }

    public synchronized ClientCertificate loadOrCreate(String alias) {
        return load(alias).orElseGet(() -> {
            ClientCertificate created = ClientCertificate.generate("shield-remote");
            save(alias, created);
            return created;
        });
    }

    public synchronized void save(String alias, ClientCertificate credential) {
        try {
            KeyStore keyStore = openOrEmpty();
            keyStore.setKeyEntry(alias, credential.keyPair().getPrivate(), password(),
                    new Certificate[]{credential.certificate()});
            write(keyStore);
        } catch (Exception e) {
            throw new StorageException(
                    "Could not write keystore " + file + "; check file permissions",
                    e);
        }
    }

    public synchronized void delete(String alias) {
        try {
            KeyStore keyStore = openOrEmpty();
            if (!keyStore.containsAlias(alias)) {
                return;
            }
            keyStore.deleteEntry(alias);
            write(keyStore);
        } catch (Exception e) {
            throw new StorageException(
                    "Could not delete credential " + alias + " from keystore " + file
                            + "; check the keystore password and file permissions",
                    e);
        }
    }

    /** Through {@link AtomicFiles}: the keystore holds the client keys, so it is owner-only and never half written. */
    private void write(KeyStore keyStore) throws GeneralSecurityException, IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        keyStore.store(out, password());
        AtomicFiles.write(file, out.toByteArray(), true);
    }

    /** True when the keystore file opens with {@code password}. */
    static boolean opens(Path file, char[] password) {
        try (InputStream in = Files.newInputStream(file)) {
            KeyStore.getInstance(KEYSTORE_TYPE).load(in, password);
            return true;
        } catch (IOException | GeneralSecurityException _) {
            return false;
        }
    }

    /**
     * Re-saves the keystore, and each of its keys, under {@code next} through {@link AtomicFiles}; false when it does
     * not open with {@code current}.
     */
    static boolean reprotect(Path file, char[] current, char[] next) {
        KeyStore keyStore;
        try (InputStream in = Files.newInputStream(file)) {
            keyStore = KeyStore.getInstance(KEYSTORE_TYPE);
            keyStore.load(in, current);
        } catch (IOException | GeneralSecurityException _) {
            return false;
        }
        try {
            for (String alias : Collections.list(keyStore.aliases())) {
                if (keyStore.isKeyEntry(alias)) {
                    Key key = keyStore.getKey(alias, current);
                    keyStore.setKeyEntry(alias, key, next, keyStore.getCertificateChain(alias));
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            keyStore.store(out, next);
            AtomicFiles.write(file, out.toByteArray(), true);
            return true;
        } catch (IOException | GeneralSecurityException e) {
            throw new StorageException("Could not re-protect keystore " + file + "; check file permissions", e);
        }
    }

    private KeyStore openOrEmpty() throws GeneralSecurityException, IOException {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE_TYPE);
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                keyStore.load(in, password());
            }
        } else {
            keyStore.load(null, null);
        }
        return keyStore;
    }
}
