package dev.andre.homecontrol.sources.sports.feed;

import java.util.Optional;

/** One kind of sports feed: every configured calendar, or every chosen competition. */
public interface SportsFeed {

    /** The prefix of this feed's item ids, such as {@code "ics:"}. */
    String itemPrefix();

    /** Whether anything of this kind is configured. */
    boolean configured();

    /** Every configured feed's events, after refreshing those that are due. */
    FeedResult events();

    Optional<SportsEvent> find(String itemId);
}
