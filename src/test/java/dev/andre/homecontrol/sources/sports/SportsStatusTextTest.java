package dev.andre.homecontrol.sources.sports;

import dev.andre.homecontrol.sources.sports.feed.FeedStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/** The one-line feed status on the setup page, singular and plural. */
class SportsStatusTextTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final Instant FETCHED = Instant.parse("2026-09-19T14:02:00Z");

    @Test
    void notLoadedYet() {
        assertThat(SportsSetupSection.statusText(null, "Bundesliga", BERLIN)).isEqualTo("Not loaded yet");
        assertThat(SportsSetupSection.statusText(new FeedStatus(null, 0, null, 0, 0, 0), "Bundesliga", BERLIN))
                .isEqualTo("Not loaded yet");
    }

    @Test
    void singularNotes() {
        assertThat(SportsSetupSection.statusText(new FeedStatus(FETCHED, 23, null, 1, 1, 1), "Bundesliga", BERLIN))
                .isEqualTo("23 events · updated 16:02; 1 repeating event uses rules Home Control shows only once"
                        + "; 1 unknown time zone; 1 unreadable event");
    }

    @Test
    void pluralNotes() {
        assertThat(SportsSetupSection.statusText(new FeedStatus(FETCHED, 0, null, 2, 3, 4), "Bundesliga", BERLIN))
                .isEqualTo("0 events · updated 16:02; 2 repeating events use rules Home Control shows only once"
                        + "; 3 unknown time zones; 4 unreadable events");
    }

    @Test
    void errorsDropTheRepeatedLabel() {
        assertThat(SportsSetupSection.statusText(
                new FeedStatus(null, 0, "Bundesliga: host answered HTTP 500", 0, 0, 0), "Bundesliga", BERLIN))
                .isEqualTo("Could not refresh: host answered HTTP 500");
    }
}
