package dev.andre.homecontrol.sources.sports;

import dev.andre.homecontrol.core.content.StreamingProviders;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The household's own answer to "where do you watch this competition". Home Control has no
 * authoritative broadcast data (spec §4.2, §11), so every place that shows it says so.
 */
public final class SportsProviders {

    public static final String USER_SETTING = " (your setting)";

    public record Option(String key, String name) {
    }

    private SportsProviders() {
    }

    public static List<Option> options() {
        return StreamingProviders.KNOWN.entrySet().stream().map(e -> new Option(e.getKey(), e.getValue())).toList();
    }

    public static Optional<String> displayName(String key) {
        return Optional.ofNullable(key == null ? null : StreamingProviders.KNOWN.get(key));
    }

    public static String normalise(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String key = raw.strip();
        if (!StreamingProviders.KNOWN.containsKey(key)) {
            throw new IllegalArgumentException("Unknown streaming service " + key);
        }
        return key;
    }

    public static SportsSettings apply(SportsSettings settings, Map<String, String> providerByCompetitionKey) {
        if (providerByCompetitionKey.isEmpty()) {
            throw new IllegalArgumentException("Nothing to save");
        }
        List<SportsSettings.CalendarEntry> calendars = new ArrayList<>(settings.calendars());
        List<SportsSettings.CompetitionEntry> competitions = new ArrayList<>(settings.competitions());
        for (Map.Entry<String, String> entry : providerByCompetitionKey.entrySet()) {
            String key = entry.getKey();
            String provider = normalise(entry.getValue());
            int calendar = indexOf(calendars.stream().map(c -> SportsSettings.calendarKey(c.id())).toList(), key);
            int competition = indexOf(competitions.stream().map(c -> SportsSettings.competitionKey(c.leagueId())).toList(), key);
            if (calendar >= 0) {
                SportsSettings.CalendarEntry c = calendars.get(calendar);
                calendars.set(calendar, new SportsSettings.CalendarEntry(c.id(), c.label(), c.host(), provider, c.addedAt()));
            } else if (competition >= 0) {
                SportsSettings.CompetitionEntry c = competitions.get(competition);
                competitions.set(competition, new SportsSettings.CompetitionEntry(c.leagueId(), c.name(), c.sport(),
                        c.country(), c.badge(), provider, c.addedAt()));
            } else {
                throw new IllegalArgumentException("No competition " + key);
            }
        }
        return settings.withCalendars(calendars).withCompetitions(competitions);
    }

    private static int indexOf(List<String> keys, String key) {
        return keys.indexOf(key);
    }
}
