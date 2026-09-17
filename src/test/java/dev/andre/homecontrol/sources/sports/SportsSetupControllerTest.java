package dev.andre.homecontrol.sources.sports;

import dev.andre.homecontrol.adapters.androidtv.PairingService;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.security.LoginRequiredException;
import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.security.PasswordRejectedException;
import dev.andre.homecontrol.sources.sports.calendar.CalendarFetchException;
import dev.andre.homecontrol.sources.sports.calendar.CalendarSchedule;
import dev.andre.homecontrol.sources.sports.calendar.FeedStatus;
import dev.andre.homecontrol.sources.sports.calendar.SportsCalendars;
import dev.andre.homecontrol.storage.StorageException;
import dev.andre.homecontrol.web.SetupController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.DefaultValue;
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
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({SportsSetupController.class, SetupController.class, SportsSetupAdvice.class})
class SportsSetupControllerTest {

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
    void addingACalendarRedirectsWithAMessage() throws Exception {
        String url = "https://calendar.example.org/private/token-abc123/bl.ics";
        given(calendars.add(any(), any())).willReturn(
                new SportsSettings.CalendarEntry("c-3f9a1c2b7d4e", "Bundesliga 2026/27", "calendar.example.org", null, Instant.EPOCH));

        mockMvc.perform(post("/setup/sources/sports/calendars")
                        .param("url", url).param("label", "")
                        .param("loginPassword", "pw1234567890").param("loginPasswordConfirmation", "pw1234567890"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/setup#sports"))
                .andExpect(flash().attribute("sportsMessage", "Added Bundesliga 2026/27"));

        ArgumentCaptor<SportsCalendars.AddCalendar> captor = ArgumentCaptor.forClass(SportsCalendars.AddCalendar.class);
        verify(calendars).add(captor.capture(), any());
        assertThat(captor.getValue()).isEqualTo(new SportsCalendars.AddCalendar(url, "", "pw1234567890", "pw1234567890"));
    }

    @Test
    void errorsBecomeFlashErrorsWithoutTheLink() throws Exception {
        String url = "https://calendar.example.org/private/token-abc123/bl.ics";

        willThrow(new IllegalArgumentException("Use an http, https or webcal link")).given(calendars).add(any(), any());
        mockMvc.perform(post("/setup/sources/sports/calendars").param("url", url))
                .andExpect(flash().attribute("sportsError", "Use an http, https or webcal link"))
                .andExpect(flash().attribute("sportsForm", java.util.Map.of("label", "")));

        willThrow(new CalendarFetchException(CalendarFetchException.Kind.NOT_FOUND, "calendar.example.org has no calendar at that link"))
                .given(calendars).add(any(), any());
        mockMvc.perform(post("/setup/sources/sports/calendars").param("url", url))
                .andExpect(flash().attribute("sportsError", "calendar.example.org has no calendar at that link"));

        willThrow(new PasswordRejectedException("The two passwords do not match")).given(calendars).add(any(), any());
        mockMvc.perform(post("/setup/sources/sports/calendars").param("url", url))
                .andExpect(flash().attribute("sportsError", "The two passwords do not match"));

        willThrow(new LoginRequiredException()).given(calendars).add(any(), any());
        mockMvc.perform(post("/setup/sources/sports/calendars").param("url", url))
                .andExpect(flash().attribute("sportsError", "Log in again to change sources"));

        willThrow(new StorageException("disk full", null)).given(calendars).add(any(), any());
        var result = mockMvc.perform(post("/setup/sources/sports/calendars").param("url", url))
                .andExpect(flash().attribute("sportsError", "Could not save sports settings"))
                .andReturn();
        assertThat(result.getFlashMap().values().toString()).doesNotContain("token-abc123");
    }

    @Test
    void removeAndTimeZone() throws Exception {
        given(calendars.remove("c-3f9a1c2b7d4e")).willReturn(
                new SportsSettings.CalendarEntry("c-3f9a1c2b7d4e", "Bundesliga 2026/27", "calendar.example.org", null, Instant.EPOCH));

        mockMvc.perform(post("/setup/sources/sports/calendars/c-3f9a1c2b7d4e/remove"))
                .andExpect(flash().attribute("sportsMessage", "Removed Bundesliga 2026/27"));

        mockMvc.perform(post("/setup/sources/sports/time-zone").param("timeZone", "Europe/London"))
                .andExpect(flash().attribute("sportsMessage", "Times are shown in Europe/London"));
        verify(settings).update(any());

        mockMvc.perform(post("/setup/sources/sports/time-zone").param("timeZone", ""))
                .andExpect(flash().attribute("sportsMessage", "Times are shown in Europe/Berlin (default)"));

        mockMvc.perform(post("/setup/sources/sports/time-zone").param("timeZone", "GMT+2"))
                .andExpect(flash().attribute("sportsError", "Use a time zone such as Europe/Berlin"));
    }

    @Test
    void theSetupPageListsCalendars() throws Exception {
        given(settings.current()).willReturn(SportsSettings.empty().withTimeZone("Europe/Berlin").withCalendars(List.of(
                new SportsSettings.CalendarEntry("c-3f9a1c2b7d4e", "Bundesliga 2026/27", "calendar.example.org", null, Instant.EPOCH),
                new SportsSettings.CalendarEntry("c-00000000000a", "Weekly sport", "nas.local", null, Instant.EPOCH))));
        given(schedule.status("c-3f9a1c2b7d4e")).willReturn(Optional.of(
                new FeedStatus(Instant.parse("2026-09-19T14:02:00Z"), 23, null, 1, 0, 0)));
        given(schedule.status("c-00000000000a")).willReturn(Optional.of(
                new FeedStatus(null, 0, "nas.local answered HTTP 500", 0, 0, 0)));

        String body = mockMvc.perform(get("/setup")).andReturn().getResponse().getContentAsString();

        assertThat(body).contains("id=\"sports\"").contains("Bundesliga 2026/27").contains("Weekly sport")
                .contains("calendar.example.org").contains("nas.local")
                .contains("23 events · updated 16:02")
                .contains("1 repeating event uses rules Home Control shows only once")
                .contains("Could not refresh: nas.local answered HTTP 500")
                .contains("action=\"/setup/sources/sports/calendars/c-3f9a1c2b7d4e/remove\"")
                .contains("name=\"url\"").contains("value=\"Europe/Berlin\"")
                .doesNotContain("token-abc123").doesNotContain("https://calendar");
        assertThat(body).contains("name=\"loginPassword\"");

        given(login.loginRequired()).willReturn(true);
        String body2 = mockMvc.perform(get("/setup")).andReturn().getResponse().getContentAsString();
        assertThat(body2).doesNotContain("name=\"loginPassword\"");
    }

    @Test
    void utcWarning() throws Exception {
        given(zones.effective()).willReturn(ZoneId.of("UTC"));
        given(zones.chosen()).willReturn(false);

        mockMvc.perform(get("/setup")).andExpect(content().string(
                containsString("The server's clock is set to UTC. Choose your time zone so kick-off times are right.")));
    }

    @Test
    void savesTheMapping() throws Exception {
        SportsSettings fixture = SportsSettings.empty()
                .withCalendars(List.of(new SportsSettings.CalendarEntry(
                        "c-3f9a1c2b7d4e", "Bundesliga 2026/27", "calendar.example.org", null, Instant.EPOCH)))
                .withCompetitions(List.of(new SportsSettings.CompetitionEntry(
                        "4331", "German Bundesliga", "Soccer", "Germany", null, null, Instant.EPOCH)));

        mockMvc.perform(post("/setup/sources/sports/providers")
                        .param("provider:calendar:c-3f9a1c2b7d4e", "dazn")
                        .param("provider:thesportsdb:4331", "")
                        .param("unrelated", "x"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/setup#sports-providers"))
                .andExpect(flash().attribute("sportsMessage",
                        "Saved. These are your own settings; Home Control does not check broadcast rights."));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.function.UnaryOperator<SportsSettings>> captor =
                ArgumentCaptor.forClass(java.util.function.UnaryOperator.class);
        verify(settings, org.mockito.Mockito.times(1)).update(captor.capture());
        SportsSettings result = captor.getValue().apply(fixture);
        assertThat(result.calendar("c-3f9a1c2b7d4e").orElseThrow().provider()).isEqualTo("dazn");
        assertThat(result.competition("4331").orElseThrow().provider()).isNull();
    }

    @Test
    void mappingErrorsAreFlashed() throws Exception {
        SportsSettings fixture = SportsSettings.empty().withCompetitions(List.of(
                new SportsSettings.CompetitionEntry("4331", "German Bundesliga", "Soccer", "Germany", null, null, Instant.EPOCH)));
        given(settings.update(any())).willAnswer(invocation -> {
            java.util.function.UnaryOperator<SportsSettings> op = invocation.getArgument(0);
            return op.apply(fixture);
        });

        mockMvc.perform(post("/setup/sources/sports/providers").param("provider:thesportsdb:4331", "sky"))
                .andExpect(flash().attribute("sportsError", "Unknown streaming service"));
    }

    @Test
    void theSetupPageLabelsTheMappingAsTheUsersSetting() throws Exception {
        given(settings.current()).willReturn(SportsSettings.empty().withCalendars(List.of(
                new SportsSettings.CalendarEntry("c-3f9a1c2b7d4e", "Bundesliga 2026/27", "calendar.example.org", "dazn", Instant.EPOCH))));

        String body = mockMvc.perform(get("/setup")).andReturn().getResponse().getContentAsString();

        assertThat(body).contains("id=\"sports-providers\"")
                .contains("Where you watch it (your setting)")
                .contains("This is your own setting: Home Control does not know broadcast rights and does not use TheSportsDB's TV listings.")
                .contains("<select name=\"provider:calendar:c-3f9a1c2b7d4e\"")
                .contains("<option value=\"dazn\" selected")
                .contains("<option value=\"\">Not set</option>")
                .contains("Save your settings")
                .contains("Events of a competition set to DAZN, Netflix or Prime Video open that app. For other services, paste a link to the event in the play sheet.");
        assertThat(body).doesNotContain("Available on").doesNotContain("Official")
                .doesNotContain("Broadcast by").doesNotContain("Live on DAZN");
    }

    @Test
    void noCompetitionsNoMappingForm() throws Exception {
        given(settings.current()).willReturn(SportsSettings.empty());

        String body = mockMvc.perform(get("/setup")).andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("id=\"sports-providers\"");
    }
}
