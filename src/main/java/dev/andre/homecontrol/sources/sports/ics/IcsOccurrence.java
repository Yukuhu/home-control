package dev.andre.homecontrol.sources.sports.ics;

import java.time.Instant;
import java.time.LocalDate;

/** One concrete instance of an event inside an expansion window. */
public record IcsOccurrence(String uid, String summary, Instant startsAt, Instant endsAt, LocalDate allDayDate) {
}
