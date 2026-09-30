package dev.andre.homecontrol.sources.sports.thesportsdb;

import dev.andre.homecontrol.sources.sports.feed.FeedResult;
import dev.andre.homecontrol.sources.sports.feed.SportsEvent;
import dev.andre.homecontrol.sources.sports.settings.JsonFileSportsStore;
import dev.andre.homecontrol.sources.sports.settings.SportsProperties;
import dev.andre.homecontrol.sources.sports.settings.SportsSettings;
import dev.andre.homecontrol.sources.sports.settings.SportsSettingsService;
import dev.andre.homecontrol.sources.sports.settings.SportsTimeZones;
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
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/** Passes that meet: downloads run without the schedule's lock, and a second caller joins a running one. */
class TheSportsDbScheduleConcurrencyTest {

    private static final String EVENTS_DAY = "eventsday.php";
    /** An event on 2026-09-19 in the German Bundesliga (league 4331). */
    private static final String BUNDESLIGA_ITEM = "tsdb:2508365";

    @TempDir
    Path dir;

    private final CountDownLatch release = new CountDownLatch(1);
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    private FakeTheSportsDbServer server;
    private SportsSettingsService settingsService;
    private TheSportsDbSchedule schedule;

    @BeforeEach
    void setUp() throws IOException {
        server = new FakeTheSportsDbServer().withStandardResponses();
        settingsService = new SportsSettingsService(new JsonFileSportsStore(dir.resolve("sports.json")),
                mock(ApplicationEventPublisher.class));
        settingsService.update(s -> s.withCompetitions(List.of(
                new SportsSettings.CompetitionEntry("4331", "German Bundesliga", "Soccer", "Germany", null, null, Instant.EPOCH),
                new SportsSettings.CompetitionEntry("4328", "English Premier League", "Soccer", "England", null, null, Instant.EPOCH))));

        SportsTimeZones zones = mock(SportsTimeZones.class);
        given(zones.effective()).willReturn(ZoneId.of("Europe/Berlin"));
        // A held answer waits for the test, so the request timeout must outlast any test.
        SportsProperties properties = new SportsProperties(true, "", 30, 10, 10, Duration.ofMinutes(120),
                new SportsProperties.Calendar(Duration.ofHours(6), Duration.ofSeconds(1), Duration.ofSeconds(2),
                        5242880, 3, true),
                new SportsProperties.TheSportsDb(true, server.apiBase(), "123", Duration.ofHours(24),
                        Duration.ofSeconds(1), Duration.ofSeconds(30), null, true));
        MutableClock clock = MutableClock.at(Instant.parse("2026-09-19T14:00:00Z"));
        TheSportsDbClient client = new TheSportsDbClient(properties.theSportsDb());
        TheSportsDbKeys keys = new TheSportsDbKeys(settingsService, mock(SecretStore.class), properties);
        schedule = new TheSportsDbSchedule(client, keys, settingsService, properties, zones, clock);
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        pool.shutdownNow();
        server.close();
    }

    private void holdDay(String date, String leagueId, String fixture) {
        server.hold(EVENTS_DAY, Map.of("d", date, "l", leagueId), 200, fixture, release);
    }

    private Future<FeedResult> passHeldAfter(int requestsSoFar) {
        Future<FeedResult> pass = pool.submit(schedule::events);
        await().until(() -> server.count(EVENTS_DAY) == requestsSoFar);
        return pass;
    }

    private static void awaitStillWaiting(Future<?> waiter) {
        await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(2)).until(() -> !waiter.isDone());
    }

    @Test
    void twoPassesAtOnceSendOneRequestPerDay() throws Exception {  // guard
        holdDay("2026-09-19", "4331", "eventsday-2026-09-19-4331.json");
        Future<FeedResult> first = passHeldAfter(2);
        Future<FeedResult> second = pool.submit(schedule::events);
        awaitStillWaiting(second);

        release.countDown();

        assertThat(first.get(10, TimeUnit.SECONDS).succeeded()).isEqualTo(2);
        assertThat(second.get(10, TimeUnit.SECONDS).succeeded()).isEqualTo(2);
        assertThat(server.count(EVENTS_DAY)).isEqualTo(4);
    }

    @Test
    void aFirstLookupDuringTheFirstPassWaitsForIt() throws Exception {
        holdDay("2026-09-19", "4331", "eventsday-2026-09-19-4331.json");
        Future<FeedResult> pass = passHeldAfter(2);
        Future<Optional<SportsEvent>> found = pool.submit(() -> schedule.find(BUNDESLIGA_ITEM));
        awaitStillWaiting(found);

        release.countDown();

        assertThat(found.get(10, TimeUnit.SECONDS)).isPresent();
        pass.get(10, TimeUnit.SECONDS);
        assertThat(server.count(EVENTS_DAY)).isEqualTo(4);
    }

    @Test
    void aWaitingPassThatIsInterruptedStopsFetching() throws Exception {
        holdDay("2026-09-18", "4331", "eventsday-2026-09-18-4331.json");
        Future<FeedResult> first = passHeldAfter(1);
        AtomicReference<FeedResult> secondResult = new AtomicReference<>();
        AtomicBoolean stillInterrupted = new AtomicBoolean();
        Thread second = Thread.ofVirtual().start(() -> {
            secondResult.set(schedule.events());
            stillInterrupted.set(Thread.currentThread().isInterrupted());
        });
        await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(2)).until(second::isAlive);

        second.interrupt();

        assertThat(second.join(Duration.ofSeconds(10))).as("returns while the download is held").isTrue();
        assertThat(stillInterrupted).isTrue();
        assertThat(secondResult.get().succeeded()).isZero();
        assertThat(secondResult.get().errors()).isEmpty();
        assertThat(server.count(EVENTS_DAY)).isEqualTo(1);

        release.countDown();
        assertThat(first.get(10, TimeUnit.SECONDS).succeeded()).isEqualTo(2);
    }

    @Test
    void aClearDuringAFetchDropsThatDay() throws Exception {
        holdDay("2026-09-19", "4331", "eventsday-2026-09-19-4331.json");
        Future<FeedResult> pass = passHeldAfter(2);

        schedule.clear();   // what a key change does
        release.countDown();
        pass.get(10, TimeUnit.SECONDS);

        assertThat(schedule.find(BUNDESLIGA_ITEM)).isEmpty();
        assertThat(schedule.status("4331").orElseThrow().events()).isZero();
        schedule.events();
        assertThat(server.count(EVENTS_DAY)).isEqualTo(6);
    }

    @Test
    void aLeagueForgottenDuringItsFetchIsNotCached() throws Exception {
        holdDay("2026-09-19", "4331", "eventsday-2026-09-19-4331.json");
        Future<FeedResult> pass = passHeldAfter(2);

        // What SportsCompetitions.remove does: settings first, then the schedule.
        settingsService.update(s -> s.withCompetitions(List.of(s.competitions().get(1))));
        schedule.forget("4331");
        release.countDown();

        FeedResult result = pass.get(10, TimeUnit.SECONDS);
        assertThat(result.feeds()).isEqualTo(1);
        assertThat(result.events()).noneMatch(e -> e.competitionKey().equals("thesportsdb:4331"));
        assertThat(schedule.find(BUNDESLIGA_ITEM)).isEmpty();
    }
}
