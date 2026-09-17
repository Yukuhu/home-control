package dev.andre.homecontrol.sources.jellyfin;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code home-control.jellyfin.enabled=false}: an Android TV/Cast-only box has no Jellyfin code wired up. */
@SpringBootTest(properties = "home-control.jellyfin.enabled=false")
@AutoConfigureMockMvc
class JellyfinModuleSwitchTest {

    @DynamicPropertySource
    static void isolatedDataDirectory(DynamicPropertyRegistry registry) throws IOException {
        String dataDir = Files.createTempDirectory("jellyfin-module-switch-test").toString();
        registry.add("shield.data-dir", () -> dataDir);
    }

    @Autowired
    ApplicationContext context;

    @Autowired
    MockMvc mockMvc;

    @Test
    void theModuleCanBeSwitchedOff() throws Exception {
        assertThat(context.getBeanNamesForType(JellyfinClient.class)).isEmpty();
        assertThat(context.getBeanNamesForType(JellyfinSetupService.class)).isEmpty();
        assertThat(context.getBeanNamesForType(JellyfinSetupController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(JellyfinSetupAdvice.class)).isEmpty();

        mockMvc.perform(get("/setup"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Jellyfin"))));

        mockMvc.perform(post("/setup/sources/jellyfin"))
                .andExpect(status().isNotFound());
    }
}
