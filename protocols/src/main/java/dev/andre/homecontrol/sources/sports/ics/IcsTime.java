package dev.andre.homecontrol.sources.sports.ics;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** An iCalendar DATE or DATE-TIME as written (RFC 5545 §3.3.4, §3.3.5); zones are applied at expansion. */
public sealed interface IcsTime {

    record Date(LocalDate date) implements IcsTime {
    }

    /** Wall-clock time; {@code tzid} null means floating (read in the calendar's zone). */
    record Local(LocalDateTime dateTime, String tzid) implements IcsTime {
    }

    record Utc(Instant instant) implements IcsTime {
    }
}
