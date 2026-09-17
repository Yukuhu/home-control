package dev.andre.homecontrol.sources.tmdb;

import dev.andre.homecontrol.core.content.ContentSources;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

/** The default ({@code home-control.tmdb.enabled} unset, so {@code true}): the module is wired up. */
@SpringBootTest
class TmdbModuleEnabledTest {

    @DynamicPropertySource
    static void isolatedDataDirectory(DynamicPropertyRegistry registry) throws IOException {
        String dataDir = Files.createTempDirectory("tmdb-module-enabled-test").toString();
        registry.add("shield.data-dir", () -> dataDir);
    }

    @Autowired
    ApplicationContext context;

    @Autowired
    ContentSources sources;

    @Test
    void theModuleIsWiredUpByDefault() {
        assertThat(context.getBeanNamesForType(TmdbContentSource.class)).isNotEmpty();
        assertThat(context.getBeanNamesForType(TmdbSetupController.class)).isNotEmpty();
        assertThat(context.getBeanNamesForType(TmdbSetupAdvice.class)).isNotEmpty();
        assertThat(sources.find("tmdb")).isPresent();
    }
}
