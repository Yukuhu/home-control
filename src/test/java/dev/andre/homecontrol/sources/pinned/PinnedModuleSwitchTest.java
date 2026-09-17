package dev.andre.homecontrol.sources.pinned;

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
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code home-control.pinned.enabled=false}: no pinned-shortcuts code is wired up. */
@SpringBootTest(properties = "home-control.pinned.enabled=false")
@AutoConfigureMockMvc
class PinnedModuleSwitchTest {

    static Path dataDir;

    @DynamicPropertySource
    static void isolatedDataDirectory(DynamicPropertyRegistry registry) throws IOException {
        dataDir = Files.createTempDirectory("pinned-module-switch-test");
        registry.add("shield.data-dir", () -> dataDir.toString());
    }

    @Autowired
    ApplicationContext context;

    @Autowired
    MockMvc mockMvc;

    @Test
    void theModuleCanBeSwitchedOff() throws Exception {
        assertThat(context.getBeanNamesForType(PinnedShortcuts.class)).isEmpty();
        assertThat(context.getBeanNamesForType(PinnedContentSource.class)).isEmpty();
        assertThat(context.getBeanNamesForType(PinnedSetupController.class)).isEmpty();

        mockMvc.perform(get("/setup"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("id=\"pinned\""))));

        assertThat(Files.exists(dataDir.resolve("pinned.json"))).isFalse();
    }
}
