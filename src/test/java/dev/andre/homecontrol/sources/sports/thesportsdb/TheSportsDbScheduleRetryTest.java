package dev.andre.homecontrol.sources.sports.thesportsdb;

import dev.andre.homecontrol.sources.sports.JsonFileSportsStore;
import dev.andre.homecontrol.sources.sports.SportsProperties;
import dev.andre.homecontrol.sources.sports.SportsSettings;
import dev.andre.homecontrol.sources.sports.SportsSettingsService;
import dev.andre.homecontrol.sources.sports.SportsTimeZones;
import dev.andre.homecontrol.storage.SecretStore;
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
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/** Backing off after a failed day, and forgetting what belongs to removed competitions or past days. */
class TheSportsDbScheduleRetryTest {

    private static final SportsSettings.CompetitionEntry BUNDESLIGA = new SportsSettings.CompetitionEntry(
            "4331", "German Bundesliga", "Soccer", "Germany", null, null, Instant.EPOCH);
    private static final SportsSettings.CompetitionEntry PREMIER_LEAGUE = new SportsSettings.CompetitionEntry(
            "4328", "English Premier League", "Soccer", "England", null, null, Instant.EPOCH);

    @TempDir
    Path dir;

    private FakeTheSportsDbServer server;
    private SportsSettingsService settingsService;
    private MutableClock clock;
    private TheSportsDbSchedule schedule;

    @BeforeEach
    void setUp() throws IOException {
        server = new FakeTheSportsDbServer().withStandardResponses();
        server.respondJson("eventsday.php", Map.of("d", "2026-09-18", "l", "4331"), 500, "{}");
        server.respondJson("eventsday.php", Map.of("d", "2026-09-19", "l", "4331"), 500, "{}");

        settingsService = new SportsSettingsService(new JsonFileSportsStore(dir.resolve("sports.json")),
                mock(ApplicationEventPublisher.class));
        settingsService.update(s -> s.withCompetitions(List.of(BUNDESLIGA, PREMIER_LEAGUE)));
        SportsTimeZones zones = mock(SportsTimeZones.class);
        given(zones.effective()).willReturn(ZoneId.of("Europe/Berlin"));
        SportsProperties properties = new SportsProperties(true, "", 30, 10, 10, Duration.ofMinutes(120),
                new SportsProperties.Calendar(Duration.ofHours(6), 1, 2, 5242880, 3, true),
                new SportsProperties.TheSportsDb(true, server.apiBase(), "123", Duration.ofHours(24), 1, 2, null));
        clock = MutableClock.at(Instant.parse("2026-09-19T14:00:00Z"));
        TheSportsDbClient client = new TheSportsDbClient(properties.theSportsDb());
        TheSportsDbKeys keys = new TheSportsDbKeys(settingsService, mock(SecretStore.class), properties);
        schedule = new TheSportsDbSchedule(client, keys, settingsService, properties, zones, clock);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void aFailedDayIsRetriedOnlyAfterTheBackoff() {
        TheSportsDbSchedule.Result first = schedule.events();
        assertThat(server.count("eventsday.php")).isEqualTo(4);
        assertThat(first.errors()).containsExactly("German Bundesliga: TheSportsDB had a server error (HTTP 500)");
        assertThat(first.succeeded()).isEqualTo(1);

        TheSportsDbSchedule.Result again = schedule.events();
        assertThat(server.count("eventsday.php")).isEqualTo(4);
        assertThat(again.errors()).isEqualTo(first.errors());

        clock.advance(Duration.ofMinutes(11));
        schedule.events();
        assertThat(server.count("eventsday.php")).isEqualTo(6);
    }

    @Test
    void removingACompetitionForgetsItsFailures() {
        schedule.events();
        settingsService.update(s -> s.withCompetitions(List.of(PREMIER_LEAGUE)));
        schedule.events();
        settingsService.update(s -> s.withCompetitions(List.of(BUNDESLIGA, PREMIER_LEAGUE)));

        TheSportsDbSchedule.Result readded = schedule.events();

        assertThat(server.count("eventsday.php")).isEqualTo(6);
        assertThat(readded.errors()).containsExactly("German Bundesliga: TheSportsDB had a server error (HTTP 500)");
    }

    @Test
    void pastDaysLeaveTheCache() {
        schedule.events();
        assertThat(schedule.find("tsdb:2601002")).isPresent();

        clock.advance(Duration.ofDays(3));
        schedule.events();

        assertThat(schedule.find("tsdb:2601002")).isEmpty();
        assertThat(server.count("eventsday.php")).isEqualTo(8);
    }
}
