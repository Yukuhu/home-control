package dev.andre.homecontrol.sources.sports;

import dev.andre.homecontrol.core.content.PinnedLinks;
import dev.andre.homecontrol.core.content.StreamingProviders;
import dev.andre.homecontrol.core.playback.PlayableRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class SportsItemsTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final Instant NOW = Instant.parse("2026-09-19T14:00:00Z");
    private static final Locale DE = Locale.forLanguageTag("de-DE");

    private static final SportsSettings SETTINGS = SportsSettings.empty().withCalendars(List.of(
            new SportsSettings.CalendarEntry("c-3f9a1c2b7d4e", "Bundesliga 2026/27", "calendar.example.org", null, Instant.EPOCH)));

    private static SportsEvent event(String start, String end) {
        return new SportsEvent("id", "calendar:c-3f9a1c2b7d4e", "Title", Instant.parse(start), Instant.parse(end),
                null, null, SportsEvent.Status.SCHEDULED);
    }

    @Test
    void liveEventsSayLive() {
        SportsEvent event = event("2026-09-19T13:30:00Z", "2026-09-19T15:25:00Z");

        var item = SportsItems.toItem(event, SETTINGS, BERLIN, DE, NOW);

        assertThat(item.subtitle()).isEqualTo("Live · Bundesliga 2026/27");
        assertThat(item.sourceId()).isEqualTo("sports");
        assertThat(item.kind().name()).isEqualTo("LIVE_EVENT");
        assertThat(item.startsAt()).isEqualTo(event.startsAt());
        assertThat(item.endsAt()).isEqualTo(event.endsAt());
        assertThat(item.artwork()).isNull();
        assertThat(item.playables()).isEmpty();
        assertThat(item.progress()).isNull();
    }

    @Test
    void upcomingEventsShowTheLocalTime() {
        SportsEvent event = event("2026-09-19T16:30:00Z", "2026-09-19T18:25:00Z");

        assertThat(SportsItems.subtitle(event, SETTINGS, BERLIN, DE, NOW)).isEqualTo("18:30 · Bundesliga 2026/27");

        String london = SportsItems.subtitle(event, SETTINGS, ZoneId.of("Europe/London"), DE, NOW);
        assertThat(london).startsWith("17:30");

        String expected = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.US).withZone(BERLIN)
                .format(event.startsAt());
        assertThat(SportsItems.subtitle(event, SETTINGS, BERLIN, Locale.US, NOW)).isEqualTo(expected + " · Bundesliga 2026/27");
    }

    @Test
    void laterAndEndedEvents() {
        SportsEvent laterEvent = event("2026-09-20T15:30:00Z", "2026-09-20T17:30:00Z");
        String expected = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(Locale.GERMANY).withZone(BERLIN)
                .format(laterEvent.startsAt()) + " · Bundesliga 2026/27";
        assertThat(SportsItems.subtitle(laterEvent, SETTINGS, BERLIN, DE, NOW)).isEqualTo(expected);

        SportsEvent endedEvent = event("2026-09-18T18:30:00Z", "2026-09-18T20:25:00Z");
        assertThat(SportsItems.subtitle(endedEvent, SETTINGS, BERLIN, DE, NOW)).isEqualTo("Ended · Bundesliga 2026/27");
    }

    @Test
    void allDayEvents() {
        SportsEvent today = new SportsEvent("id", "calendar:c-3f9a1c2b7d4e", "Title",
                Instant.parse("2026-09-18T22:00:00Z"), Instant.parse("2026-09-19T22:00:00Z"),
                LocalDate.of(2026, 9, 19), null, SportsEvent.Status.SCHEDULED);
        assertThat(SportsItems.subtitle(today, SETTINGS, BERLIN, DE, NOW)).isEqualTo("Today · Bundesliga 2026/27");

        SportsEvent later = new SportsEvent("id", "calendar:c-3f9a1c2b7d4e", "Title",
                Instant.parse("2026-09-19T22:00:00Z"), Instant.parse("2026-09-20T22:00:00Z"),
                LocalDate.of(2026, 9, 20), null, SportsEvent.Status.SCHEDULED);
        String expected = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(DE).format(LocalDate.of(2026, 9, 20));
        assertThat(SportsItems.subtitle(later, SETTINGS, BERLIN, DE, NOW)).isEqualTo(expected + " · Bundesliga 2026/27");
    }

    @Test
    void unknownCompetitionLabel() {
        SportsEvent event = new SportsEvent("id", "calendar:c-ffffffffffff", "Title",
                Instant.parse("2026-09-19T13:30:00Z"), Instant.parse("2026-09-19T15:25:00Z"),
                null, null, SportsEvent.Status.SCHEDULED);

        assertThat(SportsItems.subtitle(event, SETTINGS, BERLIN, DE, NOW)).isEqualTo("Live · Sports");
    }

    @Test
    void mappedCompetitionsNameTheProviderAsTheUsersSetting() {
        SportsSettings withCalendarProvider = SportsSettings.empty().withCalendars(List.of(
                new SportsSettings.CalendarEntry("c-3f9a1c2b7d4e", "Bundesliga 2026/27", "calendar.example.org", "dazn", Instant.EPOCH)));
        SportsEvent werder = event("2026-09-19T13:30:00Z", "2026-09-19T15:25:00Z");
        assertThat(SportsItems.subtitle(werder, withCalendarProvider, BERLIN, DE, NOW))
                .isEqualTo("Live · Bundesliga 2026/27 · DAZN (your setting)");

        SportsSettings withCompetitionProvider = SportsSettings.empty().withCompetitions(List.of(
                new SportsSettings.CompetitionEntry("4331", "German Bundesliga", "Soccer", "Germany", null, "primevideo", Instant.EPOCH)));
        SportsEvent tsdbEvent = new SportsEvent("tsdb:1", "thesportsdb:4331", "Title",
                Instant.parse("2026-09-19T16:30:00Z"), Instant.parse("2026-09-19T18:30:00Z"), null, null, SportsEvent.Status.SCHEDULED);
        assertThat(SportsItems.subtitle(tsdbEvent, withCompetitionProvider, BERLIN, DE, NOW))
                .isEqualTo("18:30 · German Bundesliga · Prime Video (your setting)");
    }

    @Test
    void unmappedCompetitionsNameNoProvider() {
        SportsEvent werder = event("2026-09-19T13:30:00Z", "2026-09-19T15:25:00Z");
        String subtitle = SportsItems.subtitle(werder, SETTINGS, BERLIN, DE, NOW);
        assertThat(subtitle).isEqualTo("Live · Bundesliga 2026/27");
        assertThat(subtitle.split(" · ")).hasSize(2);
        assertThat(subtitle).doesNotContain("DAZN");
    }

    @ParameterizedTest
    @ValueSource(strings = {"netflix", "primevideo", "dazn", "disneyplus", "appletvplus", "paramountplus", "wowtv", "joyn", "rtlplus"})
    void noSubtitleClaimsAProviderWithoutTheLabel(String providerKey) {
        SportsSettings settings = SportsSettings.empty().withCalendars(List.of(
                new SportsSettings.CalendarEntry("c-3f9a1c2b7d4e", "Bundesliga 2026/27", "calendar.example.org", providerKey, Instant.EPOCH)));
        SportsEvent werder = event("2026-09-19T13:30:00Z", "2026-09-19T15:25:00Z");
        String subtitle = SportsItems.subtitle(werder, settings, BERLIN, DE, NOW);
        String name = StreamingProviders.KNOWN.get(providerKey);
        if (subtitle.contains(name)) {
            assertThat(subtitle).endsWith("(your setting)");
        }
    }

    private static SportsSettings withProvider(String provider) {
        return SportsSettings.empty().withCalendars(List.of(new SportsSettings.CalendarEntry(
                "c-3f9a1c2b7d4e", "Bundesliga 2026/27", "calendar.example.org", provider, Instant.EPOCH)));
    }

    @Test
    void daznCompetitionsOpenTheDaznApp() {
        SportsEvent event = event("2026-09-19T13:30:00Z", "2026-09-19T15:25:00Z");

        assertThat(SportsItems.playables(event, withProvider("dazn"), null))
                .containsExactly(new PlayableRef.AppLink(URI.create("https://www.dazn.com/"), "dazn"));
        assertThat(SportsItems.playables(event, withProvider("primevideo"), null))
                .containsExactly(new PlayableRef.AppLink(URI.create("https://app.primevideo.com/"), "primevideo"));
        assertThat(SportsItems.playables(event, withProvider("netflix"), null))
                .containsExactly(new PlayableRef.AppLink(URI.create("https://www.netflix.com/browse"), "netflix"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"wowtv", "joyn", "disneyplus", "rtlplus"})
    void servicesWithoutAnAppHomeHaveNoPlayables(String provider) {
        SportsEvent event = event("2026-09-19T13:30:00Z", "2026-09-19T15:25:00Z");
        assertThat(SportsItems.playables(event, withProvider(provider), null)).isEmpty();
    }

    @Test
    void unmappedCompetitionHasNoPlayables() {
        SportsEvent event = event("2026-09-19T13:30:00Z", "2026-09-19T15:25:00Z");
        assertThat(SportsItems.playables(event, SETTINGS, null)).isEmpty();
    }

    @Test
    void aPinnedEventLinkWins() {
        SportsEvent target = new SportsEvent("tsdb:2508361", "calendar:c-3f9a1c2b7d4e", "Title",
                Instant.parse("2026-09-19T13:30:00Z"), Instant.parse("2026-09-19T15:30:00Z"), null, null, SportsEvent.Status.SCHEDULED);
        PlayableRef.AppLink pinnedLink = new PlayableRef.AppLink(
                URI.create("https://www.dazn.com/de-DE/fixture/ContentId:1a2b3c4d5e6f7g8h9i0j"), "dazn");
        PinnedLinks pinnedLinks = mock(PinnedLinks.class);
        given(pinnedLinks.linkFor("sports", "tsdb:2508361")).willReturn(Optional.of(pinnedLink));
        given(pinnedLinks.linkFor("sports", "tsdb:other")).willReturn(Optional.empty());

        assertThat(SportsItems.playables(target, withProvider("dazn"), pinnedLinks)).containsExactly(pinnedLink);
        assertThat(SportsItems.playables(target, SETTINGS, pinnedLinks)).containsExactly(pinnedLink);

        SportsEvent other = new SportsEvent("tsdb:other", "calendar:c-3f9a1c2b7d4e", "Title",
                Instant.parse("2026-09-19T13:30:00Z"), Instant.parse("2026-09-19T15:30:00Z"), null, null, SportsEvent.Status.SCHEDULED);
        assertThat(SportsItems.playables(other, withProvider("dazn"), pinnedLinks))
                .containsExactly(new PlayableRef.AppLink(URI.create("https://www.dazn.com/"), "dazn"));
    }

    @Test
    void endedEventsStillOpenTheApp() {
        SportsEvent bayern = new SportsEvent("id", "calendar:c-3f9a1c2b7d4e", "Bayern",
                Instant.parse("2026-09-18T18:30:00Z"), Instant.parse("2026-09-18T20:25:00Z"),
                null, null, SportsEvent.Status.SCHEDULED);
        assertThat(SportsItems.playables(bayern, withProvider("dazn"), null))
                .containsExactly(new PlayableRef.AppLink(URI.create("https://www.dazn.com/"), "dazn"));
    }
}
