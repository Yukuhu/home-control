package dev.andre.homecontrol.sources.sports;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Where an event stands relative to now, in the household's time zone. */
public enum EventPhase {
    LIVE, ALL_DAY_TODAY, UPCOMING_TODAY, LATER, ENDED;

    public static EventPhase of(SportsEvent event, Instant now, ZoneId zone) {
        if (event.status() == SportsEvent.Status.FINISHED || !event.endsAt().isAfter(now)) {
            return ENDED;
        }
        Instant tomorrow = LocalDate.ofInstant(now, zone).plusDays(1).atStartOfDay(zone).toInstant();
        if (event.allDay()) {
            return !event.startsAt().isAfter(now) || event.startsAt().isBefore(tomorrow) ? ALL_DAY_TODAY : LATER;
        }
        if (event.status() == SportsEvent.Status.LIVE || !event.startsAt().isAfter(now)) {
            return LIVE;
        }
        return event.startsAt().isBefore(tomorrow) ? UPCOMING_TODAY : LATER;
    }
}
