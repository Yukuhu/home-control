package dev.andre.homecontrol.adapters.androidtv;

import dev.andre.homecontrol.HomeControlApplication;
import dev.andre.homecontrol.storage.SecretStore;
import dev.andre.homecontrol.testsupport.TestCredentials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** The keystore password across an upgrade: nothing is generated before it is needed, and an old default goes. */
class KeystoreUpgradeStartupTest {

    private static ConfigurableApplicationContext start(Path dataDir) {
        return new SpringApplicationBuilder(HomeControlApplication.class)
                .run("--server.port=0", "--home-control.data-dir=" + dataDir, "--home-control.discovery.enabled=false");
    }

    @Test
    void aFreshInstallStartsWithoutAnySecretOrLogin(@TempDir Path dataDir) {
        try (ConfigurableApplicationContext app = start(dataDir)) {
            assertThat(app.getBean(SecretStore.class).names()).isEmpty();
            assertThat(app.getBean(SecretStore.class).login()).isEmpty();
            assertThat(dataDir.resolve("secrets.json")).doesNotExist();
            assertThat(dataDir.resolve("keystore.p12")).doesNotExist();
        }
    }

    @Test
    void anInstallThatUsedTheOldDefaultStartsWithItsKeystoreReprotected(@TempDir Path dataDir) {
        Path keystore = dataDir.resolve("keystore.p12");
        new CertificateStore(keystore, "shield".toCharArray()).save("shield-1", TestCredentials.clientCertificate());

        try (ConfigurableApplicationContext app = start(dataDir)) {
            String password = app.getBean(SecretStore.class).deviceSecret(KeystorePassword.SECRET).orElseThrow();

            assertThat(CertificateStore.opens(keystore, "shield".toCharArray())).isFalse();
            assertThat(new CertificateStore(keystore, password.toCharArray()).load("shield-1")).isPresent();
            assertThat(app.getBean(SecretStore.class).login()).isEmpty();
        }
    }
}
