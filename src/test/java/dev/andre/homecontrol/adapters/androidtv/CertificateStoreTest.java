package dev.andre.homecontrol.adapters.androidtv;

import dev.andre.homecontrol.adapters.androidtv.protocol.ClientCertificate;
import dev.andre.homecontrol.storage.StorageException;
import dev.andre.homecontrol.testsupport.TestCredentials;
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
    void persistsAndReloadsTheSameKeyPair() {
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

        CertificateStore wrongPassword = new CertificateStore(file, "wrong".toCharArray());
        assertThatThrownBy(() -> wrongPassword.load("shield"))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining(file.toString())
                .hasMessageContaining("password");
    }

    @Test
    void aKeystoreThatCannotBeWrittenIsAStorageFailure() throws Exception {
        Path notADirectory = Files.writeString(dir.resolve("not-a-directory"), "a file");
        CertificateStore store = new CertificateStore(notADirectory.resolve("keystore.p12"), "secret".toCharArray());
        ClientCertificate credential = TestCredentials.clientCertificate();

        assertThatThrownBy(() -> store.save("living", credential))
                .isInstanceOf(StorageException.class)
                .hasMessageStartingWith("Could not write keystore")
                .hasMessageContaining("check file permissions");
    }

    @Test
    void deletingWithAWrongPasswordIsAStorageFailure() {
        Path file = dir.resolve("keystore.p12");
        new CertificateStore(file, "correct".toCharArray()).loadOrCreate("shield");

        CertificateStore wrongPassword = new CertificateStore(file, "wrong".toCharArray());
        assertThatThrownBy(() -> wrongPassword.delete("shield"))
                .isInstanceOf(StorageException.class)
                .hasMessageStartingWith("Could not delete credential shield from keystore " + file);
    }

    @Test
    void aKeystoreOpensOnlyWithItsPassword() {
        Path file = dir.resolve("keystore.p12");
        new CertificateStore(file, "correct".toCharArray()).loadOrCreate("shield");

        assertThat(CertificateStore.opens(file, "correct".toCharArray())).isTrue();
        assertThat(CertificateStore.opens(file, "wrong".toCharArray())).isFalse();
        assertThat(CertificateStore.opens(dir.resolve("missing.p12"), "correct".toCharArray())).isFalse();
    }

    @Test
    void reprotectingMovesTheKeystoreAndItsKeysToTheNewPassword() {
        Path file = dir.resolve("keystore.p12");
        ClientCertificate created = new CertificateStore(file, "old".toCharArray()).loadOrCreate("shield");

        assertThat(CertificateStore.reprotect(file, "wrong".toCharArray(), "new".toCharArray())).isFalse();
        assertThat(CertificateStore.reprotect(file, "old".toCharArray(), "new".toCharArray())).isTrue();

        assertThat(CertificateStore.opens(file, "old".toCharArray())).isFalse();
        assertThat(new CertificateStore(file, "new".toCharArray()).load("shield"))
                .hasValueSatisfying(loaded -> assertThat(loaded.certificate()).isEqualTo(created.certificate()));
    }

    @Test
    void verifyReadableAcceptsAMissingKeystoreWithoutCreatingIt() {
        Path file = dir.resolve("keystore.p12");

        new CertificateStore(file, "secret".toCharArray()).verifyReadable();

        assertThat(file).doesNotExist();
    }

    @Test
    void theKeystoreIsWrittenOwnerOnly() throws Exception {
        org.assertj.core.api.Assumptions.assumeThat(dir.getFileSystem().supportedFileAttributeViews()).contains("posix");
        Path file = dir.resolve("keystore.p12");

        new CertificateStore(file, "secret".toCharArray()).save("living", TestCredentials.clientCertificate());

        assertThat(java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
                .isEqualTo("rw-------");
    }

    /** Older versions wrote the keystore readable by everyone; it is made owner-only when it is first read. */
    @Test
    void aKeystoreOthersCanReadIsMadeOwnerOnlyWhenChecked() throws Exception {
        org.assertj.core.api.Assumptions.assumeThat(dir.getFileSystem().supportedFileAttributeViews()).contains("posix");
        Path file = dir.resolve("keystore.p12");
        new CertificateStore(file, "secret".toCharArray()).save("living", TestCredentials.clientCertificate());
        Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));

        new CertificateStore(file, "secret".toCharArray()).verifyReadable();

        assertThat(java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
                .isEqualTo("rw-------");
    }
}
