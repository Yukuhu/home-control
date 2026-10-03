package dev.andre.homecontrol.sources.sports.settings;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A sports duration is read in the unit its setting is about, and one the sports rails cannot use fails startup. */
class SportsPropertiesTest {

    private static final SportsProperties.Calendar CALENDAR = new SportsProperties.Calendar(Duration.ofHours(6),
            Duration.ofSeconds(5), Duration.ofSeconds(15), 5_242_880, 3, false);
    private static final SportsProperties.TheSportsDb THE_SPORTS_DB = theSportsDb(Duration.ofHours(24), null);

    private static SportsProperties.TheSportsDb theSportsDb(Duration fixturesTtl, Map<String, Duration> durations) {
        return new SportsProperties.TheSportsDb(true, URI.create("https://www.thesportsdb.com/api/v1/json"), "123",
                fixturesTtl, Duration.ofSeconds(5), Duration.ofSeconds(15), durations, false);
    }

    @Test
    void aDurationWithoutAUnitIsReadInTheUnitItsSettingIsAbout() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "home-control.sports.default-event-duration", "90",
                "home-control.sports.calendar.refresh", "6",
                "home-control.sports.the-sports-db.fixtures-ttl", "24"));

        SportsProperties properties = new Binder(source).bind("home-control.sports", SportsProperties.class).get();

        assertThat(properties.defaultEventDuration()).isEqualTo(Duration.ofMinutes(90));
        assertThat(properties.calendar().refresh()).isEqualTo(Duration.ofHours(6));
        assertThat(properties.theSportsDb().fixturesTtl()).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void aDurationUnderAMinuteNamesItsSetting() {
        Duration tooShort = Duration.ofMillis(90);
        assertThatThrownBy(() -> new SportsProperties(true, "", 30, 10, 10, tooShort, CALENDAR, THE_SPORTS_DB))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("home-control.sports.default-event-duration");
        assertThatThrownBy(() -> new SportsProperties.Calendar(Duration.ofMinutes(-5), Duration.ofSeconds(5),
                Duration.ofSeconds(15), 5_242_880, 3, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("home-control.sports.calendar.refresh");
        assertThatThrownBy(() -> theSportsDb(Duration.ZERO, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("home-control.sports.the-sports-db.fixtures-ttl");
    }

    @Test
    void aSportsDurationUnderAMinuteNamesTheSport() {
        // A bare 90 in the map is 90 ms: the map's values are read without a unit of their own.
        Map<String, Duration> durations = Map.of("soccer", Duration.ofMillis(90));

        assertThatThrownBy(() -> theSportsDb(Duration.ofHours(24), durations))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("home-control.sports.the-sports-db.sport-durations.soccer")
                .hasMessageContaining("90m");
    }
}
