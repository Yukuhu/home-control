package dev.andre.homecontrol.sources.sports.calendar;

import dev.andre.homecontrol.sources.http.OutboundAddressPolicy;
import dev.andre.homecontrol.sources.sports.feed.FeedResult;
import dev.andre.homecontrol.sources.sports.feed.FeedStatus;
import dev.andre.homecontrol.sources.sports.settings.JsonFileSportsStore;
import dev.andre.homecontrol.sources.sports.feed.SportsEvent;
import dev.andre.homecontrol.sources.sports.settings.SportsProperties;
import dev.andre.homecontrol.sources.sports.settings.SportsSettings;
import dev.andre.homecontrol.sources.sports.settings.SportsSettingsService;
import dev.andre.homecontrol.sources.sports.settings.SportsTimeZones;
import dev.andre.homecontrol.sources.sports.ics.IcsCalendar;
import dev.andre.homecontrol.sources.sports.ics.IcsOccurrence;
import dev.andre.homecontrol.sources.sports.ics.IcsOccurrences;
import dev.andre.homecontrol.sources.sports.ics.IcsParser;
import dev.andre.homecontrol.storage.SecretStore;
import dev.andre.homecontrol.testsupport.MutableClock;
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
                new SportsProperties.Calendar(Duration.ofHours(6), Duration.ofSeconds(1), Duration.ofSeconds(2),
                5242880, 3, true),
                new SportsProperties.TheSportsDb(true, URI.create("http://127.0.0.1:9/api/v1/json"), "123",
                        Duration.ofHours(24), Duration.ofSeconds(1), Duration.ofSeconds(2), null, true));

        CalendarFetcher fetcher = new CalendarFetcher(properties.calendar(), new OutboundAddressPolicy(true));
        schedule = new CalendarSchedule(settingsService, fetcher, secrets, properties, zones, clock);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void loadsEveryCalendarOnce() {
        FeedResult result = schedule.events();

        assertThat(result.succeeded()).isEqualTo(2);
        assertThat(result.errors()).isEmpty();
        // The id older versions gave the event, from its UID and its start; pins made then are under it.
        assertThat(result.events()).anyMatch(e -> "ics:c-3f9a1c2b7d4e:069e696917c4a665".equals(e.formerItemId()));
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

        FeedResult result = schedule.events();
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

        FeedResult result = schedule.events();

        assertThat(result.succeeded()).isEqualTo(1);
        assertThat(result.errors()).containsExactly("Weekly sport: 127.0.0.1 has no calendar at that link");
    }

    @Test
    void aMissingSecretIsExplained() {
        given(secrets.secret("sports.calendar.c-3f9a1c2b7d4e")).willReturn(Optional.empty());

        FeedResult result = schedule.events();

        assertThat(result.errors()).contains(
                "Bundesliga 2026/27: The link for Bundesliga 2026/27 is missing; remove the calendar and add it again");
        assertThat(server.count("/private/token-abc123/bl.ics")).isZero();
    }

    @Test
    void htmlIsNotACalendar() {
        server.respond("/weekly.ics", 200, "text/html", "<html></html>");

        FeedResult result = schedule.events();

        assertThat(result.errors()).contains("Weekly sport: That link did not return a calendar (.ics)");
    }

    @Test
    void theStatusShowsTheLastPassRatherThanExpandingAgain() {
        int counted = schedule.status("c-3f9a1c2b7d4e").orElseThrow().events();
        assertThat(counted).isPositive();

        // A setup view later: the window would now hold none of these events, but the status is the last pass's.
        clock.advance(Duration.ofDays(30));

        assertThat(schedule.status("c-3f9a1c2b7d4e").orElseThrow().events()).isEqualTo(counted);
    }

    @Test
    void aMovedKickOffKeepsItsId() {
        IcsOccurrence planned = new IcsOccurrence("bl-1@fixtures.example", "A – B",
                Instant.parse("2026-09-19T13:30:00Z"), Instant.parse("2026-09-19T15:30:00Z"), null, null);
        IcsOccurrence moved = new IcsOccurrence("bl-1@fixtures.example", "A – B",
                Instant.parse("2026-09-19T16:30:00Z"), Instant.parse("2026-09-19T18:30:00Z"), null, null);

        assertThat(CalendarSchedule.toEvent("c-1", moved).itemId())
                .isEqualTo(CalendarSchedule.toEvent("c-1", planned).itemId());
    }

    @Test
    void eachOccurrenceOfASeriesHasItsOwnIdWhichAnOverrideKeeps() {
        Instant firstDue = Instant.parse("2026-09-20T13:00:00Z");
        Instant secondDue = Instant.parse("2026-09-27T13:00:00Z");
        IcsOccurrence first = new IcsOccurrence("series@fixtures.example", "Series", firstDue,
                firstDue.plus(Duration.ofHours(2)), null, firstDue);
        IcsOccurrence second = new IcsOccurrence("series@fixtures.example", "Series", secondDue,
                secondDue.plus(Duration.ofHours(2)), null, secondDue);
        IcsOccurrence secondMoved = new IcsOccurrence("series@fixtures.example", "Series moved",
                secondDue.plus(Duration.ofHours(3)), secondDue.plus(Duration.ofHours(5)), null, secondDue);

        assertThat(CalendarSchedule.toEvent("c-1", first).itemId())
                .isNotEqualTo(CalendarSchedule.toEvent("c-1", second).itemId());
        assertThat(CalendarSchedule.toEvent("c-1", secondMoved).itemId())
                .isEqualTo(CalendarSchedule.toEvent("c-1", second).itemId());
    }

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    private static List<IcsOccurrence> occurrences(IcsCalendar calendar, ZoneId household) {
        return IcsOccurrences.expand(calendar, household, Instant.parse("2026-09-18T00:00:00Z"),
                Instant.parse("2026-10-20T00:00:00Z"), Duration.ofMinutes(120)).occurrences();
    }

    @Test
    void aUidAFeedReusesNamesItsEventsByTheirStartsWhereverTheWindowIs() {
        // Against RFC 5545: one UID for two matches, and the second listed twice.
        IcsCalendar calendar = IcsParser.parse("""
                BEGIN:VCALENDAR
                BEGIN:VEVENT
                UID:reused@fixtures.example
                DTSTART:20260919T133000Z
                SUMMARY:A – B
                END:VEVENT
                BEGIN:VEVENT
                UID:reused@fixtures.example
                DTSTART:20260926T133000Z
                SUMMARY:C – D
                END:VEVENT
                BEGIN:VEVENT
                UID:reused@fixtures.example
                DTSTART:20260926T133000Z
                SUMMARY:C – D
                END:VEVENT
                END:VCALENDAR
                """);
        List<IcsOccurrence> found = occurrences(calendar, BERLIN);

        List<SportsEvent> both = CalendarSchedule.toEvents("c-1", calendar, found, BERLIN);
        // A week on, the first match has left the window: the second keeps its id, and with it its own pin.
        List<SportsEvent> later = CalendarSchedule.toEvents("c-1", calendar, found.subList(1, 3), BERLIN);

        assertThat(both.get(1).itemId()).isNotEqualTo(both.get(0).itemId()).isEqualTo(later.get(0).itemId());
        assertThat(both.get(2).itemId()).as("the same event twice is one").isEqualTo(both.get(1).itemId());
    }

    @Test
    void anOverrideIsNoSecondEventOfItsUid() {
        String series = """
                BEGIN:VCALENDAR
                BEGIN:VEVENT
                UID:series@fixtures.example
                DTSTART:20260920T130000Z
                RRULE:FREQ=WEEKLY;COUNT=3
                SUMMARY:Series
                END:VEVENT
                """;
        IcsCalendar planned = IcsParser.parse(series + "END:VCALENDAR\n");
        IcsCalendar moved = IcsParser.parse(series + """
                BEGIN:VEVENT
                UID:series@fixtures.example
                RECURRENCE-ID:20260927T130000Z
                DTSTART:20260927T160000Z
                SUMMARY:Series
                END:VEVENT
                END:VCALENDAR
                """);

        assertThat(ids(moved)).hasSize(3).isEqualTo(ids(planned));
    }

    private static List<String> ids(IcsCalendar calendar) {
        return CalendarSchedule.toEvents("c-1", calendar, occurrences(calendar, BERLIN), BERLIN).stream()
                .map(SportsEvent::itemId).toList();
    }

    @Test
    void anAllDayEventsFormerIdIsTheOneItHadInItsCalendarsZone() {
        IcsCalendar london = IcsParser.parse("""
                BEGIN:VCALENDAR
                X-WR-TIMEZONE:Europe/London
                BEGIN:VEVENT
                UID:matchday@fixtures.example
                DTSTART;VALUE=DATE:20260920
                SUMMARY:Matchday
                END:VEVENT
                END:VCALENDAR
                """);
        IcsOccurrence placed = occurrences(london, BERLIN).getFirst();
        // Older versions placed the date at midnight in London, and made the id from that start.
        IcsOccurrence asBefore = new IcsOccurrence(placed.uid(), placed.summary(),
                Instant.parse("2026-09-19T23:00:00Z"), Instant.parse("2026-09-20T23:00:00Z"),
                placed.allDayDate(), null);

        assertThat(CalendarSchedule.toEvents("c-1", london, List.of(placed), BERLIN).getFirst().formerItemId())
                .isEqualTo(CalendarSchedule.toEvent("c-1", asBefore).formerItemId());
    }

    @Test
    void anAllDaySeriesKeepsItsIdsWhenTheHouseholdZoneChanges() {
        IcsCalendar weekly = IcsParser.parse("""
                BEGIN:VCALENDAR
                BEGIN:VEVENT
                UID:matchdays@fixtures.example
                DTSTART;VALUE=DATE:20260920
                RRULE:FREQ=WEEKLY;COUNT=3
                SUMMARY:Matchday
                END:VEVENT
                END:VCALENDAR
                """);
        ZoneId newYork = ZoneId.of("America/New_York");

        List<String> inBerlin = CalendarSchedule.toEvents("c-1", weekly, occurrences(weekly, BERLIN), BERLIN)
                .stream().map(SportsEvent::itemId).toList();
        List<String> inNewYork = CalendarSchedule.toEvents("c-1", weekly, occurrences(weekly, newYork), newYork)
                .stream().map(SportsEvent::itemId).toList();

        assertThat(inBerlin).hasSize(3).isEqualTo(inNewYork);
    }

    @Test
    void findsItemsAndStatuses() {
        // Under the id older versions gave it: a pin made then still finds its event.
        Optional<SportsEvent> found = schedule.find("ics:c-3f9a1c2b7d4e:069e696917c4a665");
        assertThat(found).isPresent();
        assertThat(schedule.find(found.get().itemId())).as("under the id it has now").isEqualTo(found);
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

        FeedResult result = schedule.events();
        assertThat(result.events()).noneMatch(e -> e.competitionKey().equals("calendar:c-3f9a1c2b7d4e"));
    }

    private static String fixture(String name) throws IOException {
        try (var in = CalendarScheduleTest.class.getResourceAsStream("/fixtures/ics/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
