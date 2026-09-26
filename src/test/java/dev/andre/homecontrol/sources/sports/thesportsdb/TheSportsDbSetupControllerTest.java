package dev.andre.homecontrol.sources.sports.thesportsdb;

import dev.andre.homecontrol.adapters.androidtv.PairingService;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.sources.sports.SportsProperties;
import dev.andre.homecontrol.sources.sports.SportsSetupController;
import dev.andre.homecontrol.sources.sports.SportsSetupAdvice;
import dev.andre.homecontrol.sources.sports.SportsSettings;
import dev.andre.homecontrol.sources.sports.SportsSettingsService;
import dev.andre.homecontrol.sources.sports.SportsTimeZones;
import dev.andre.homecontrol.sources.sports.calendar.CalendarSchedule;
import dev.andre.homecontrol.sources.sports.calendar.FeedStatus;
import dev.andre.homecontrol.sources.sports.calendar.SportsCalendars;
import dev.andre.homecontrol.web.SetupController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.time.Duration;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({TheSportsDbSetupController.class, SportsSetupController.class, SetupController.class, SportsSetupAdvice.class})
class TheSportsDbSetupControllerTest {

    @TestConfiguration
    static class Config {
        @Bean
        SportsProperties sportsProperties() {
            return new SportsProperties(true, "", 30, 10, 10, Duration.ofMinutes(120),
                    new SportsProperties.Calendar(Duration.ofHours(6), 5, 15, 5242880, 3, false),
                    new SportsProperties.TheSportsDb(true, URI.create("https://www.thesportsdb.com/api/v1/json"),
                            "123", Duration.ofHours(24), 5, 15, null));
        }
    }

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    SportsCalendars calendars;

    @MockitoBean
    SportsSettingsService settings;

    @MockitoBean
    SportsTimeZones zones;

    @MockitoBean
    CalendarSchedule schedule;

    @MockitoBean
    LoginService login;

    @MockitoBean
    SportsCompetitions competitions;

    @MockitoBean
    TheSportsDbSchedule tsdbSchedule;

    @MockitoBean
    PairingService pairing;

    @MockitoBean
    DeviceManager devices;

    @BeforeEach
    void defaults() {
        given(devices.devices()).willReturn(List.of());
        given(devices.pairable()).willReturn(List.of());
        given(devices.addable()).willReturn(List.of());
        given(login.loginRequired()).willReturn(false);
        given(settings.current()).willReturn(SportsSettings.empty());
        given(zones.effective()).willReturn(ZoneId.of("Europe/Berlin"));
        given(zones.chosen()).willReturn(true);
    }

    @Test
    void addAndRemove() throws Exception {
        given(competitions.add("4331")).willReturn(
                new SportsSettings.CompetitionEntry("4331", "German Bundesliga", "Soccer", "Germany", null, null, Instant.EPOCH));
        mockMvc.perform(post("/setup/sources/sports/competitions").param("leagueId", "4331"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("sportsMessage", "Added German Bundesliga"));

        given(competitions.remove("4331")).willReturn(
                new SportsSettings.CompetitionEntry("4331", "German Bundesliga", "Soccer", "Germany", null, null, Instant.EPOCH));
        mockMvc.perform(post("/setup/sources/sports/competitions/4331/remove"))
                .andExpect(flash().attribute("sportsMessage", "Removed German Bundesliga"));

        doThrow(new IllegalArgumentException("TheSportsDB has no competition 999"))
                .when(competitions).add("999");
        mockMvc.perform(post("/setup/sources/sports/competitions").param("leagueId", "999"))
                .andExpect(flash().attribute("sportsError", "TheSportsDB has no competition 999"));
    }

    @Test
    void searchFlashesResults() throws Exception {
        given(competitions.search("Germany", "Soccer")).willReturn(List.of(
                new League("4485", "DFB-Pokal", "Soccer", "Germany", null),
                new League("4399", "German 2. Bundesliga", "Soccer", "Germany", null),
                new League("4331", "German Bundesliga", "Soccer", "Germany", null),
                new League("5891", "German Oberliga Baden-Württemberg", "Soccer", "Germany", null)));
        given(settings.current()).willReturn(SportsSettings.empty().withCompetitions(List.of(
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
        verify(competitions).usePersonalKey(captor.capture(), any());
        assertThat(captor.getValue()).isEqualTo(new SportsCompetitions.PersonalKey("9876543210", "household password", "household password"));

        var result = mockMvc.perform(post("/setup/sources/sports/thesportsdb/key").param("key", "9876543210")).andReturn();
        assertThat(result.getFlashMap().values().toString()).doesNotContain("9876543210");

        mockMvc.perform(post("/setup/sources/sports/thesportsdb/free-key"))
                .andExpect(flash().attribute("sportsMessage", "Using the free TheSportsDB key"));
    }

    @Test
    void theSetupPageShowsTheSportsDb() throws Exception {
        given(settings.current()).willReturn(SportsSettings.empty().withCompetitions(List.of(
                new SportsSettings.CompetitionEntry("4331", "German Bundesliga", "Soccer", "Germany", null, null, Instant.EPOCH),
                new SportsSettings.CompetitionEntry("4328", "English Premier League", "Soccer", "England", null, null, Instant.EPOCH))));
        given(tsdbSchedule.status("4331")).willReturn(Optional.of(new FeedStatus(Instant.parse("2026-09-19T14:00:00Z"), 5, null, 0, 0, 0)));
        given(tsdbSchedule.status("4328")).willReturn(Optional.of(
                new FeedStatus(null, 0, "TheSportsDB is limiting requests; try again in a minute", 0, 0, 0)));

        String body = mockMvc.perform(get("/setup")).andReturn().getResponse().getContentAsString();

        assertThat(body).contains("TheSportsDB")
                .contains("The free key shows at most 3 matches per competition per day.")
                .contains("German Bundesliga")
                .contains("5 events · updated 16:00")
                .contains("Could not refresh: TheSportsDB is limiting requests; try again in a minute")
                .contains("action=\"/setup/sources/sports/competitions/4331/remove\"")
                .contains("name=\"key\"").contains("type=\"password\"")
                .contains("Data from <a href=\"https://www.thesportsdb.com\"")
                .doesNotContain("9876543210")
                .contains("id=\"sports-providers\"")
                .contains("name=\"provider:thesportsdb:4331\"")
                .contains("German Bundesliga")
                .contains("English Premier League")
                .contains("<option value=\"\" selected=\"selected\">Not set</option>");
    }
}
