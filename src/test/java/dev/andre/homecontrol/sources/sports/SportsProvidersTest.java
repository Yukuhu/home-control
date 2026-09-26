package dev.andre.homecontrol.sources.sports;

import dev.andre.homecontrol.core.content.StreamingProviders;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SportsProvidersTest {

    private static final SportsSettings SETTINGS = SportsSettings.empty()
            .withCalendars(List.of(new SportsSettings.CalendarEntry(
                    "c-3f9a1c2b7d4e", "Bundesliga 2026/27", "calendar.example.org", null, Instant.EPOCH)))
            .withCompetitions(List.of(new SportsSettings.CompetitionEntry(
                    "4331", "German Bundesliga", "Soccer", "Germany", null, null, Instant.EPOCH)));

    @Test
    void normalises() {
        assertThat(SportsProviders.normalise("dazn")).isEqualTo("dazn");
        assertThat(SportsProviders.normalise(" primevideo ")).isEqualTo("primevideo");
        assertThat(SportsProviders.normalise("")).isNull();
        assertThat(SportsProviders.normalise(null)).isNull();
        assertThatThrownBy(() -> SportsProviders.normalise("DAZN"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Unknown streaming service");
        assertThatThrownBy(() -> SportsProviders.normalise("sky")).hasMessage("Unknown streaming service");
    }

    @Test
    void appliesToCalendarsAndCompetitions() {
        SportsSettings applied = SportsProviders.apply(SETTINGS,
                Map.of("calendar:c-3f9a1c2b7d4e", "dazn", "thesportsdb:4331", "primevideo"));

        assertThat(applied.calendar("c-3f9a1c2b7d4e").orElseThrow().provider()).isEqualTo("dazn");
        assertThat(applied.competition("4331").orElseThrow().provider()).isEqualTo("primevideo");

        SportsSettings cleared = SportsProviders.apply(applied, Map.of("thesportsdb:4331", ""));
        assertThat(cleared.competition("4331").orElseThrow().provider()).isNull();
        assertThat(cleared.calendar("c-3f9a1c2b7d4e").orElseThrow().provider()).isEqualTo("dazn");
    }

    @Test
    void refusesUnknownKeys() {
        var preparedArg47_1 = Map.of("thesportsdb:9999", "dazn");
        assertThatThrownBy(() -> SportsProviders.apply(SETTINGS, preparedArg47_1))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Unknown competition");
        assertThatThrownBy(() -> SportsProviders.apply(SETTINGS, Map.of("calendar:c-ffffffffffff", "dazn")))
                .hasMessage("Unknown competition");
        assertThatThrownBy(() -> SportsProviders.apply(SETTINGS, Map.of("nope", "dazn")))
                .hasMessage("Unknown competition");
        assertThatThrownBy(() -> SportsProviders.apply(SETTINGS, Map.of())).hasMessage("Nothing to save");

        assertThat(SETTINGS.calendar("c-3f9a1c2b7d4e").orElseThrow().provider()).isNull();
        assertThat(SETTINGS.competition("4331").orElseThrow().provider()).isNull();
    }

    @Test
    void optionsFollowTheKnownProviders() {
        List<String> keys = SportsProviders.options().stream().map(SportsProviders.Option::key).toList();
        assertThat(keys).isEqualTo(List.copyOf(StreamingProviders.KNOWN.keySet()));
        assertThat(SportsProviders.displayName("dazn")).contains("DAZN");
    }
}
