package dev.andre.homecontrol.sources.sports;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Everything the user configured for the sports source: calendars, the TheSportsDB key and competitions. */
public record SportsSettings(String timeZone, List<CalendarEntry> calendars, KeyKind keyKind,
                             List<CompetitionEntry> competitions) {

    public enum KeyKind { FREE, PERSONAL }

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
        return "calendar:" + id;
    }

    public static String competitionKey(String leagueId) {
        return "thesportsdb:" + leagueId;
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
        if (competitionKey.startsWith("calendar:")) {
            return calendar(competitionKey.substring("calendar:".length())).map(CalendarEntry::label);
        }
        if (competitionKey.startsWith("thesportsdb:")) {
            return competition(competitionKey.substring("thesportsdb:".length())).map(CompetitionEntry::name);
        }
        return Optional.empty();
    }

    public Optional<String> providerFor(String competitionKey) {
        if (competitionKey == null) {
            return Optional.empty();
        }
        if (competitionKey.startsWith("calendar:")) {
            return calendar(competitionKey.substring("calendar:".length())).map(CalendarEntry::provider);
        }
        if (competitionKey.startsWith("thesportsdb:")) {
            return competition(competitionKey.substring("thesportsdb:".length())).map(CompetitionEntry::provider);
        }
        return Optional.empty();
    }
}
