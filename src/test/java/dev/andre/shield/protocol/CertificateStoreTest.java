package dev.andre.shield.protocol;

import dev.andre.shield.storage.StorageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.interfaces.RSAPublicKey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CertificateStoreTest {

    @TempDir
    Path dir;

    @Test
    void generatesA2048BitSelfSignedCertificate() {
        ClientCertificate cert = ClientCertificate.generate("shield-remote");

        assertThat(((RSAPublicKey) cert.certificate().getPublicKey()).getModulus().bitLength())
                .isEqualTo(2048);
        assertThat(cert.certificate().getSubjectX500Principal())
                .isEqualTo(cert.certificate().getIssuerX500Principal());
    }

    @Test
    void persistsAndReloadsTheSameKeyPair() throws Exception {
        Path file = dir.resolve("keystore.p12");
        CertificateStore store = new CertificateStore(file, "secret".toCharArray());

        ClientCertificate created = store.loadOrCreate("shield");
        assertThat(Files.exists(file)).isTrue();

        CertificateStore reopened = new CertificateStore(file, "secret".toCharArray());
        ClientCertificate reloaded = reopened.loadOrCreate("shield");

        assertThat(reloaded.certificate()).isEqualTo(created.certificate());
        assertThat(reloaded.keyPair().getPrivate()).isEqualTo(created.keyPair().getPrivate());
    }

    @Test
    void fingerprintsACertificateStably() {
        ClientCertificate cert = ClientCertificate.generate("shield-remote");
        ClientCertificate other = ClientCertificate.generate("shield-remote");

        assertThat(ClientCertificate.fingerprintOf(cert.certificate()))
                .hasSize(64)
                .matches("[0-9A-F]{64}")
                .isEqualTo(ClientCertificate.fingerprintOf(cert.certificate()))
                .isNotEqualTo(ClientCertificate.fingerprintOf(other.certificate()));
    }

    @Test
    void loadReturnsEmptyForAnUnknownAlias() {
        CertificateStore store = new CertificateStore(dir.resolve("keystore.p12"), "secret".toCharArray());
        assertThat(store.load("never-paired")).isEmpty();
    }

    @Test
    void deletesOnlyTheRequestedAlias() {
        Path file = dir.resolve("keystore.p12");
        CertificateStore store = new CertificateStore(file, "secret".toCharArray());
        store.loadOrCreate("living-room");
        ClientCertificate bedroom = store.loadOrCreate("bedroom");

        store.delete("living-room");

        CertificateStore reopened = new CertificateStore(file, "secret".toCharArray());
        assertThat(reopened.load("living-room")).isEmpty();
        assertThat(reopened.load("bedroom")).get()
                .extracting(ClientCertificate::certificate)
                .isEqualTo(bedroom.certificate());
    }

    @Test
    void aWrongPasswordIsAStorageFailureNotAMissingAlias() {
        Path file = dir.resolve("keystore.p12");
        new CertificateStore(file, "correct".toCharArray()).loadOrCreate("shield");

        assertThatThrownBy(() -> new CertificateStore(file, "wrong".toCharArray()).load("shield"))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining(file.toString())
                .hasMessageContaining("password");
    }

    @Test
    void verifyReadableAcceptsAMissingKeystoreWithoutCreatingIt() {
        Path file = dir.resolve("keystore.p12");

        new CertificateStore(file, "secret".toCharArray()).verifyReadable();

        assertThat(file).doesNotExist();
    }
}
