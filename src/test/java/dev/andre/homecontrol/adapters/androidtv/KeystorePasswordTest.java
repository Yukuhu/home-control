package dev.andre.homecontrol.adapters.androidtv;

import dev.andre.homecontrol.storage.StorageException;
import dev.andre.homecontrol.testsupport.InMemoryDeviceSecrets;
import dev.andre.homecontrol.testsupport.TestCredentials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeystorePasswordTest {

    @TempDir
    Path dir;

    private final InMemoryDeviceSecrets secrets = new InMemoryDeviceSecrets();

    private Path keystore() {
        return dir.resolve("keystore.p12");
    }

    private void pairUnder(String password) {
        new CertificateStore(keystore(), password.toCharArray()).save("shield-1", TestCredentials.clientCertificate());
    }

    private char[] resolve(String configured) {
        return KeystorePassword.resolve(keystore(), configured, secrets, new SecureRandom());
    }

    @Test
    void aConfiguredPasswordIsUsedAsItIs() {
        pairUnder("mine");

        assertThat(resolve("mine")).isEqualTo("mine".toCharArray());
        assertThat(secrets.all()).isEmpty();
    }

    @Test
    void aFreshInstallGeneratesAndStoresAPassword() {
        char[] password = resolve("");

        assertThat(new String(password)).hasSizeGreaterThanOrEqualTo(40);
        assertThat(secrets.deviceSecret(KeystorePassword.SECRET)).contains(new String(password));
        assertThat(resolve(null)).isEqualTo(password);
    }

    @ParameterizedTest
    @ValueSource(strings = {"shield", "change-me"})
    void aKeystoreUnderAnOldDefaultIsReprotectedAndItsPairingStillLoads(String old) {
        pairUnder(old);

        char[] password = resolve("");

        assertThat(CertificateStore.opens(keystore(), old.toCharArray())).isFalse();
        assertThat(new CertificateStore(keystore(), password).load("shield-1")).isPresent();
    }

    @Test
    void aStoredPasswordWhoseKeystoreIsStillUnderTheOldDefaultIsReprotected() {
        pairUnder("shield");
        secrets.putDeviceSecret(KeystorePassword.SECRET, "stored-before-a-crash");

        char[] password = resolve("");

        assertThat(password).isEqualTo("stored-before-a-crash".toCharArray());
        assertThat(new CertificateStore(keystore(), password).load("shield-1")).isPresent();
    }

    @Test
    void aKeystoreThatOpensWithNoneOfThemStopsStartupByName() {
        pairUnder("something else");

        assertThatThrownBy(() -> resolve(""))
                .isInstanceOf(StorageException.class)
                .hasMessage("keystore.p12 does not open with the stored password or an old default; set"
                        + " home-control.androidtv.keystore-password to the password it was created with, or delete"
                        + " keystore.p12 and pair the Android TV devices again");
    }

    @Test
    void theStoreAsksForThePasswordOnlyWhenItNeedsIt() {
        AtomicInteger asked = new AtomicInteger();
        CertificateStore store = new CertificateStore(keystore(), () -> {
            asked.incrementAndGet();
            return "pw".toCharArray();
        });

        store.verifyReadable();
        assertThat(store.load("shield-1")).isEmpty();
        assertThat(asked).hasValue(0);

        store.save("shield-1", TestCredentials.clientCertificate());
        store.load("shield-1");
        assertThat(asked).hasValue(1);
    }

    @Test
    void theStartupCheckReportsTheNamedErrorForAnUnopenableKeystore() {
        pairUnder("something else");
        CertificateStore store = new CertificateStore(keystore(), () -> resolve(""));

        assertThatThrownBy(store::verifyReadable)
                .isInstanceOf(StorageException.class)
                .hasMessageStartingWith("keystore.p12 does not open with the stored password or an old default");
    }
}
