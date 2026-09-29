package dev.andre.homecontrol.config;

import dev.andre.homecontrol.HomeControlApplication;
import dev.andre.homecontrol.adapters.androidtv.AndroidTvProperties;
import dev.andre.homecontrol.adapters.webos.WebOsProperties;
import dev.andre.homecontrol.storage.DataDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** An install configured entirely with the old names still starts, and every value reaches its new name. */
class LegacyConfigurationStartupTest {

    @Test
    void anInstallWithOnlyOldNamesStarts(@TempDir Path dataDir) {
        try (ConfigurableApplicationContext app = new SpringApplicationBuilder(HomeControlApplication.class)
                .run("--server.port=0", "--shield.data-dir=" + dataDir, "--shield.keystore-password=old-secret",
                        "--shield.discovery-enabled=false", "--home-control.webos.connect-timeout-seconds=7")) {
            assertThat(app.getBean(DataDirectory.class).path()).isEqualTo(dataDir);
            assertThat(app.getBean(AndroidTvProperties.class).keystorePassword()).isEqualTo("old-secret");
            assertThat(app.getBean(HomeControlProperties.class).discovery().enabled()).isFalse();
            assertThat(app.getBean(WebOsProperties.class).connectTimeout()).isEqualTo(Duration.ofSeconds(7));
        }
    }
}
