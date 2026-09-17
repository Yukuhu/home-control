package dev.andre.homecontrol.sources.sports.calendar;

import dev.andre.homecontrol.sources.sports.JsonFileSportsStore;
import dev.andre.homecontrol.sources.sports.SportsEvent;
import dev.andre.homecontrol.sources.sports.SportsProperties;
import dev.andre.homecontrol.sources.sports.SportsSettings;
import dev.andre.homecontrol.sources.sports.SportsSettingsService;
import dev.andre.homecontrol.sources.sports.SportsTimeZones;
import dev.andre.homecontrol.sources.sports.ics.IcsCalendar;
import dev.andre.homecontrol.sources.sports.ics.IcsParser;
import dev.andre.homecontrol.storage.SecretStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class CalendarScheduleTest {

    @TempDir
    Path dir;

    private FakeCalendarServer server;
    private SecretStore secrets;
    private SportsSettingsService settingsService;
    private MutableClock clock;
    private CalendarSchedule schedule;

    @BeforeEach
    void setUp() throws IOException {
        server = new FakeCalendarServer();
        server.respondFixture("/private/token-abc123/bl.ics", "bundesliga.ics");
        server.respondFixture("/weekly.ics", "recurring.ics");

        secrets = mock(SecretStore.class);
        given(secrets.secret("sports.calendar.c-3f9a1c2b7d4e"))
                .willReturn(Optional.of(server.url("/private/token-abc123/bl.ics").toString()));
        given(secrets.secret("sports.calendar.c-00000000000a"))
                .willReturn(Optional.of(server.url("/weekly.ics").toString()));

        JsonFileSportsStore store = new JsonFileSportsStore(dir.resolve("sports.json"));
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        settingsService = new SportsSettingsService(store, events);
        settingsService.update(s -> s.withCalendars(List.of(
                new SportsSettings.CalendarEntry("c-3f9a1c2b7d4e", "Bundesliga 2026/27", "127.0.0.1", null, Instant.EPOCH),
                new SportsSettings.CalendarEntry("c-00000000000a", "Weekly sport", "127.0.0.1", null, Instant.EPOCH))));

        SportsTimeZones zones = mock(SportsTimeZones.class);
        given(zones.effective()).willReturn(ZoneId.of("Europe/Berlin"));

        clock = MutableClock.at(Instant.parse("2026-09-19T14:00:00Z"));
        SportsProperties properties = new SportsProperties(true, "", 30, 10, 10, Duration.ofMinutes(120),
                new SportsProperties.Calendar(Duration.ofHours(6), 1, 2, 5242880, 3, true),
                new SportsProperties.TheSportsDb(true, URI.create("http://127.0.0.1:9/api/v1/json"), "123",
                        Duration.ofHours(24), 1, 2, null));

        CalendarFetcher fetcher = new CalendarFetcher(properties.calendar(), new CalendarUrlPolicy(true));
        schedule = new CalendarSchedule(settingsService, fetcher, secrets, properties, zones, clock);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void loadsEveryCalendarOnce() {
        CalendarSchedule.Result result = schedule.events();

        assertThat(result.succeeded()).isEqualTo(2);
        assertThat(result.errors()).isEmpty();
        assertThat(result.events()).anyMatch(e -> e.itemId().equals("ics:c-3f9a1c2b7d4e:069e696917c4a665"));
        assertThat(result.events()).anyMatch(e -> e.competitionKey().equals("calendar:c-3f9a1c2b7d4e"));

        schedule.events();
        assertThat(server.count("/private/token-abc123/bl.ics")).isEqualTo(1);
        assertThat(server.count("/weekly.ics")).isEqualTo(1);
    }

    @Test
    void refetchesWhenStale() {
        schedule.events();
        clock.advance(Duration.ofHours(6));
        schedule.events();

        assertThat(server.count("/private/token-abc123/bl.ics")).isEqualTo(2);
        assertThat(server.count("/weekly.ics")).isEqualTo(2);
    }

    @Test
    void aFailureKeepsTheLastGoodCopyAndRetriesAfterTenMinutes() {
        schedule.events();
        clock.advance(Duration.ofHours(6));
        server.respond("/weekly.ics", 500, "text/plain", "");

        CalendarSchedule.Result result = schedule.events();
        assertThat(result.events()).anyMatch(e -> e.title().contains("Darts Premier League"));
        assertThat(result.errors()).containsExactly("Weekly sport: 127.0.0.1 answered HTTP 500");

        int afterFailure = server.count("/weekly.ics");
        clock.advance(Duration.ofMinutes(5));
        schedule.events();
        assertThat(server.count("/weekly.ics")).isEqualTo(afterFailure);

        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        schedule.events();
        assertThat(server.count("/weekly.ics")).isEqualTo(afterFailure + 1);
    }

    @Test
    void aCalendarThatNeverLoadedIsAnError() {
        server.respond("/weekly.ics", 404, "text/plain", "");

        CalendarSchedule.Result result = schedule.events();

        assertThat(result.succeeded()).isEqualTo(1);
        assertThat(result.errors()).containsExactly("Weekly sport: 127.0.0.1 has no calendar at that link");
    }

    @Test
    void aMissingSecretIsExplained() {
        given(secrets.secret("sports.calendar.c-3f9a1c2b7d4e")).willReturn(Optional.empty());

        CalendarSchedule.Result result = schedule.events();

        assertThat(result.errors()).contains(
                "Bundesliga 2026/27: The link for Bundesliga 2026/27 is missing; remove the calendar and add it again");
        assertThat(server.count("/private/token-abc123/bl.ics")).isZero();
    }

    @Test
    void htmlIsNotACalendar() {
        server.respond("/weekly.ics", 200, "text/html", "<html></html>");

        CalendarSchedule.Result result = schedule.events();

        assertThat(result.errors()).contains("Weekly sport: That link did not return a calendar (.ics)");
    }

    @Test
    void findsItemsAndStatuses() {
        Optional<SportsEvent> found = schedule.find("ics:c-3f9a1c2b7d4e:069e696917c4a665");
        assertThat(found).isPresent();
        assertThat(schedule.find("ics:nope")).isEmpty();

        FeedStatus status = schedule.status("c-00000000000a").orElseThrow();
        assertThat(status.fetchedAt()).isEqualTo(clock.instant());
        assertThat(status.events()).isGreaterThan(0);
        assertThat(status.unsupportedRules()).isEqualTo(1);
        assertThat(status.error()).isNull();
    }

    @Test
    void primeAndForget() throws Exception {
        IcsCalendar parsed = IcsParser.parse(fixture("bundesliga.ics"));
        schedule.prime("c-3f9a1c2b7d4e", parsed);

        schedule.events();
        assertThat(server.count("/private/token-abc123/bl.ics")).isZero();

        schedule.forget("c-3f9a1c2b7d4e");
        assertThat(schedule.find("ics:c-3f9a1c2b7d4e:069e696917c4a665")).isEmpty();
    }

    @Test
    void removedCalendarsAreDropped() {
        schedule.events();
        settingsService.update(s -> s.withCalendars(List.of(s.calendars().get(1))));

        CalendarSchedule.Result result = schedule.events();
        assertThat(result.events()).noneMatch(e -> e.competitionKey().equals("calendar:c-3f9a1c2b7d4e"));
    }

    private static String fixture(String name) throws IOException {
        try (var in = CalendarScheduleTest.class.getResourceAsStream("/fixtures/ics/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
