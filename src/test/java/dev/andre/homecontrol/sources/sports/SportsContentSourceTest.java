package dev.andre.homecontrol.sources.sports;

import dev.andre.homecontrol.core.content.SourcePreferences;
import dev.andre.homecontrol.sources.sports.calendar.CalendarSchedule;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class SportsContentSourceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-19T14:00:00Z"), ZoneOffset.UTC);

    private static SportsSettingsService settingsWith(List<SportsSettings.CalendarEntry> calendars) {
        SportsSettingsService service = mock(SportsSettingsService.class);
        given(service.current()).willReturn(SportsSettings.empty().withCalendars(calendars));
        return service;
    }

    @Test
    void isUnavailableWithoutFeeds() {
        CalendarSchedule calendarSchedule = mock(CalendarSchedule.class);
        given(calendarSchedule.hasCalendars()).willReturn(false);
        SportsSchedule schedule = new SportsSchedule(calendarSchedule, null);
        SportsTimeZones zones = mock(SportsTimeZones.class);
        given(zones.effective()).willReturn(ZoneId.of("Europe/Berlin"));

        SportsContentSource source = new SportsContentSource(settingsWith(List.of()), schedule, zones,
                () -> SourcePreferences.defaults("de-DE", "DE"), CLOCK);

        assertThat(source.available()).isFalse();
        assertThat(source.rails()).isEmpty();
    }

    @Test
    void availableWithACalendarButNoRailsYet() {
        CalendarSchedule calendarSchedule = mock(CalendarSchedule.class);
        given(calendarSchedule.hasCalendars()).willReturn(true);
        SportsSchedule schedule = new SportsSchedule(calendarSchedule, null);
        SportsTimeZones zones = mock(SportsTimeZones.class);
        given(zones.effective()).willReturn(ZoneId.of("Europe/Berlin"));

        SportsContentSource source = new SportsContentSource(settingsWith(List.of(
                new SportsSettings.CalendarEntry("c-3f9a1c2b7d4e", "Bundesliga", "example.org", null, Instant.EPOCH))),
                schedule, zones, () -> SourcePreferences.defaults("de-DE", "DE"), CLOCK);

        assertThat(source.available()).isTrue();
        assertThat(source.rails()).isEmpty();
        assertThatThrownBy(() -> source.rail("live-today")).isInstanceOf(IllegalArgumentException.class);
        assertThat(source.id()).isEqualTo("sports");
        assertThat(source.displayName()).isEqualTo("Sports");
        assertThat(source.searchable()).isFalse();
    }

    @Test
    void readsItems() {
        CalendarSchedule calendarSchedule = mock(CalendarSchedule.class);
        given(calendarSchedule.hasCalendars()).willReturn(true);
        SportsSchedule schedule = new SportsSchedule(calendarSchedule, null);
        SportsTimeZones zones = mock(SportsTimeZones.class);
        given(zones.effective()).willReturn(ZoneId.of("Europe/Berlin"));

        SportsEvent werder = new SportsEvent("ics:c-3f9a1c2b7d4e:069e696917c4a665", "calendar:c-3f9a1c2b7d4e",
                "SV Werder Bremen – FC Augsburg", Instant.parse("2026-09-19T13:30:00Z"), Instant.parse("2026-09-19T15:25:00Z"),
                null, null, SportsEvent.Status.SCHEDULED);
        given(calendarSchedule.find("ics:c-3f9a1c2b7d4e:069e696917c4a665")).willReturn(Optional.of(werder));
        given(calendarSchedule.find("ics:nope")).willReturn(Optional.empty());

        SportsContentSource source = new SportsContentSource(settingsWith(List.of(
                new SportsSettings.CalendarEntry("c-3f9a1c2b7d4e", "Bundesliga 2026/27", "example.org", null, Instant.EPOCH))),
                schedule, zones, () -> SourcePreferences.defaults("de-DE", "DE"), CLOCK);

        assertThat(source.item("ics:c-3f9a1c2b7d4e:069e696917c4a665")).isPresent()
                .get().satisfies(item -> assertThat(item.subtitle()).isEqualTo("Live · Bundesliga 2026/27"));
        assertThat(source.item("ics:nope")).isEmpty();
    }

    @Test
    void availableWithOnlyACompetition() {
        CalendarSchedule calendarSchedule = mock(CalendarSchedule.class);
        given(calendarSchedule.hasCalendars()).willReturn(false);
        dev.andre.homecontrol.sources.sports.thesportsdb.TheSportsDbSchedule competitions =
                mock(dev.andre.homecontrol.sources.sports.thesportsdb.TheSportsDbSchedule.class);
        given(competitions.hasCompetitions()).willReturn(true);
        SportsSchedule withCompetition = new SportsSchedule(calendarSchedule, competitions);
        SportsTimeZones zones = mock(SportsTimeZones.class);
        given(zones.effective()).willReturn(ZoneId.of("Europe/Berlin"));

        SportsContentSource source = new SportsContentSource(settingsWith(List.of()), withCompetition, zones,
                () -> SourcePreferences.defaults("de-DE", "DE"), CLOCK);
        assertThat(source.available()).isTrue();

        SportsSchedule withoutCompetitions = new SportsSchedule(calendarSchedule, null);
        SportsContentSource source2 = new SportsContentSource(settingsWith(List.of()), withoutCompetitions, zones,
                () -> SourcePreferences.defaults("de-DE", "DE"), CLOCK);
        assertThat(source2.available()).isFalse();
    }

    @Test
    void readsTheSportsDbItems() {
        CalendarSchedule calendarSchedule = mock(CalendarSchedule.class);
        given(calendarSchedule.hasCalendars()).willReturn(true);
        dev.andre.homecontrol.sources.sports.thesportsdb.TheSportsDbSchedule competitions =
                mock(dev.andre.homecontrol.sources.sports.thesportsdb.TheSportsDbSchedule.class);
        SportsSchedule schedule = new SportsSchedule(calendarSchedule, competitions);
        SportsTimeZones zones = mock(SportsTimeZones.class);
        given(zones.effective()).willReturn(ZoneId.of("Europe/Berlin"));

        java.net.URI thumbSmall = java.net.URI.create(
                "https://r2.thesportsdb.com/images/media/event/thumb/ppxv5f1688630656.jpg/small");
        SportsEvent event = new SportsEvent("tsdb:2508361", "thesportsdb:4331", "Werder Bremen vs Augsburg",
                Instant.parse("2026-09-19T13:30:00Z"), Instant.parse("2026-09-19T15:30:00Z"), null, thumbSmall,
                SportsEvent.Status.SCHEDULED);
        given(competitions.find("tsdb:2508361")).willReturn(Optional.of(event));

        SportsSettingsService settingsService = mock(SportsSettingsService.class);
        given(settingsService.current()).willReturn(SportsSettings.empty().withCompetitions(List.of(
                new SportsSettings.CompetitionEntry("4331", "German Bundesliga", "Soccer", "Germany", null, null, Instant.EPOCH))));

        SportsContentSource source = new SportsContentSource(settingsService, schedule, zones,
                () -> SourcePreferences.defaults("de-DE", "DE"), CLOCK);

        assertThat(source.item("tsdb:2508361")).isPresent().get().satisfies(item -> {
            assertThat(item.subtitle()).isEqualTo("Live · German Bundesliga");
            assertThat(item.artwork()).isEqualTo(thumbSmall);
        });
    }
}
