package dev.andre.shield.protocol;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Optional;

/** PKCS12-backed storage for pairing credentials, one entry per device alias. */
public class CertificateStore {

    private final Path file;
    private final char[] password;

    public CertificateStore(Path file, char[] password) {
        this.file = file;
        this.password = password;
    }

    public synchronized Optional<ClientCertificate> load(String alias) {
        try {
            KeyStore keyStore = openOrEmpty();
            if (!keyStore.containsAlias(alias)) {
                return Optional.empty();
            }
            PrivateKey privateKey = (PrivateKey) keyStore.getKey(alias, password);
            Certificate certificate = keyStore.getCertificate(alias);
            KeyPair keyPair = new KeyPair(certificate.getPublicKey(), privateKey);
            return Optional.of(new ClientCertificate(keyPair, (X509Certificate) certificate));
        } catch (Exception e) {
            throw new IllegalStateException("Could not read keystore " + file, e);
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
            keyStore.setKeyEntry(alias, credential.keyPair().getPrivate(), password,
                    new Certificate[]{credential.certificate()});
            Files.createDirectories(file.toAbsolutePath().getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                keyStore.store(out, password);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not write keystore " + file, e);
        }
    }

    private KeyStore openOrEmpty() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                keyStore.load(in, password);
            }
        } else {
            keyStore.load(null, password);
        }
        return keyStore;
    }
}
