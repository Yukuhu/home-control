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
        SportsSchedule schedule = new SportsSchedule(calendarSchedule);
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
        SportsSchedule schedule = new SportsSchedule(calendarSchedule);
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
        SportsSchedule schedule = new SportsSchedule(calendarSchedule);
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
}
