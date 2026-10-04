package dev.andre.homecontrol.sources.sports.thesportsdb;

import dev.andre.homecontrol.sources.sports.feed.FeedResult;
import dev.andre.homecontrol.sources.sports.settings.JsonFileSportsStore;
import dev.andre.homecontrol.sources.sports.feed.SportsEvent;
import dev.andre.homecontrol.sources.sports.settings.SportsProperties;
import dev.andre.homecontrol.sources.sports.settings.SportsSettings;
import dev.andre.homecontrol.sources.sports.settings.SportsSettingsService;
import dev.andre.homecontrol.sources.sports.settings.SportsTimeZones;
import dev.andre.homecontrol.testsupport.MutableClock;
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
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class TheSportsDbScheduleTest {

    @TempDir
    Path dir;

    private FakeTheSportsDbServer server;
    private SportsSettingsService settingsService;
    private MutableClock clock;
    private TheSportsDbSchedule schedule;

    private SportsTimeZones zones;
    private SportsProperties properties;
    private TheSportsDbKeys keys;

    @BeforeEach
    void setUp() throws IOException {
        server = new FakeTheSportsDbServer().withStandardResponses();

        JsonFileSportsStore store = new JsonFileSportsStore(dir.resolve("sports.json"));
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        settingsService = new SportsSettingsService(store, events);
        settingsService.update(s -> s.withCompetitions(List.of(
                new SportsSettings.CompetitionEntry("4331", "German Bundesliga", "Soccer", "Germany", null, null, Instant.EPOCH),
                new SportsSettings.CompetitionEntry("4328", "English Premier League", "Soccer", "England", null, null, Instant.EPOCH))));

        zones = mock(SportsTimeZones.class);
        given(zones.effective()).willReturn(ZoneId.of("Europe/Berlin"));

        properties = new SportsProperties(true, "", 30, 10, 10, Duration.ofMinutes(120),
                new SportsProperties.Calendar(Duration.ofHours(6), Duration.ofSeconds(1), Duration.ofSeconds(2),
                5242880, 3, true),
                new SportsProperties.TheSportsDb(true, server.apiBase(), "123", Duration.ofHours(24),
                Duration.ofSeconds(1), Duration.ofSeconds(2), null, true));

        clock = MutableClock.at(Instant.parse("2026-09-19T14:00:00Z"));
        TheSportsDbClient client = new TheSportsDbClient(properties.theSportsDb());
        keys = new TheSportsDbKeys(settingsService, mock(dev.andre.homecontrol.storage.SecretStore.class), properties);
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
        FeedResult result = schedule.events();

        assertThat(server.count("eventsday.php")).isEqualTo(4);
        assertThat(result.events()).extracting(SportsEvent::itemId)
                .contains("tsdb:2508360", "tsdb:2508361", "tsdb:2601002");
        assertThat(result.succeeded()).isEqualTo(2);
        assertThat(result.errors()).isEmpty();

        schedule.events();
        assertThat(server.count("eventsday.php")).isEqualTo(4);
    }

    @Test
    void aTimeZoneChangeFetchesTheDaysAgainForTheNewZone() {
        schedule.events();
        int fetched = server.count("eventsday.php");

        // London's day covers the same UTC dates: only the zone the days were placed in differs.
        given(zones.effective()).willReturn(ZoneId.of("Europe/London"));
        schedule.events();

        assertThat(server.count("eventsday.php")).isEqualTo(2 * fetched);
    }

    @Test
    void aTimeZoneChangeWhoseFetchesFailKeepsNoDaysPlacedInTheOldZone() {
        schedule.events();
        assertThat(schedule.find("tsdb:2508360")).isPresent();

        given(zones.effective()).willReturn(ZoneId.of("Europe/London"));
        for (String league : List.of("4331", "4328")) {
            for (String day : List.of("2026-09-18", "2026-09-19")) {
                server.respondJson("eventsday.php", java.util.Map.of("d", day, "l", league), 500, "{}");
            }
        }
        FeedResult result = schedule.events();

        // An all-day fixture placed in Berlin would be judged against London's today.
        assertThat(result.events()).isEmpty();
        assertThat(result.errors()).hasSize(2);
        assertThat(schedule.find("tsdb:2508360")).isEmpty();
        assertThat(schedule.status("4331")).hasValueSatisfying(status -> assertThat(status.events()).isZero());
    }

    @Test
    void anUnexpectedFailureFailsOnlyItsCompetition() {
        TheSportsDbClient failing = mock(TheSportsDbClient.class);
        given(failing.eventsDay(any(), any(), eq("4331"))).willThrow(new IllegalStateException("an unexpected answer"));
        given(failing.eventsDay(any(), any(), eq("4328"))).willReturn(List.of());
        TheSportsDbSchedule withFailing =
                new TheSportsDbSchedule(failing, keys, settingsService, properties, zones, clock);

        FeedResult result = withFailing.events();

        assertThat(result.errors()).singleElement().asString().startsWith("German Bundesliga: ");
        assertThat(result.succeeded()).isEqualTo(1);
    }

    @Test
    void rateLimitingStopsTheRound() {
        server.respondJson("eventsday.php", java.util.Map.of("d", "2026-09-18", "l", "4331"), 429, "{}");

        FeedResult result = schedule.events();

        assertThat(server.count("eventsday.php")).isEqualTo(1);
        assertThat(result.errors()).containsExactlyInAnyOrder(
                "German Bundesliga: TheSportsDB is limiting requests; try again in a minute",
                "English Premier League: TheSportsDB is limiting requests; try again in a minute");
        assertThat(result.succeeded()).isZero();
    }

    @Test
    void aMissingPersonalKeyIsAnErrorWithoutRequests() {
        settingsService.update(s -> s.withKeyKind(SportsSettings.KeyKind.PERSONAL));

        FeedResult result = schedule.events();

        assertThat(result.errors()).isNotEmpty().allMatch(e -> e.contains("Your TheSportsDB key is missing"));
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

        FeedResult result = schedule.events();
        assertThat(result.events()).noneMatch(e -> e.itemId().startsWith("tsdb:26"));
    }
}
