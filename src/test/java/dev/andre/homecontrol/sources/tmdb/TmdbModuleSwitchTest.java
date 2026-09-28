package dev.andre.homecontrol.sources.tmdb;

import dev.andre.homecontrol.core.content.ContentSources;
import dev.andre.homecontrol.testsupport.ModulesOffTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code home-control.tmdb.enabled=false}: no TMDB code is wired up. */
class TmdbModuleSwitchTest extends ModulesOffTest {

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
