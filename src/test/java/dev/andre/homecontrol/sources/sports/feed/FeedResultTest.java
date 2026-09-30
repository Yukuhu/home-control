package dev.andre.homecontrol.sources.sports.feed;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FeedResultTest {

    private static final SportsEvent MATCH = new SportsEvent("ics:c-3f9a1c2b7d4e:069e696917c4a665",
            "calendar:c-3f9a1c2b7d4e", "SV Werder Bremen – FC Augsburg", Instant.parse("2026-09-19T13:30:00Z"),
            Instant.parse("2026-09-19T15:25:00Z"), null, null, SportsEvent.Status.SCHEDULED);
    private static final SportsEvent FIXTURE = new SportsEvent("tsdb:2601002", "thesportsdb:4328",
            "Arsenal vs Chelsea", Instant.parse("2026-09-19T16:30:00Z"), Instant.parse("2026-09-19T18:30:00Z"),
            null, null, SportsEvent.Status.SCHEDULED);

    @Test
    void plusKeepsTheOrderAndSumsTheCounts() {
        FeedResult calendars = new FeedResult(List.of(MATCH), List.of("Weekly sport: 127.0.0.1 answered HTTP 500"), 2, 1);
        FeedResult competitions = new FeedResult(List.of(FIXTURE), List.of("German Bundesliga: limited"), 1, 1);

        FeedResult both = calendars.plus(competitions);

        assertThat(both.events()).containsExactly(MATCH, FIXTURE);
        assertThat(both.errors()).containsExactly("Weekly sport: 127.0.0.1 answered HTTP 500",
                "German Bundesliga: limited");
        assertThat(both.feeds()).isEqualTo(3);
        assertThat(both.succeeded()).isEqualTo(2);
    }

    @Test
    void noneAddsNothing() {
        FeedResult one = new FeedResult(List.of(MATCH), List.of(), 1, 1);

        assertThat(FeedResult.NONE.plus(one)).isEqualTo(one);
    }

    @Test
    void allFailedNeedsFeedsNoSuccessAndAReason() {
        assertThat(new FeedResult(List.of(), List.of("Weekly sport: down"), 1, 0).allFailed()).isTrue();
        assertThat(new FeedResult(List.of(), List.of("Weekly sport: down"), 2, 1).allFailed()).isFalse();
        assertThat(new FeedResult(List.of(), List.of(), 1, 0).allFailed()).isFalse();
        assertThat(FeedResult.NONE.allFailed()).isFalse();
    }

    @Test
    void theListsAreCopied() {
        List<String> errors = new ArrayList<>(List.of("Weekly sport: down"));
        FeedResult result = new FeedResult(List.of(), errors, 1, 0);

        errors.clear();

        assertThat(result.errors()).containsExactly("Weekly sport: down");
    }
}
