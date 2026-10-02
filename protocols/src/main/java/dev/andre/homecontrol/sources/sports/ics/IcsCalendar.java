package dev.andre.homecontrol.sources.sports.ics;

import java.util.List;

/** A parsed VCALENDAR: its name, declared time zone and the VEVENTs Home Control could read. */
public record IcsCalendar(String name, String timeZone, List<IcsEvent> events, int skippedEvents) {

    public IcsCalendar {
        events = events == null ? List.of() : List.copyOf(events);
    }
}
