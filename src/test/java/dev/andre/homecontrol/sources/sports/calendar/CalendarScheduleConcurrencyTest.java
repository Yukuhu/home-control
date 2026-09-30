package dev.andre.homecontrol.sources.sports.calendar;

import dev.andre.homecontrol.sources.http.OutboundAddressPolicy;
import dev.andre.homecontrol.sources.sports.feed.FeedResult;
import dev.andre.homecontrol.sources.sports.feed.SportsEvent;
import dev.andre.homecontrol.sources.sports.ics.IcsParser;
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
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
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
class CalendarScheduleConcurrencyTest {

    private static final String BUNDESLIGA = "c-3f9a1c2b7d4e";
    private static final String WEEKLY = "c-00000000000a";
    private static final String BUNDESLIGA_PATH = "/private/token-abc123/bl.ics";
    private static final String WEEKLY_PATH = "/weekly.ics";
    private static final String BUNDESLIGA_ITEM = "ics:c-3f9a1c2b7d4e:069e696917c4a665";

    @TempDir
    Path dir;

    private final CountDownLatch release = new CountDownLatch(1);
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    private FakeCalendarServer server;
    private SportsSettingsService settingsService;
    private MutableClock clock;
    private CalendarSchedule schedule;

    @BeforeEach
    void setUp() throws IOException {
        server = new FakeCalendarServer();
        server.respondFixture(BUNDESLIGA_PATH, "bundesliga.ics");
        server.respondFixture(WEEKLY_PATH, "recurring.ics");

        SecretStore secrets = mock(SecretStore.class);
        given(secrets.secret("sports.calendar." + BUNDESLIGA)).willReturn(Optional.of(server.url(BUNDESLIGA_PATH).toString()));
        given(secrets.secret("sports.calendar." + WEEKLY)).willReturn(Optional.of(server.url(WEEKLY_PATH).toString()));

        settingsService = new SportsSettingsService(new JsonFileSportsStore(dir.resolve("sports.json")),
                mock(ApplicationEventPublisher.class));
        settingsService.update(s -> s.withCalendars(List.of(
                new SportsSettings.CalendarEntry(BUNDESLIGA, "Bundesliga 2026/27", "127.0.0.1", null, Instant.EPOCH),
                new SportsSettings.CalendarEntry(WEEKLY, "Weekly sport", "127.0.0.1", null, Instant.EPOCH))));

        SportsTimeZones zones = mock(SportsTimeZones.class);
        given(zones.effective()).willReturn(ZoneId.of("Europe/Berlin"));
        clock = MutableClock.at(Instant.parse("2026-09-19T14:00:00Z"));
        // A held answer waits for the test, so the request timeout must outlast any test.
        SportsProperties properties = new SportsProperties(true, "", 30, 10, 10, Duration.ofMinutes(120),
                new SportsProperties.Calendar(Duration.ofHours(6), Duration.ofSeconds(1), Duration.ofSeconds(30),
                        5242880, 3, true),
                new SportsProperties.TheSportsDb(true, URI.create("http://127.0.0.1:9/api/v1/json"), "123",
                        Duration.ofHours(24), Duration.ofSeconds(1), Duration.ofSeconds(2), null, true));
        schedule = new CalendarSchedule(settingsService,
                new CalendarFetcher(properties.calendar(), new OutboundAddressPolicy(true)), secrets, properties,
                zones, clock);
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        pool.shutdownNow();
        server.close();
    }

    private Future<FeedResult> passHeldAt(String path, int requestsSoFar) {
        Future<FeedResult> pass = pool.submit(schedule::events);
        await().until(() -> server.count(path) == requestsSoFar);
        return pass;
    }

    private static void awaitStillWaiting(Future<?> waiter) {
        await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(2)).until(() -> !waiter.isDone());
    }

    @Test
    void aSecondPassJoinsTheRunningFetchInsteadOfSendingAnother() throws Exception {  // guard
        server.holdFixture(WEEKLY_PATH, "recurring.ics", release);
        Future<FeedResult> first = passHeldAt(WEEKLY_PATH, 1);
        Future<FeedResult> second = pool.submit(schedule::events);
        awaitStillWaiting(second);

        release.countDown();

        assertThat(first.get(10, TimeUnit.SECONDS).succeeded()).isEqualTo(2);
        assertThat(second.get(10, TimeUnit.SECONDS).succeeded()).isEqualTo(2);
        assertThat(server.count(WEEKLY_PATH)).isEqualTo(1);
    }

    @Test
    void aFirstLookupDuringTheFirstPassWaitsForIt() throws Exception {
        server.holdFixture(BUNDESLIGA_PATH, "bundesliga.ics", release);
        Future<FeedResult> pass = passHeldAt(BUNDESLIGA_PATH, 1);
        Future<Optional<SportsEvent>> found = pool.submit(() -> schedule.find(BUNDESLIGA_ITEM));
        awaitStillWaiting(found);

        release.countDown();

        assertThat(found.get(10, TimeUnit.SECONDS)).isPresent();
        pass.get(10, TimeUnit.SECONDS);
        assertThat(server.count(BUNDESLIGA_PATH)).isEqualTo(1);
    }

    @Test
    void aWaitingPassThatIsInterruptedStopsFetching() throws Exception {
        server.holdFixture(BUNDESLIGA_PATH, "bundesliga.ics", release);
        Future<FeedResult> first = passHeldAt(BUNDESLIGA_PATH, 1);
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
        assertThat(server.count(WEEKLY_PATH)).isZero();

        release.countDown();
        assertThat(first.get(10, TimeUnit.SECONDS).succeeded()).isEqualTo(2);
    }

    @Test
    void aCalendarRemovedDuringItsFetchStaysRemoved() throws Exception {
        server.holdFixture(BUNDESLIGA_PATH, "bundesliga.ics", release);
        Future<FeedResult> pass = passHeldAt(BUNDESLIGA_PATH, 1);

        // What SportsCalendars.remove does: settings first, then the schedule.
        settingsService.update(s -> s.withCalendars(List.of(s.calendars().get(1))));
        schedule.forget(BUNDESLIGA);
        release.countDown();

        FeedResult result = pass.get(10, TimeUnit.SECONDS);
        assertThat(result.feeds()).isEqualTo(1);
        assertThat(result.events()).noneMatch(e -> e.competitionKey().equals("calendar:" + BUNDESLIGA));
        assertThat(schedule.find(BUNDESLIGA_ITEM)).isEmpty();
        assertThat(schedule.status(BUNDESLIGA)).isEmpty();
    }

    @Test
    void aCalendarAddedDuringAPassIsPublishedFromItsFirstParse() throws Exception {
        server.holdFixture(WEEKLY_PATH, "recurring.ics", release);
        Future<FeedResult> pass = passHeldAt(WEEKLY_PATH, 1);

        // What SportsCalendars.add does: settings first, then the first parse.
        String added = "c-00000000000b";
        settingsService.update(s -> s.withCalendars(List.of(s.calendars().get(0), s.calendars().get(1),
                new SportsSettings.CalendarEntry(added, "Cup", "127.0.0.1", null, Instant.EPOCH))));
        schedule.prime(added, IcsParser.parse(fixture("bundesliga.ics")));
        release.countDown();

        FeedResult result = pass.get(10, TimeUnit.SECONDS);
        assertThat(result.feeds()).isEqualTo(3);
        assertThat(result.errors()).isEmpty();
        assertThat(result.events()).anyMatch(e -> e.competitionKey().equals("calendar:" + added));
    }

    @Test
    void statusAndForgetDoNotWaitForARunningFetch() throws Exception {  // guard
        schedule.events();
        clock.advance(Duration.ofHours(6));
        server.holdFixture(WEEKLY_PATH, "recurring.ics", release);
        Future<FeedResult> pass = passHeldAt(WEEKLY_PATH, 2);

        assertThat(pool.submit(() -> schedule.status(BUNDESLIGA)).get(5, TimeUnit.SECONDS)).isPresent();
        pool.submit(() -> schedule.forget(BUNDESLIGA)).get(5, TimeUnit.SECONDS);
        assertThat(pass).isNotDone();

        release.countDown();
        pass.get(10, TimeUnit.SECONDS);
    }

    private static String fixture(String name) throws IOException {
        try (InputStream in = CalendarScheduleConcurrencyTest.class.getResourceAsStream("/fixtures/ics/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
