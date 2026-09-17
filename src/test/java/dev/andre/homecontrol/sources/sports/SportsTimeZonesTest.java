package dev.andre.homecontrol.sources.sports;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.ZoneId;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class SportsTimeZonesTest {

    private static SportsProperties properties(String timeZone) {
        return new SportsProperties(true, timeZone, 30, 10, 10, Duration.ofMinutes(120),
                new SportsProperties.Calendar(Duration.ofHours(6), 5, 15, 5242880, 3, false),
                new SportsProperties.TheSportsDb(true, URI.create("http://127.0.0.1:9/api/v1/json"), "123",
                        Duration.ofHours(24), 1, 2, null));
    }

    @Test
    void storedWinsOverConfiguredOverSystem() {
        SportsSettingsService settings = mock(SportsSettingsService.class);
        given(settings.current()).willReturn(SportsSettings.empty().withTimeZone("Europe/London"));
        SportsTimeZones zones = new SportsTimeZones(settings, properties("America/New_York"));
        assertThat(zones.effective()).isEqualTo(ZoneId.of("Europe/London"));

        SportsSettingsService noneStored = mock(SportsSettingsService.class);
        given(noneStored.current()).willReturn(SportsSettings.empty());
        SportsTimeZones configuredOnly = new SportsTimeZones(noneStored, properties("America/New_York"));
        assertThat(configuredOnly.effective()).isEqualTo(ZoneId.of("America/New_York"));
        assertThat(configuredOnly.chosen()).isTrue();

        SportsTimeZones neither = new SportsTimeZones(noneStored, properties(""));
        assertThat(neither.effective()).isEqualTo(ZoneId.systemDefault());
        assertThat(neither.chosen()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Europe/Berlin", "America/Argentina/Buenos_Aires", "UTC"})
    void parseAcceptsRegionIdsAndUtc(String id) {
        assertThat(SportsTimeZones.parse(id)).isPresent();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"GMT+2", "EST", "CET", "Mars/Base"})
    void parseRejectsOffsetsAndAbbreviations(String id) {
        assertThat(SportsTimeZones.parse(id)).isEmpty();
    }
}
