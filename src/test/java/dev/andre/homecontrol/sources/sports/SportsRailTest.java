package dev.andre.homecontrol.sources.sports;

import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.core.content.PinnedLinks;
import dev.andre.homecontrol.core.content.SourcePreferences;
import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.PlayableRef;
import dev.andre.homecontrol.sources.sports.calendar.CalendarSchedule;
import dev.andre.homecontrol.sources.sports.ics.IcsCalendar;
import dev.andre.homecontrol.sources.sports.ics.IcsOccurrence;
import dev.andre.homecontrol.sources.sports.ics.IcsOccurrences;
import dev.andre.homecontrol.sources.sports.ics.IcsParser;
import dev.andre.homecontrol.sources.sports.thesportsdb.TheSportsDbEventMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class SportsRailTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final Instant NOW = Instant.parse("2026-09-19T14:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private SportsSchedule schedule;
    private SportsSettingsService settingsService;
    private SportsTimeZones zones;
    private SportsContentSource source;

    private static String fixture(String dir, String name) {
        try (InputStream in = SportsRailTest.class.getResourceAsStream("/fixtures/" + dir + "/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<SportsEvent> icsEvents() {
        IcsCalendar calendar = IcsParser.parse(fixture("ics", "bundesliga.ics"));
        IcsOccurrences.Result result = IcsOccurrences.expand(calendar, BERLIN,
                NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(8)), Duration.ofMinutes(120));
        List<SportsEvent> events = new ArrayList<>();
        for (IcsOccurrence occurrence : result.occurrences()) {
            events.add(CalendarSchedule.toEvent("c-3f9a1c2b7d4e", occurrence));
        }
        return events;
    }

    private static List<SportsEvent> tsdbEvents(String leagueId, URI badge, String... fixtureNames) {
        List<SportsEvent> events = new ArrayList<>();
        for (String fixtureName : fixtureNames) {
            JsonNode root = MAPPER.readTree(fixture("thesportsdb", fixtureName));
            for (JsonNode node : root.path("events")) {
                TheSportsDbEventMapper.toEvent(node, leagueId, badge, BERLIN,
                        sport -> SportsProperties.TheSportsDb.DEFAULT_DURATIONS.getOrDefault(
                                sport == null ? "" : sport.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", ""),
                                Duration.ofMinutes(120))).ifPresent(events::add);
            }
        }
        return events;
    }

    @BeforeEach
    void setUp() {
        List<SportsEvent> events = new ArrayList<>();
        events.addAll(icsEvents());
        events.addAll(tsdbEvents("4331", null, "eventsday-2026-09-18-4331.json", "eventsday-2026-09-19-4331.json"));
        events.addAll(tsdbEvents("4328", null, "eventsday-2026-09-19-4328.json"));

        schedule = mock(SportsSchedule.class);
        given(schedule.hasFeeds()).willReturn(true);
        given(schedule.events()).willReturn(events);
        for (SportsEvent event : events) {
            given(schedule.find(event.itemId())).willReturn(Optional.of(event));
        }

        settingsService = mock(SportsSettingsService.class);
        given(settingsService.current()).willReturn(SportsSettings.empty()
                .withCalendars(List.of(new SportsSettings.CalendarEntry(
                        "c-3f9a1c2b7d4e", "Bundesliga 2026/27", "calendar.example.org", "dazn", Instant.EPOCH)))
                .withCompetitions(List.of(
                        new SportsSettings.CompetitionEntry("4331", "German Bundesliga", "Soccer", "Germany", null, "dazn", Instant.EPOCH),
                        new SportsSettings.CompetitionEntry("4328", "English Premier League", "Soccer", "England", null, null, Instant.EPOCH))));

        zones = mock(SportsTimeZones.class);
        given(zones.effective()).willReturn(BERLIN);

        SportsProperties properties = new SportsProperties(true, "", 30, 10, 10, Duration.ofMinutes(120),
                new SportsProperties.Calendar(Duration.ofHours(6), 5, 15, 5242880, 3, false),
                new SportsProperties.TheSportsDb(true, URI.create("https://www.thesportsdb.com/api/v1/json"), "123",
                        Duration.ofHours(24), 5, 15, null));

        source = new SportsContentSource(settingsService, schedule, zones,
                () -> SourcePreferences.defaults("de-DE", "DE"), CLOCK, noPinnedLinks(), properties);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<PinnedLinks> noPinnedLinks() {
        ObjectProvider<PinnedLinks> provider = mock(ObjectProvider.class);
        given(provider.getIfAvailable()).willReturn(null);
        return provider;
    }

    @Test
    void offersTheRailOnlyWhenAvailable() {
        assertThat(source.rails()).containsExactly(
                new dev.andre.homecontrol.core.content.RailDescriptor("sports", "live-today", "Live now / Today"));

        given(schedule.hasFeeds()).willReturn(false);
        assertThat(source.rails()).isEmpty();
        assertThat(source.defaultRefreshInterval()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void buildsLiveNowAndToday() {
        List<String> ids = source.rail("live-today").items().stream().map(ContentItem::id).toList();

        assertThat(ids).containsExactly(
                "ics:c-3f9a1c2b7d4e:069e696917c4a665",
                "tsdb:2508361",
                "tsdb:2508362",
                "tsdb:2601002",
                "tsdb:2601003",
                "tsdb:2508365",
                "ics:c-3f9a1c2b7d4e:47f47c4a3b4c720d",
                "tsdb:2508366");
    }

    @Test
    void subtitlesAndPlayables() {
        var items = source.rail("live-today").items();
        ContentItem werder = byId(items, "tsdb:2508361");
        assertThat(werder.subtitle()).isEqualTo("Live · German Bundesliga · DAZN (your setting)");
        assertThat(werder.playables()).containsExactly(new PlayableRef.AppLink(URI.create("https://www.dazn.com/"), "dazn"));
        assertThat(werder.kind().name()).isEqualTo("LIVE_EVENT");
        assertThat(werder.startsAt()).isEqualTo(Instant.parse("2026-09-19T13:30:00Z"));
        assertThat(werder.endsAt()).isEqualTo(Instant.parse("2026-09-19T15:30:00Z"));
        assertThat(werder.artwork().toString()).endsWith("ppxv5f1688630656.jpg/small");

        ContentItem liverpool = byId(items, "tsdb:2601002");
        assertThat(liverpool.subtitle()).isEqualTo("Live · English Premier League");
        assertThat(liverpool.playables()).isEmpty();

        ContentItem brighton = byId(items, "tsdb:2601003");
        assertThat(brighton.subtitle()).isEqualTo("Today · English Premier League");

        ContentItem heidenheim = byId(items, "tsdb:2508366");
        assertThat(heidenheim.subtitle()).isEqualTo("20:30 · German Bundesliga · DAZN (your setting)");
    }

    @Test
    void failuresSurfaceAsRailErrors() {
        ContentSourceException failure = new ContentSourceException("Bundesliga 2026/27: calendar.example.org answered HTTP 500");
        given(schedule.events()).willThrow(failure);

        assertThatThrownBy(() -> source.rail("live-today")).isSameAs(failure);
    }

    @Test
    void unknownRail() {
        assertThatThrownBy(() -> source.rail("today"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Sports has no rail 'today'");
    }

    @Test
    void theRailNeverClaimsAPersonalFeedOrAuthoritativeRights() {
        var rail = source.rail("live-today");
        assertThat(rail.descriptor().title()).doesNotContain("For you", "Recommended", "Continue");

        for (ContentItem item : rail.items()) {
            assertThat(item.subtitle()).doesNotContain("For you", "Recommended", "Continue", "Official", "on DAZN");
            if (item.subtitle() != null && item.subtitle().contains("DAZN")) {
                assertThat(item.subtitle()).endsWith("(your setting)");
            }
        }
    }

    @Test
    void itemReadsMatchTheRail() {
        ContentItem railItem = byId(source.rail("live-today").items(), "tsdb:2508361");
        ContentItem read = source.item("tsdb:2508361").orElseThrow();
        assertThat(read).isEqualTo(railItem);
    }

    private static ContentItem byId(List<ContentItem> items, String id) {
        return items.stream().filter(i -> i.id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("No item " + id + " in " + items.stream().map(ContentItem::id).toList()));
    }
}
