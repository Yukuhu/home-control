package dev.andre.homecontrol.sources.sports;

import dev.andre.homecontrol.testsupport.ModulesOffTest;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code home-control.sports.enabled=false}: no sports code is wired up. */
class SportsModuleOffTest extends ModulesOffTest {

    @Test
    void theModuleCanBeSwitchedOff() throws Exception {
        assertThat(context.getBeanNamesForType(SportsContentSource.class)).isEmpty();
        assertThat(context.getBeanNamesForType(dev.andre.homecontrol.sources.sports.calendar.SportsCalendars.class)).isEmpty();
        assertThat(context.getBeanNamesForType(SportsSetupController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(SportsSetupSection.class)).isEmpty();

        mockMvc.perform(get("/setup"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("id=\"sports\""))));

        assertThat(Files.exists(dataDir().resolve("sports.json"))).isFalse();
    }
}
