package dev.andre.homecontrol.sources.sports.thesportsdb;

import dev.andre.homecontrol.sources.sports.JsonFileSportsStore;
import dev.andre.homecontrol.sources.sports.SportsEvent;
import dev.andre.homecontrol.sources.sports.SportsProperties;
import dev.andre.homecontrol.sources.sports.SportsSettings;
import dev.andre.homecontrol.sources.sports.SportsSettingsService;
import dev.andre.homecontrol.sources.sports.SportsTimeZones;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class TheSportsDbScheduleTest {

    @TempDir
    Path dir;

    private FakeTheSportsDbServer server;
    private SportsSettingsService settingsService;
    private MutableClock clock;
    private TheSportsDbSchedule schedule;

    @BeforeEach
    void setUp() throws IOException {
        server = new FakeTheSportsDbServer().withStandardResponses();

        JsonFileSportsStore store = new JsonFileSportsStore(dir.resolve("sports.json"));
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        settingsService = new SportsSettingsService(store, events);
        settingsService.update(s -> s.withCompetitions(List.of(
                new SportsSettings.CompetitionEntry("4331", "German Bundesliga", "Soccer", "Germany", null, null, Instant.EPOCH),
                new SportsSettings.CompetitionEntry("4328", "English Premier League", "Soccer", "England", null, null, Instant.EPOCH))));

        SportsTimeZones zones = mock(SportsTimeZones.class);
        given(zones.effective()).willReturn(ZoneId.of("Europe/Berlin"));

        SportsProperties properties = new SportsProperties(true, "", 30, 10, 10, Duration.ofMinutes(120),
                new SportsProperties.Calendar(Duration.ofHours(6), 1, 2, 5242880, 3, true),
                new SportsProperties.TheSportsDb(true, server.apiBase(), "123", Duration.ofHours(24), 1, 2, null));

        clock = MutableClock.at(Instant.parse("2026-09-19T14:00:00Z"));
        TheSportsDbClient client = new TheSportsDbClient(properties.theSportsDb());
        TheSportsDbKeys keys = new TheSportsDbKeys(settingsService, mock(dev.andre.homecontrol.storage.SecretStore.class), properties);
        schedule = new TheSportsDbSchedule(client, keys, settingsService, properties, zones, clock);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void utcDatesCoverTheLocalDay() {
        Instant now = Instant.parse("2026-09-19T14:00:00Z");
        assertThat(TheSportsDbSchedule.utcDates(now, ZoneId.of("Europe/Berlin")))
                .containsExactlyInAnyOrder(LocalDate.of(2026, 9, 18), LocalDate.of(2026, 9, 19));
        assertThat(TheSportsDbSchedule.utcDates(now, ZoneId.of("America/Los_Angeles")))
                .containsExactlyInAnyOrder(LocalDate.of(2026, 9, 19), LocalDate.of(2026, 9, 20));
        assertThat(TheSportsDbSchedule.utcDates(now, ZoneId.of("Pacific/Kiritimati")))
                .containsExactlyInAnyOrder(LocalDate.of(2026, 9, 19), LocalDate.of(2026, 9, 20));
    }

    @Test
    void fetchesEachCompetitionAndDateOnce() {
        TheSportsDbSchedule.Result result = schedule.events();

        assertThat(server.count("eventsday.php")).isEqualTo(4);
        assertThat(result.events()).extracting(SportsEvent::itemId)
                .contains("tsdb:2508360", "tsdb:2508361", "tsdb:2601002");
        assertThat(result.succeeded()).isEqualTo(2);
        assertThat(result.errors()).isEmpty();

        schedule.events();
        assertThat(server.count("eventsday.php")).isEqualTo(4);
    }

    @Test
    void rateLimitingStopsTheRound() {
        server.respondJson("eventsday.php", java.util.Map.of("d", "2026-09-18", "l", "4331"), 429, "{}");

        TheSportsDbSchedule.Result result = schedule.events();

        assertThat(server.count("eventsday.php")).isEqualTo(1);
        assertThat(result.errors()).containsExactlyInAnyOrder(
                "German Bundesliga: TheSportsDB is limiting requests; try again in a minute",
                "English Premier League: TheSportsDB is limiting requests; try again in a minute");
        assertThat(result.succeeded()).isZero();
    }

    @Test
    void aMissingPersonalKeyIsAnErrorWithoutRequests() {
        settingsService.update(s -> s.withKeyKind(SportsSettings.KeyKind.PERSONAL));

        TheSportsDbSchedule.Result result = schedule.events();

        assertThat(result.errors()).allMatch(e -> e.contains("Your TheSportsDB key is missing"));
        assertThat(server.count("eventsday.php")).isZero();
    }

    @Test
    void findsCachedEventsAndReportsStatus() {
        assertThat(schedule.find("tsdb:2508365")).isPresent();
        assertThat(schedule.find("tsdb:1")).isEmpty();

        var status = schedule.status("4331").orElseThrow();
        assertThat(status.fetchedAt()).isEqualTo(clock.instant());
        assertThat(status.events()).isEqualTo(5);
        assertThat(status.error()).isNull();
    }

    @Test
    void forgetAndClear() {
        schedule.events();
        int before = server.count("eventsday.php");

        schedule.forget("4331");
        schedule.events();
        assertThat(server.count("eventsday.php")).isEqualTo(before + 2);

        schedule.clear();
        schedule.events();
        assertThat(server.count("eventsday.php")).isEqualTo(before + 2 + 4);
    }

    @Test
    void removedCompetitionsAreIgnored() {
        schedule.events();
        settingsService.update(s -> s.withCompetitions(List.of(s.competitions().get(0))));

        TheSportsDbSchedule.Result result = schedule.events();
        assertThat(result.events()).noneMatch(e -> e.itemId().startsWith("tsdb:26"));
    }
}
