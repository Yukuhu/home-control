package dev.andre.homecontrol.sources.pinned;

import dev.andre.homecontrol.testsupport.ModulesOffTest;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code home-control.pinned.enabled=false}: no pinned-shortcuts code is wired up. */
class PinnedModuleSwitchTest extends ModulesOffTest {

    @Test
    void theModuleCanBeSwitchedOff() throws Exception {
        assertThat(context.getBeanNamesForType(PinnedShortcuts.class)).isEmpty();
        assertThat(context.getBeanNamesForType(PinnedContentSource.class)).isEmpty();
        assertThat(context.getBeanNamesForType(PinnedSetupController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(PinnedSetupSection.class)).isEmpty();

        mockMvc.perform(get("/setup"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("id=\"pinned\""))));

        assertThat(Files.exists(dataDir().resolve("pinned.json"))).isFalse();
    }
}
