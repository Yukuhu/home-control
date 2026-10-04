package dev.andre.homecontrol.sources.sports.settings;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The sports settings: defaults for missing parts, and the label and provider each competition key names. */
class SportsSettingsTest {

    private final SportsSettings settings = SportsSettings.empty()
            .withCalendars(List.of(new SportsSettings.CalendarEntry("c-1", "Bundesliga", "calendar.example.org", "dazn",
                    Instant.EPOCH)))
            .withCompetitions(List.of(new SportsSettings.CompetitionEntry("4331", "German Bundesliga", "Soccer", "Germany",
                    null, "sky", Instant.EPOCH)));

    @Test
    void missingListsAreEmptyAndAMissingKeyKindIsTheFreeKey() {
        SportsSettings bare = new SportsSettings(null, null, null, null);

        assertThat(bare.calendars()).isEmpty();
        assertThat(bare.competitions()).isEmpty();
        assertThat(bare.keyKind()).isEqualTo(SportsSettings.KeyKind.FREE);
        assertThat(bare.withKeyKind(SportsSettings.KeyKind.PERSONAL).keyKind()).isEqualTo(SportsSettings.KeyKind.PERSONAL);
    }

    @Test
    void aCalendarKeyNamesItsLabelAndProvider() {
        String key = SportsSettings.calendarKey("c-1");

        assertThat(settings.labelFor(key)).contains("Bundesliga");
        assertThat(settings.providerFor(key)).contains("dazn");
        assertThat(settings.labelFor(SportsSettings.calendarKey("c-gone"))).isEmpty();
    }

    @Test
    void aCompetitionKeyNamesItsLabelAndProvider() {
        String key = SportsSettings.competitionKey("4331");

        assertThat(settings.labelFor(key)).contains("German Bundesliga");
        assertThat(settings.providerFor(key)).contains("sky");
        assertThat(settings.providerFor(SportsSettings.competitionKey("9999"))).isEmpty();
    }

    @Test
    void noKeyOrAnUnknownKindOfKeyNamesNothing() {
        assertThat(settings.labelFor(null)).isEmpty();
        assertThat(settings.providerFor(null)).isEmpty();
        assertThat(settings.labelFor("other:4331")).isEmpty();
        assertThat(settings.providerFor("other:4331")).isEmpty();
    }
}
