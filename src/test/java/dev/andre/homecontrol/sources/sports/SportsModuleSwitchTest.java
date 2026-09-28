package dev.andre.homecontrol.sources.sports;

import dev.andre.homecontrol.sources.sports.thesportsdb.SportsCompetitions;
import dev.andre.homecontrol.sources.sports.thesportsdb.TheSportsDbClient;
import dev.andre.homecontrol.sources.sports.thesportsdb.TheSportsDbSetupController;
import org.junit.jupiter.api.Nested;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SportsModuleSwitchTest {

    /** {@code home-control.sports.thesportsdb.enabled=false}: the sports source stays, TheSportsDB does not. */
    @SpringBootTest(properties = "home-control.sports.thesportsdb.enabled=false")
    @AutoConfigureMockMvc
    @Nested
    class TheSportsDbOff {

        static Path dataDir;

        @DynamicPropertySource
        static void isolatedDataDirectory(DynamicPropertyRegistry registry) throws IOException {
            dataDir = Files.createTempDirectory("sports-module-switch-tsdb-off");
            registry.add("shield.data-dir", () -> dataDir.toString());
        }

        @Autowired
        ApplicationContext context;

        @Autowired
        MockMvc mockMvc;

        @Test
        void theSportsDbCanBeSwitchedOffAlone() throws Exception {
            assertThat(context.getBeanNamesForType(SportsContentSource.class)).isNotEmpty();
            assertThat(context.getBeanNamesForType(TheSportsDbClient.class)).isEmpty();
            assertThat(context.getBeanNamesForType(SportsCompetitions.class)).isEmpty();
            assertThat(context.getBeanNamesForType(TheSportsDbSetupController.class)).isEmpty();

            String body = mockMvc.perform(get("/setup")).andReturn().getResponse().getContentAsString();
            assertThat(body).contains("id=\"sports\"")
                    .doesNotContain("Fixtures for the competitions you choose come from TheSportsDB");

            mockMvc.perform(post("/setup/sources/sports/competitions").param("leagueId", "4331"))
                    .andExpect(status().isNotFound());
        }
    }
}
