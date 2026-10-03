package dev.andre.homecontrol.sources.sports.ics;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One concrete instance of an event inside an expansion window. {@code recurrenceId} is the start the occurrence has
 * in its series, which an override that moves it keeps; null for an event that does not repeat.
 */
public record IcsOccurrence(String uid, String summary, Instant startsAt, Instant endsAt, LocalDate allDayDate,
                            Instant recurrenceId) {
}
