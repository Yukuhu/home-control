package dev.andre.homecontrol.sources.sports.thesportsdb;

import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.security.LoginRequiredException;
import dev.andre.homecontrol.security.PasswordRejectedException;
import dev.andre.homecontrol.sources.sports.SportsSettings;
import dev.andre.homecontrol.sources.sports.calendar.FeedStatus;
import dev.andre.homecontrol.storage.StorageException;
import dev.andre.homecontrol.testsupport.WebSliceTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TheSportsDbSetupControllerTest extends WebSliceTest {

    @Autowired
    MockMvc mockMvc;

    @BeforeEach
    void defaults() {
        given(devices.devices()).willReturn(List.of());
        given(enrollment.pairable()).willReturn(List.of());
        given(enrollment.addable()).willReturn(List.of());
        given(login.loginRequired()).willReturn(false);
        given(sportsSettings.current()).willReturn(SportsSettings.empty());
        given(sportsZones.effective()).willReturn(ZoneId.of("Europe/Berlin"));
        given(sportsZones.chosen()).willReturn(true);
    }

    @Test
    void addAndRemove() throws Exception {
        given(sportsCompetitions.add("4331")).willReturn(
                new SportsSettings.CompetitionEntry("4331", "German Bundesliga", "Soccer", "Germany", null, null, Instant.EPOCH));
        mockMvc.perform(post("/setup/sources/sports/competitions").param("leagueId", "4331"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("sportsMessage", "Added German Bundesliga"));

        given(sportsCompetitions.remove("4331")).willReturn(
                new SportsSettings.CompetitionEntry("4331", "German Bundesliga", "Soccer", "Germany", null, null, Instant.EPOCH));
        mockMvc.perform(post("/setup/sources/sports/competitions/4331/remove"))
                .andExpect(flash().attribute("sportsMessage", "Removed German Bundesliga"));

        doThrow(new IllegalArgumentException("TheSportsDB has no competition 999"))
                .when(sportsCompetitions).add("999");
        mockMvc.perform(post("/setup/sources/sports/competitions").param("leagueId", "999"))
                .andExpect(flash().attribute("sportsError", "TheSportsDB has no competition 999"));
    }

    @Test
    void searchFlashesResults() throws Exception {
        given(sportsCompetitions.search("Germany", "Soccer")).willReturn(List.of(
                new League("4485", "DFB-Pokal", "Soccer", "Germany", null),
                new League("4399", "German 2. Bundesliga", "Soccer", "Germany", null),
                new League("4331", "German Bundesliga", "Soccer", "Germany", null),
                new League("5891", "German Oberliga Baden-Württemberg", "Soccer", "Germany", null)));
        given(sportsSettings.current()).willReturn(SportsSettings.empty().withCompetitions(List.of(
                new SportsSettings.CompetitionEntry("4331", "German Bundesliga", "Soccer", "Germany", null, null, Instant.EPOCH))));

        var result = mockMvc.perform(post("/setup/sources/sports/thesportsdb/search")
                        .param("country", "Germany").param("sport", "Soccer"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        TheSportsDbSetupController.SearchView search =
                (TheSportsDbSetupController.SearchView) result.getFlashMap().get("sportsSearch");
        assertThat(search.leagues()).hasSize(4);
        assertThat(search.leagues().stream().filter(l -> l.id().equals("4331")).findFirst().orElseThrow().added()).isTrue();
        assertThat(search.leagues().stream().filter(l -> l.id().equals("4399")).findFirst().orElseThrow().added()).isFalse();
    }

    @Test
    void keys() throws Exception {
        mockMvc.perform(post("/setup/sources/sports/thesportsdb/key")
                        .param("key", "9876543210").param("loginPassword", "household password")
                        .param("loginPasswordConfirmation", "household password"))
                .andExpect(flash().attribute("sportsMessage", "Using your TheSportsDB key"));

        ArgumentCaptor<SportsCompetitions.PersonalKey> captor = ArgumentCaptor.forClass(SportsCompetitions.PersonalKey.class);
        verify(sportsCompetitions).usePersonalKey(captor.capture(), any());
        assertThat(captor.getValue()).isEqualTo(new SportsCompetitions.PersonalKey("9876543210", "household password", "household password"));

        var result = mockMvc.perform(post("/setup/sources/sports/thesportsdb/key").param("key", "9876543210")).andReturn();
        assertThat(result.getFlashMap().values().toString()).doesNotContain("9876543210");

        mockMvc.perform(post("/setup/sources/sports/thesportsdb/free-key"))
                .andExpect(flash().attribute("sportsMessage", "Using the free TheSportsDB key"));
    }

    @Test
    void theSetupPageShowsTheSportsDb() throws Exception {
        given(sportsSettings.current()).willReturn(SportsSettings.empty().withCompetitions(List.of(
                new SportsSettings.CompetitionEntry("4331", "German Bundesliga", "Soccer", "Germany", null, null, Instant.EPOCH),
                new SportsSettings.CompetitionEntry("4328", "English Premier League", "Soccer", "England", null, null, Instant.EPOCH))));
        given(theSportsDbSchedule.status("4331")).willReturn(Optional.of(new FeedStatus(Instant.parse("2026-09-19T14:00:00Z"), 5, null, 0, 0, 0)));
        given(theSportsDbSchedule.status("4328")).willReturn(Optional.of(
                new FeedStatus(null, 0, "TheSportsDB is limiting requests; try again in a minute", 0, 0, 0)));

        String body = mockMvc.perform(get("/setup")).andReturn().getResponse().getContentAsString();

        assertThat(body).contains("TheSportsDB")
                .contains("The free key shows at most 3 matches per competition per day.")
                .contains("German Bundesliga")
                .contains("5 events · updated 16:00")
                .contains("Could not refresh: TheSportsDB is limiting requests; try again in a minute")
                .contains("action=\"/setup/sources/sports/competitions/4331/remove\"")
                .contains("type=\"password\" name=\"key\"")
                .contains("Data from <a href=\"https://www.thesportsdb.com\"")
                .doesNotContain("9876543210")
                .contains("id=\"sports-providers\"")
                .contains("name=\"provider:thesportsdb:4331\"")
                .contains("German Bundesliga")
                .contains("English Premier League")
                .contains("<option value=\"\" selected=\"selected\">Not set</option>");
    }

    @Test
    void storageFailuresShowOneMessageWithoutFileDetails() throws Exception {
        StorageException disk = new StorageException("Could not write /data/sports.json", new java.io.IOException("disk full"));
        doThrow(disk).when(sportsCompetitions).add("4331");
        doThrow(disk).when(sportsCompetitions).remove("4331");
        doThrow(disk).when(sportsCompetitions).search("Germany", "Soccer");
        doThrow(disk).when(sportsCompetitions).usePersonalKey(any(), any());
        doThrow(disk).when(sportsCompetitions).useFreeKey();

        for (var request : List.of(
                post("/setup/sources/sports/competitions").param("leagueId", "4331"),
                post("/setup/sources/sports/competitions/4331/remove"),
                post("/setup/sources/sports/thesportsdb/search").param("country", "Germany").param("sport", "Soccer"),
                post("/setup/sources/sports/thesportsdb/key").param("key", "9876543210"),
                post("/setup/sources/sports/thesportsdb/free-key"))) {
            mockMvc.perform(request)
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/setup#sports"))
                    .andExpect(flash().attribute("sportsError", "Could not save sports settings"));
        }
    }

    @Test
    void refusedKeysSearchesAndRemovalsSayWhy() throws Exception {
        doThrow(new LoginRequiredException())
                .doThrow(new PasswordRejectedException("The two passwords do not match"))
                .doThrow(new TheSportsDbException(ContentSourceException.Kind.UNAUTHORIZED, "TheSportsDB did not accept that key"))
                .when(sportsCompetitions).usePersonalKey(any(), any());
        doThrow(new TheSportsDbException(ContentSourceException.Kind.UNREACHABLE, "TheSportsDB is unreachable"))
                .when(sportsCompetitions).search("Germany", "Soccer");
        doThrow(new IllegalArgumentException("Choose a country or a sport")).when(sportsCompetitions).search(null, null);
        doThrow(new IllegalArgumentException("Competition 999 is not added")).when(sportsCompetitions).remove("999");
        doThrow(new TheSportsDbException(ContentSourceException.Kind.RATE_LIMITED, "TheSportsDB is busy; try again later"))
                .when(sportsCompetitions).add("4331");

        for (String expected : List.of("Log in again to change sources", "The two passwords do not match",
                "TheSportsDB did not accept that key")) {
            var result = mockMvc.perform(post("/setup/sources/sports/thesportsdb/key").param("key", "9876543210"))
                    .andExpect(flash().attribute("sportsError", expected))
                    .andReturn();
            assertThat(result.getFlashMap().values().toString()).doesNotContain("9876543210");
        }
        mockMvc.perform(post("/setup/sources/sports/thesportsdb/search").param("country", "Germany").param("sport", "Soccer"))
                .andExpect(flash().attribute("sportsError", "TheSportsDB is unreachable"));
        mockMvc.perform(post("/setup/sources/sports/thesportsdb/search"))
                .andExpect(flash().attribute("sportsError", "Choose a country or a sport"));
        mockMvc.perform(post("/setup/sources/sports/competitions/999/remove"))
                .andExpect(flash().attribute("sportsError", "Competition 999 is not added"));
        mockMvc.perform(post("/setup/sources/sports/competitions").param("leagueId", "4331"))
                .andExpect(flash().attribute("sportsError", "TheSportsDB is busy; try again later"));
    }
}
