package dev.andre.homecontrol.sources.sports;

import dev.andre.homecontrol.core.content.ContentChangedEvent;
import dev.andre.homecontrol.core.content.ContentSources;
import dev.andre.homecontrol.core.content.PinOffers;
import dev.andre.homecontrol.core.content.PinnedLinks;
import dev.andre.homecontrol.core.content.SourcePreferences;
import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.PlayableRef;
import dev.andre.homecontrol.sources.pinned.JsonFilePinStore;
import dev.andre.homecontrol.sources.pinned.Pin;
import dev.andre.homecontrol.sources.pinned.PinnedProperties;
import dev.andre.homecontrol.sources.pinned.PinnedShortcuts;
import dev.andre.homecontrol.sources.sports.calendar.CalendarSchedule;
import dev.andre.homecontrol.sources.sports.ics.IcsCalendar;
import dev.andre.homecontrol.sources.sports.ics.IcsOccurrence;
import dev.andre.homecontrol.sources.sports.ics.IcsOccurrences;
import dev.andre.homecontrol.sources.sports.ics.IcsParser;
import dev.andre.homecontrol.sources.sports.thesportsdb.TheSportsDbEventMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class SportsPinUpgradeTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final Instant NOW = Instant.parse("2026-09-19T14:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String DAZN_EVENT_LINK = "https://www.dazn.com/de-DE/fixture/ContentId:1a2b3c4d5e6f7g8h9i0j";

    @TempDir
    Path dir;

    private PinnedShortcuts pinnedShortcuts;
    private List<Object> published;
    private SportsSettingsService settingsService;
    private SportsContentSource source;

    private static String fixture(String subdir, String name) {
        try (InputStream in = SportsPinUpgradeTest.class.getResourceAsStream("/fixtures/" + subdir + "/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<SportsEvent> buildEvents() {
        List<SportsEvent> events = new ArrayList<>();
        IcsCalendar calendar = IcsParser.parse(fixture("ics", "bundesliga.ics"));
        IcsOccurrences.Result result = IcsOccurrences.expand(calendar, BERLIN,
                NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(8)), Duration.ofMinutes(120));
        for (IcsOccurrence occurrence : result.occurrences()) {
            events.add(CalendarSchedule.toEvent("c-3f9a1c2b7d4e", occurrence));
        }
        events.addAll(tsdbEvents("4331", "eventsday-2026-09-18-4331.json", "eventsday-2026-09-19-4331.json"));
        events.addAll(tsdbEvents("4328", "eventsday-2026-09-19-4328.json"));
        return events;
    }

    private static List<SportsEvent> tsdbEvents(String leagueId, String... fixtureNames) {
        List<SportsEvent> events = new ArrayList<>();
        for (String fixtureName : fixtureNames) {
            JsonNode root = MAPPER.readTree(fixture("thesportsdb", fixtureName));
            for (JsonNode node : root.path("events")) {
                TheSportsDbEventMapper.toEvent(node, leagueId, null, BERLIN,
                        sport -> SportsProperties.TheSportsDb.DEFAULT_DURATIONS.getOrDefault(
                                sport == null ? "" : sport.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""),
                                Duration.ofMinutes(120))).ifPresent(events::add);
            }
        }
        return events;
    }

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        List<SportsEvent> events = buildEvents();

        SportsSchedule schedule = mock(SportsSchedule.class);
        given(schedule.hasFeeds()).willReturn(true);
        given(schedule.events()).willReturn(events);
        for (SportsEvent event : events) {
            given(schedule.find(event.itemId())).willReturn(Optional.of(event));
        }

        settingsService = mock(SportsSettingsService.class);
        given(settingsService.current()).willReturn(defaultSettings());

        SportsTimeZones zones = mock(SportsTimeZones.class);
        given(zones.effective()).willReturn(BERLIN);

        SportsProperties properties = new SportsProperties(true, "", 30, 10, 10, Duration.ofMinutes(120),
                new SportsProperties.Calendar(Duration.ofHours(6), 5, 15, 5242880, 3, false),
                new SportsProperties.TheSportsDb(true, URI.create("https://www.thesportsdb.com/api/v1/json"), "123",
                        Duration.ofHours(24), 5, 15, null));

        published = new ArrayList<>();
        ApplicationEventPublisher publisher = published::add;

        JsonFilePinStore store = new JsonFilePinStore(dir.resolve("pinned.json"));
        PinnedProperties pinnedProperties = new PinnedProperties(true, 200);

        ObjectProvider<ContentSources> sourcesProvider = mock(ObjectProvider.class);
        pinnedShortcuts = new PinnedShortcuts(store, pinnedProperties, publisher, CLOCK, new SecureRandom(), sourcesProvider);

        ObjectProvider<PinnedLinks> pinnedLinksProvider = mock(ObjectProvider.class);
        given(pinnedLinksProvider.getIfAvailable()).willReturn(pinnedShortcuts);

        source = new SportsContentSource(settingsService, schedule, zones,
                () -> SourcePreferences.defaults("de-DE", "DE"), CLOCK, pinnedLinksProvider, properties);

        ContentSources contentSources = new ContentSources(List.of(source));
        given(sourcesProvider.getIfAvailable()).willReturn(contentSources);
    }

    private static SportsSettings defaultSettings() {
        return SportsSettings.empty()
                .withCalendars(List.of(new SportsSettings.CalendarEntry(
                        "c-3f9a1c2b7d4e", "Bundesliga 2026/27", "calendar.example.org", "dazn", Instant.EPOCH)))
                .withCompetitions(List.of(
                        new SportsSettings.CompetitionEntry("4331", "German Bundesliga", "Soccer", "Germany", null, "dazn", Instant.EPOCH),
                        new SportsSettings.CompetitionEntry("4328", "English Premier League", "Soccer", "England", null, null, Instant.EPOCH)));
    }

    @Test
    void pastingAnEventLinkUpgradesTheEvent() {
        Pin pin = pinnedShortcuts.addUpgrade(DAZN_EVENT_LINK, "sports/tsdb:2508361");

        assertThat(pin.title()).isEqualTo("Werder Bremen vs Augsburg");
        assertThat(pin.kind().name()).isEqualTo("LIVE_EVENT");
        assertThat(pin.artwork().toString()).endsWith("ppxv5f1688630656.jpg/small");
        assertThat(pin.subtitle()).isEqualTo("DAZN");
        assertThat(pin.upgradeOf()).isEqualTo("sports/tsdb:2508361");

        List<ContentChangedEvent> changed = published.stream()
                .filter(ContentChangedEvent.class::isInstance).map(ContentChangedEvent.class::cast).toList();
        assertThat(changed).contains(new ContentChangedEvent("pinned"), new ContentChangedEvent("sports"));

        ContentItem item = source.item("tsdb:2508361").orElseThrow();
        assertThat(item.playables()).containsExactly(new PlayableRef.AppLink(URI.create(DAZN_EVENT_LINK), "dazn"));
        assertThat(PinOffers.offer(item)).isEmpty();

        ContentItem railItem = source.rail("live-today").items().stream()
                .filter(i -> i.id().equals("tsdb:2508361")).findFirst().orElseThrow();
        assertThat(railItem).isEqualTo(item);
    }

    @Test
    void unmappedEventsCanBeUpgradedToo() {
        pinnedShortcuts.addUpgrade(DAZN_EVENT_LINK, "sports/tsdb:2601002");

        ContentItem item = source.item("tsdb:2601002").orElseThrow();
        assertThat(item.playables()).containsExactly(new PlayableRef.AppLink(URI.create(DAZN_EVENT_LINK), "dazn"));
    }

    @Test
    void icsEventIdsWork() {
        Pin pin = pinnedShortcuts.addUpgrade(DAZN_EVENT_LINK, "sports/ics:c-3f9a1c2b7d4e:069e696917c4a665");

        assertThat(pin.upgradeOf()).isEqualTo("sports/ics:c-3f9a1c2b7d4e:069e696917c4a665");
        ContentItem item = source.item("ics:c-3f9a1c2b7d4e:069e696917c4a665").orElseThrow();
        assertThat(item.playables()).containsExactly(new PlayableRef.AppLink(URI.create(DAZN_EVENT_LINK), "dazn"));
    }

    @Test
    void upgradedEventsStayUpgradedWhenTheMappingChanges() {
        pinnedShortcuts.addUpgrade(DAZN_EVENT_LINK, "sports/tsdb:2508361");

        given(settingsService.current()).willReturn(SportsSettings.empty()
                .withCalendars(defaultSettings().calendars())
                .withCompetitions(List.of(
                        new SportsSettings.CompetitionEntry("4331", "German Bundesliga", "Soccer", "Germany", null, null, Instant.EPOCH),
                        new SportsSettings.CompetitionEntry("4328", "English Premier League", "Soccer", "England", null, null, Instant.EPOCH))));

        ContentItem item = source.item("tsdb:2508361").orElseThrow();
        assertThat(item.playables()).containsExactly(new PlayableRef.AppLink(URI.create(DAZN_EVENT_LINK), "dazn"));
    }
}
