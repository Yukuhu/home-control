package dev.andre.homecontrol.sources.sports.feed;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One scheduled, live or finished sporting event, from whichever feed produced it. {@code formerItemId} is the id an
 * older version gave the event, under which pins made then were stored; null when it had no other.
 */
public record SportsEvent(String itemId, String competitionKey, String title, Instant startsAt, Instant endsAt,
                          LocalDate allDayDate, URI artwork, Status status, String formerItemId) {

    public enum Status { SCHEDULED, LIVE, FINISHED }

    public SportsEvent(String itemId, String competitionKey, String title, Instant startsAt, Instant endsAt,
                       LocalDate allDayDate, URI artwork, Status status) {
        this(itemId, competitionKey, title, startsAt, endsAt, allDayDate, artwork, status, null);
    }

    public SportsEvent {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(competitionKey, "competitionKey");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(startsAt, "startsAt");
        Objects.requireNonNull(endsAt, "endsAt");
        Objects.requireNonNull(status, "status");
        if (endsAt.isBefore(startsAt)) {
            throw new IllegalArgumentException("endsAt must not be before startsAt");
        }
    }

    public boolean allDay() {
        return allDayDate != null;
    }
}
