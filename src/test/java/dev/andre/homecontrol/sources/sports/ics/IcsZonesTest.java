package dev.andre.homecontrol.sources.sports.ics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class IcsZonesTest {

    @Test
    void resolvesIanaIds() {
        assertThat(IcsZones.resolve("Europe/Berlin")).contains(ZoneId.of("Europe/Berlin"));
        assertThat(IcsZones.resolve("\"Europe/Berlin\"")).contains(ZoneId.of("Europe/Berlin"));
        assertThat(IcsZones.resolve("UTC")).contains(ZoneId.of("UTC"));
    }

    @Test
    void resolvesPathLikeIds() {
        assertThat(IcsZones.resolve("/mozilla.org/20050126_1/Europe/Paris")).contains(ZoneId.of("Europe/Paris"));
        assertThat(IcsZones.resolve("/citadel.org/20190914_1/Europe/London")).contains(ZoneId.of("Europe/London"));
    }

    @ParameterizedTest
    @CsvSource({
            "W. Europe Standard Time, Europe/Berlin",
            "Central Europe Standard Time, Europe/Budapest",
            "Central European Standard Time, Europe/Warsaw",
            "Romance Standard Time, Europe/Paris",
            "GMT Standard Time, Europe/London",
            "Greenwich Standard Time, Atlantic/Reykjavik",
            "E. Europe Standard Time, Europe/Chisinau",
            "Eastern Standard Time, America/New_York",
            "Central Standard Time, America/Chicago",
            "Mountain Standard Time, America/Denver",
            "Pacific Standard Time, America/Los_Angeles",
            "Coordinated Universal Time, UTC",
    })
    void resolvesWindowsNames(String windows, String iana) {
        assertThat(IcsZones.resolve(windows)).isPresent();
        assertThat(IcsZones.resolve(windows).get().getRules()).isEqualTo(ZoneId.of(iana).getRules());
    }

    @Test
    void unknownOrBlankIsEmpty() {
        assertThat(IcsZones.resolve("Mars/Olympus_Mons")).isEmpty();
        assertThat(IcsZones.resolve("")).isEmpty();
        assertThat(IcsZones.resolve(null)).isEmpty();
    }
}
