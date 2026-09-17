package dev.andre.homecontrol.sources.sports;

import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.sources.sports.calendar.CalendarSchedule;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Every configured feed as one list of events. Only fails when no feed has anything to show. */
public class SportsSchedule {

    private final CalendarSchedule calendars;

    public SportsSchedule(CalendarSchedule calendars) {
        this.calendars = calendars;
    }

    public boolean hasFeeds() {
        return calendars.hasCalendars();
    }

    public List<SportsEvent> events() {
        CalendarSchedule.Result result = calendars.events();
        if (result.feeds() > 0 && result.succeeded() == 0 && !result.errors().isEmpty()) {
            throw new ContentSourceException(result.errors().getFirst());
        }
        return new ArrayList<>(result.events());
    }

    public Optional<SportsEvent> find(String itemId) {
        return itemId.startsWith("ics:") ? calendars.find(itemId) : Optional.empty();
    }
}
