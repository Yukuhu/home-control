package dev.andre.homecontrol.sources.sports.feed;

import java.util.ArrayList;
import java.util.List;

/** What one or more feeds gave: their events, why some failed, how many were asked and how many loaded. */
public record FeedResult(List<SportsEvent> events, List<String> errors, int feeds, int succeeded) {

    /** Nothing configured. */
    public static final FeedResult NONE = new FeedResult(List.of(), List.of(), 0, 0);

    public FeedResult {
        events = List.copyOf(events);
        errors = List.copyOf(errors);
    }

    /** This result followed by {@code other}: events and errors in order, the counts summed. */
    public FeedResult plus(FeedResult other) {
        List<SportsEvent> allEvents = new ArrayList<>(events);
        allEvents.addAll(other.events);
        List<String> allErrors = new ArrayList<>(errors);
        allErrors.addAll(other.errors);
        return new FeedResult(allEvents, allErrors, feeds + other.feeds, succeeded + other.succeeded);
    }

    /** Feeds were asked, none loaded, and at least one said why. */
    public boolean allFailed() {
        return feeds > 0 && succeeded == 0 && !errors.isEmpty();
    }
}
