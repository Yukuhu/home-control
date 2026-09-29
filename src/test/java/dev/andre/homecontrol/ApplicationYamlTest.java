package dev.andre.homecontrol;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests load the production application.yaml, with only src/test/resources/config/application.yaml on top. */
class ApplicationYamlTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    void theProductionFileIsBound() {
        runner.run(context -> {
            Environment environment = context.getEnvironment();
            // Set only in src/main/resources/application.yaml.
            assertThat(environment.getProperty("home-control.webos.port")).isEqualTo("3000");
            assertThat(environment.getProperty("home-control.deep-link-test.youtube-url"))
                    .isEqualTo("https://www.youtube.com/watch?v=aqz-KE-bpKQ");
        });
    }

    @Test
    void bluetoothStaysOffInTheProductionFile() throws IOException {
        // Reads the production file directly, unmerged: an override added to the test file must not make
        // this pass vacuously, and exporting HOME_CONTROL_BLUETOOTH_ENABLED must not make it fail.
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application.yaml", new ClassPathResource("application.yaml"));
        assertThat(sources).hasSize(1);
        assertThat(sources.getFirst().getProperty("home-control.bluetooth.enabled")).isEqualTo(false);
    }

    @Test
    void theTestOverridesWin() {
        runner.run(context -> {
            Environment environment = context.getEnvironment();
            // Gradle sets org.gradle.test.worker in every test JVM; outside Gradle the configuration falls back to 0.
            String fork = System.getProperty("org.gradle.test.worker", "0");
            assertThat(environment.getProperty("home-control.data-dir")).isEqualTo("build/test-data/" + fork);
            assertThat(environment.getProperty("home-control.bluetooth.runtime-dir"))
                    .isEqualTo(System.getProperty("java.io.tmpdir") + "/home-control-bluetooth-" + fork);
            assertThat(environment.getProperty("home-control.discovery.enabled")).isEqualTo("false");
            assertThat(environment.getProperty("home-control.ssdp.enabled")).isEqualTo("false");
            assertThat(environment.getProperty("home-control.content.rails.scheduler-enabled")).isEqualTo("false");
            assertThat(environment.getProperty("home-control.tmdb.api-base-url")).isEqualTo("http://127.0.0.1:9/3");
            assertThat(environment.getProperty("home-control.security.secret")).isEmpty();
        });
    }

    @Test
    void theOldKeystorePasswordNameStillReachesTheKeystoreFromAnySource() {
        runner.withSystemProperties("SHIELD_KEYSTORE_PASSWORD=from-a-system-property")
                .run(context -> assertThat(context.getEnvironment()
                        .getProperty("home-control.androidtv.keystore-password")).isEqualTo("from-a-system-property"));
    }
}
