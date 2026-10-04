package dev.andre.homecontrol.sources.sports.ics;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One concrete instance of an event inside an expansion window. {@code recurrenceId} is the start the occurrence has
 * in its series, which an override that moves it keeps: for an all-day one, its date's midnight in UTC. It is null
 * for an event that does not repeat, or whose RRULE is not supported.
 */
public record IcsOccurrence(String uid, String summary, Instant startsAt, Instant endsAt, LocalDate allDayDate,
                            Instant recurrenceId) {
}
