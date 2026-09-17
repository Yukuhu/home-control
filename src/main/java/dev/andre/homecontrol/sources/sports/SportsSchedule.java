package dev.andre.homecontrol.sources.sports;

import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.sources.sports.calendar.CalendarSchedule;
import dev.andre.homecontrol.sources.sports.thesportsdb.TheSportsDbSchedule;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Every configured feed as one list of events. Only fails when no feed has anything to show. */
public class SportsSchedule {

    private final CalendarSchedule calendars;
    private final TheSportsDbSchedule competitions;

    public SportsSchedule(CalendarSchedule calendars, TheSportsDbSchedule competitions) {
        this.calendars = calendars;
        this.competitions = competitions;
    }

    public boolean hasFeeds() {
        return calendars.hasCalendars() || (competitions != null && competitions.hasCompetitions());
    }

    public List<SportsEvent> events() {
        CalendarSchedule.Result calendarResult = calendars.events();
        TheSportsDbSchedule.Result competitionResult = competitions == null
                ? new TheSportsDbSchedule.Result(List.of(), List.of(), 0, 0) : competitions.events();

        List<SportsEvent> events = new ArrayList<>(calendarResult.events());
        events.addAll(competitionResult.events());

        List<String> errors = new ArrayList<>(calendarResult.errors());
        errors.addAll(competitionResult.errors());

        int feeds = calendarResult.feeds() + competitionResult.feeds();
        int succeeded = calendarResult.succeeded() + competitionResult.succeeded();

        if (feeds > 0 && succeeded == 0 && !errors.isEmpty()) {
            throw new ContentSourceException(errors.getFirst());
        }
        return events;
    }

    public Optional<SportsEvent> find(String itemId) {
        if (itemId.startsWith("ics:")) {
            return calendars.find(itemId);
        }
        if (itemId.startsWith("tsdb:")) {
            return competitions == null ? Optional.empty() : competitions.find(itemId);
        }
        return Optional.empty();
    }
}
