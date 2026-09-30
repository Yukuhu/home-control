package dev.andre.homecontrol.sources.sports;

import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.sources.sports.feed.FeedResult;
import dev.andre.homecontrol.sources.sports.feed.SportsEvent;
import dev.andre.homecontrol.sources.sports.feed.SportsFeed;

import java.util.List;
import java.util.Optional;

/** Every configured feed as one list of events. Only fails when no feed has anything to show. */
public class SportsSchedule {

    private final List<SportsFeed> feeds;

    public SportsSchedule(List<SportsFeed> feeds) {
        this.feeds = List.copyOf(feeds);
    }

    public boolean hasFeeds() {
        return feeds.stream().anyMatch(SportsFeed::configured);
    }

    public List<SportsEvent> events() {
        FeedResult all = FeedResult.NONE;
        for (SportsFeed feed : feeds) {
            all = all.plus(feed.events());
        }
        if (all.allFailed()) {
            throw new ContentSourceException(ContentSourceException.Kind.BAD_RESPONSE, all.errors().getFirst());
        }
        return all.events();
    }

    public Optional<SportsEvent> find(String itemId) {
        return feeds.stream()
                .filter(feed -> itemId.startsWith(feed.itemPrefix()))
                .findFirst()
                .flatMap(feed -> feed.find(itemId));
    }
}
