package dev.andre.homecontrol.sources.sports.ics;

import java.time.Duration;
import java.util.List;

/** One VEVENT's properties Home Control reads (see the ICS subset rules). */
public record IcsEvent(String uid, String summary, IcsTime start, IcsTime end, Duration duration, String rrule,
                       List<IcsTime> exdates, IcsTime recurrenceId, String status) {

    public IcsEvent {
        exdates = exdates == null ? List.of() : List.copyOf(exdates);
    }
}
