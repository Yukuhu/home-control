package dev.andre.homecontrol.config;

import dev.andre.homecontrol.HomeControlApplication;
import dev.andre.homecontrol.adapters.androidtv.AndroidTvProperties;
import dev.andre.homecontrol.adapters.webos.WebOsProperties;
import dev.andre.homecontrol.storage.DataDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** An install configured entirely with the old names still starts, and every value reaches its new name. */
class LegacyConfigurationStartupTest {

    @Test
    void anInstallWithOnlyOldNamesStarts(@TempDir Path dataDir) {
        try (ConfigurableApplicationContext app = new SpringApplicationBuilder(HomeControlApplication.class)
                .run("--server.port=0", "--shield.data-dir=" + dataDir, "--shield.keystore-password=old-secret",
                        "--shield.discovery-enabled=false", "--home-control.webos.connect-timeout-seconds=7")) {
            assertThat(app.getBean(DataDirectory.class).path()).isEqualTo(dataDir);
            assertThat(app.getBean(AndroidTvProperties.class).keystorePassword()).isEqualTo("old-secret");
            assertThat(app.getBean(WebOsProperties.class).connectTimeout()).isEqualTo(Duration.ofSeconds(7));
        }
    }

    /** Old keys in a YAML file, as an install's own configuration file has them, reach their new names too. */
    @Test
    void oldKeysInAYamlFileReachTheirNewNames(@TempDir Path dir) throws IOException {
        Path yaml = Files.writeString(dir.resolve("old.yaml"), """
                shield:
                  keystore-password: from-yaml
                home-control:
                  webos:
                    connect-timeout-seconds: 8
                """);
        try (ConfigurableApplicationContext app = new SpringApplicationBuilder(HomeControlApplication.class)
                .run("--server.port=0", "--home-control.data-dir=" + dir.resolve("data"),
                        "--spring.config.additional-location=file:" + yaml)) {
            assertThat(app.getBean(AndroidTvProperties.class).keystorePassword()).isEqualTo("from-yaml");
            assertThat(app.getBean(WebOsProperties.class).connectTimeout()).isEqualTo(Duration.ofSeconds(8));
        }
    }

    /** A bad value under an old key fails startup naming the file and line where it was set. */
    @Test
    void aBadOldValueIsReportedWhereItWasSet(@TempDir Path dir) throws IOException {
        Path yaml = Files.writeString(dir.resolve("old.yaml"), """
                home-control:
                  webos:
                    connect-timeout-seconds: abc
                """);
        SpringApplicationBuilder app = new SpringApplicationBuilder(HomeControlApplication.class);
        String[] arguments = {"--server.port=0", "--home-control.data-dir=" + dir.resolve("data"),
                "--spring.config.additional-location=file:" + yaml};

        assertThatThrownBy(() -> app.run(arguments)).satisfies(failure -> {
            BindException bind = causeOfType(failure, BindException.class);
            assertThat(bind.getProperty().getName()).hasToString("home-control.webos.connect-timeout");
            assertThat(String.valueOf(bind.getProperty().getOrigin())).contains("old.yaml").contains("3:");
        });
    }

    private static <T extends Throwable> T causeOfType(Throwable failure, Class<T> type) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return type.cast(cause);
            }
        }
        throw new AssertionError("No " + type.getSimpleName() + " in " + failure);
    }
}
