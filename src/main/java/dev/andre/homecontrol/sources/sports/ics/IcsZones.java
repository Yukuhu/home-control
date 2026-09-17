package dev.andre.homecontrol.sources.sports.ics;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

import static java.util.Map.entry;

/** Maps the TZID spellings real calendar producers use to Java zones. VTIMEZONE bodies are not read. */
public final class IcsZones {

    private static final Map<String, String> WINDOWS = Map.ofEntries(
            entry("W. Europe Standard Time", "Europe/Berlin"),
            entry("Central Europe Standard Time", "Europe/Budapest"),
            entry("Central European Standard Time", "Europe/Warsaw"),
            entry("Romance Standard Time", "Europe/Paris"),
            entry("GMT Standard Time", "Europe/London"),
            entry("Greenwich Standard Time", "Atlantic/Reykjavik"),
            entry("E. Europe Standard Time", "Europe/Chisinau"),
            entry("FLE Standard Time", "Europe/Kyiv"),
            entry("Eastern Standard Time", "America/New_York"),
            entry("Central Standard Time", "America/Chicago"),
            entry("Mountain Standard Time", "America/Denver"),
            entry("Pacific Standard Time", "America/Los_Angeles"),
            entry("UTC", "UTC"),
            entry("Coordinated Universal Time", "UTC"));

    private IcsZones() {
    }

    public static Optional<ZoneId> resolve(String tzid) {
        if (tzid == null) {
            return Optional.empty();
        }
        String id = tzid.strip();
        if (id.length() >= 2 && id.startsWith("\"") && id.endsWith("\"")) {
            id = id.substring(1, id.length() - 1).strip();
        }
        if (id.isEmpty()) {
            return Optional.empty();
        }
        String windows = WINDOWS.get(id);
        if (windows != null) {
            return Optional.of(zoneOf(windows));
        }
        String[] segments = id.split("/");
        for (int i = 0; i < segments.length; i++) {
            String candidate = String.join("/", Arrays.copyOfRange(segments, i, segments.length));
            if (candidate.isEmpty()) {
                continue;
            }
            try {
                return Optional.of(ZoneId.of(candidate));
            } catch (DateTimeException e) {
                // try a shorter suffix
            }
        }
        return Optional.empty();
    }

    private static ZoneId zoneOf(String id) {
        try {
            return ZoneId.of(id);
        } catch (DateTimeException e) {
            // fallback spelling for JDKs whose tzdb lacks the modern alias
            return ZoneId.of(id.equals("Europe/Kyiv") ? "Europe/Kiev" : id);
        }
    }
}
