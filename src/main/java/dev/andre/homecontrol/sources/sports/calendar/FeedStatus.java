package dev.andre.homecontrol.sources.sports.calendar;

import java.time.Instant;

/** What the setup page shows about one feed: when it last loaded and how it is doing. */
public record FeedStatus(Instant fetchedAt, int events, String error, int unsupportedRules, int unknownZones,
                         int skippedEvents) {
}
