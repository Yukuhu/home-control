package dev.andre.homecontrol.sources.sports;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Everything the user configured for the sports source: calendars, the TheSportsDB key and competitions. */
public record SportsSettings(String timeZone, List<CalendarEntry> calendars, KeyKind keyKind,
                             List<CompetitionEntry> competitions) {

    public enum KeyKind { FREE, PERSONAL }
    private static final String CALENDAR_PREFIX = "calendar:";
    private static final String THE_SPORTS_DB_PREFIX = "thesportsdb:";

    public record CalendarEntry(String id, String label, String host, String provider, Instant addedAt) {
    }

    public record CompetitionEntry(String leagueId, String name, String sport, String country, URI badge,
                                   String provider, Instant addedAt) {
    }

    public SportsSettings {
        calendars = calendars == null ? List.of() : List.copyOf(calendars);
        competitions = competitions == null ? List.of() : List.copyOf(competitions);
        keyKind = keyKind == null ? KeyKind.FREE : keyKind;
    }

    public static SportsSettings empty() {
        return new SportsSettings(null, List.of(), KeyKind.FREE, List.of());
    }

    public SportsSettings withTimeZone(String newTimeZone) {
        return new SportsSettings(newTimeZone, calendars, keyKind, competitions);
    }

    public SportsSettings withCalendars(List<CalendarEntry> newCalendars) {
        return new SportsSettings(timeZone, newCalendars, keyKind, competitions);
    }

    public SportsSettings withKeyKind(KeyKind newKeyKind) {
        return new SportsSettings(timeZone, calendars, newKeyKind, competitions);
    }

    public SportsSettings withCompetitions(List<CompetitionEntry> newCompetitions) {
        return new SportsSettings(timeZone, calendars, keyKind, newCompetitions);
    }

    public static String calendarKey(String id) {
        return CALENDAR_PREFIX + id;
    }

    public static String competitionKey(String leagueId) {
        return THE_SPORTS_DB_PREFIX + leagueId;
    }

    public Optional<CalendarEntry> calendar(String id) {
        return calendars.stream().filter(c -> c.id().equals(id)).findFirst();
    }

    public Optional<CompetitionEntry> competition(String leagueId) {
        return competitions.stream().filter(c -> c.leagueId().equals(leagueId)).findFirst();
    }

    public Optional<String> labelFor(String competitionKey) {
        if (competitionKey == null) {
            return Optional.empty();
        }
        if (competitionKey.startsWith(CALENDAR_PREFIX)) {
            return calendar(competitionKey.substring(CALENDAR_PREFIX.length())).map(CalendarEntry::label);
        }
        if (competitionKey.startsWith(THE_SPORTS_DB_PREFIX)) {
            return competition(competitionKey.substring(THE_SPORTS_DB_PREFIX.length())).map(CompetitionEntry::name);
        }
        return Optional.empty();
    }

    public Optional<String> providerFor(String competitionKey) {
        if (competitionKey == null) {
            return Optional.empty();
        }
        if (competitionKey.startsWith(CALENDAR_PREFIX)) {
            return calendar(competitionKey.substring(CALENDAR_PREFIX.length())).map(CalendarEntry::provider);
        }
        if (competitionKey.startsWith(THE_SPORTS_DB_PREFIX)) {
            return competition(competitionKey.substring(THE_SPORTS_DB_PREFIX.length())).map(CompetitionEntry::provider);
        }
        return Optional.empty();
    }
}
