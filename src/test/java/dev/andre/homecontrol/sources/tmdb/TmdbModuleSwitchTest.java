package dev.andre.homecontrol.sources.tmdb;

import dev.andre.homecontrol.core.content.ContentSources;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code home-control.tmdb.enabled=false}: no TMDB code is wired up. */
@SpringBootTest(properties = "home-control.tmdb.enabled=false")
@AutoConfigureMockMvc
class TmdbModuleSwitchTest {

    @DynamicPropertySource
    static void isolatedDataDirectory(DynamicPropertyRegistry registry) throws IOException {
        String dataDir = Files.createTempDirectory("tmdb-module-switch-test").toString();
        registry.add("shield.data-dir", () -> dataDir);
    }

    @Autowired
    ApplicationContext context;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ContentSources sources;

    @Test
    void theModuleCanBeSwitchedOff() throws Exception {
        assertThat(context.getBeanNamesForType(TmdbContentSource.class)).isEmpty();
        assertThat(context.getBeanNamesForType(TmdbSetupController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(TmdbSetupAdvice.class)).isEmpty();
        assertThat(sources.find("tmdb")).isEmpty();

        mockMvc.perform(get("/setup"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("id=\"tmdb\""))));
    }
}
