package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.testsupport.ModulesOffTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code home-control.jellyfin.enabled=false}: an Android TV/Cast-only box has no Jellyfin code wired up. */
class JellyfinModuleSwitchTest extends ModulesOffTest {

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
