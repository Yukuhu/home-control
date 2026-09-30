package dev.andre.homecontrol.sources.sports;

import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.sources.sports.feed.FeedResult;
import dev.andre.homecontrol.sources.sports.feed.SportsEvent;
import dev.andre.homecontrol.sources.sports.feed.SportsFeed;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SportsScheduleTest {

    /** A feed with a fixed answer; {@code known} is what {@link #find} can return. */
    private record StubFeed(String itemPrefix, boolean configured, FeedResult result, List<SportsEvent> known)
            implements SportsFeed {

        @Override
        public FeedResult events() {
            return result;
        }

        @Override
        public Optional<SportsEvent> find(String itemId) {
            return known.stream().filter(event -> event.itemId().equals(itemId)).findFirst();
        }
    }

    private static final SportsEvent MATCH = event("ics:c-3f9a1c2b7d4e:069e696917c4a665", "calendar:c-3f9a1c2b7d4e");
    private static final SportsEvent FIXTURE = event("tsdb:2601002", "thesportsdb:4328");

    private static SportsEvent event(String itemId, String competitionKey) {
        return new SportsEvent(itemId, competitionKey, "Match", Instant.parse("2026-09-19T13:30:00Z"),
                Instant.parse("2026-09-19T15:30:00Z"), null, null, SportsEvent.Status.SCHEDULED);
    }

    @Test
    void mergesTheFeedsInTheirOrder() {
        SportsSchedule schedule = new SportsSchedule(List.of(
                new StubFeed("ics:", true, new FeedResult(List.of(MATCH), List.of("Weekly sport: down"), 2, 1), List.of()),
                new StubFeed("tsdb:", true, new FeedResult(List.of(FIXTURE), List.of(), 1, 1), List.of())));

        assertThat(schedule.events()).containsExactly(MATCH, FIXTURE);
    }

    @Test
    void failsWithTheFirstReasonWhenNoFeedLoaded() {
        SportsSchedule schedule = new SportsSchedule(List.of(
                new StubFeed("ics:", true,
                        new FeedResult(List.of(), List.of("Weekly sport: 127.0.0.1 answered HTTP 500"), 1, 0), List.of()),
                new StubFeed("tsdb:", true,
                        new FeedResult(List.of(), List.of("German Bundesliga: limited"), 1, 0), List.of())));

        assertThatThrownBy(schedule::events)
                .isInstanceOfSatisfying(ContentSourceException.class,
                        e -> assertThat(e.kind()).isEqualTo(ContentSourceException.Kind.BAD_RESPONSE))
                .hasMessage("Weekly sport: 127.0.0.1 answered HTTP 500");
    }

    @Test
    void oneFeedThatLoadedIsEnough() {
        SportsSchedule schedule = new SportsSchedule(List.of(
                new StubFeed("ics:", true, new FeedResult(List.of(), List.of("Weekly sport: down"), 1, 0), List.of()),
                new StubFeed("tsdb:", true, new FeedResult(List.of(FIXTURE), List.of(), 1, 1), List.of())));

        assertThat(schedule.events()).containsExactly(FIXTURE);
    }

    @Test
    void findAsksOnlyTheFeedWhosePrefixMatches() {
        SportsEvent misplaced = event("tsdb:9", "calendar:c-3f9a1c2b7d4e");
        SportsSchedule schedule = new SportsSchedule(List.of(
                new StubFeed("ics:", true, FeedResult.NONE, List.of(MATCH, misplaced)),
                new StubFeed("tsdb:", true, FeedResult.NONE, List.of(FIXTURE))));

        assertThat(schedule.find(MATCH.itemId())).contains(MATCH);
        assertThat(schedule.find(FIXTURE.itemId())).contains(FIXTURE);
        assertThat(schedule.find("tsdb:9")).isEmpty();
        assertThat(schedule.find("yt:dQw4w9WgXcQ")).isEmpty();
    }

    @Test
    void hasFeedsWhenAnyFeedIsConfigured() {
        StubFeed noCalendars = new StubFeed("ics:", false, FeedResult.NONE, List.of());
        StubFeed competitions = new StubFeed("tsdb:", true, FeedResult.NONE, List.of());

        assertThat(new SportsSchedule(List.of(noCalendars, competitions)).hasFeeds()).isTrue();
        assertThat(new SportsSchedule(List.of(noCalendars)).hasFeeds()).isFalse();
        assertThat(new SportsSchedule(List.of()).hasFeeds()).isFalse();
    }
}
