package dev.andre.homecontrol.sources.sports;

import dev.andre.homecontrol.config.Json;
import dev.andre.homecontrol.core.content.StreamingProviders;
import dev.andre.homecontrol.storage.VersionedJsonFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code sports.json}, version 1: calendars, the TheSportsDB key kind and competitions, never the calendar URL. Read
 * once, then served from memory and written through.
 */
public class JsonFileSportsStore {

    private static final String TIME_ZONE = "timeZone";
    private static final String PROVIDER = "provider";
    private static final String ADDED_AT = "addedAt";
    private static final String SPORT = "sport";
    private static final String COUNTRY = "country";
    private static final String BADGE = "badge";

    private static final Logger log = LoggerFactory.getLogger(JsonFileSportsStore.class);

    private static final int VERSION = 1;
    private static final Pattern CALENDAR_ID = Pattern.compile("^c-[0-9a-f]{12}$");
    private static final Pattern LEAGUE_ID = Pattern.compile("^\\d{1,9}$");
    private static final int MAX_LABEL = 80;
    private static final int MAX_HOST = 253;
    private static final int MAX_NAME = 120;
    private static final int MAX_FIELD = 60;

    private static final JsonMapper MAPPER = Json.MAPPER;

    private final VersionedJsonFile<SportsSettings> file;

    public JsonFileSportsStore(Path path) {
        this.file = new VersionedJsonFile<>(path, "sports settings", VERSION, SportsSettings::empty,
                JsonFileSportsStore::readSettings, JsonFileSportsStore::writeSettings);
    }

    public synchronized SportsSettings load() {
        return file.read();
    }

    public synchronized void save(SportsSettings settings) {
        file.write(settings);
    }

    private static SportsSettings readSettings(JsonNode root) {
        if (!root.isObject()) {
            throw new IllegalArgumentException("document must be a JSON object");
        }
        String timeZone = timeZone(root.path(TIME_ZONE));
        List<SportsSettings.CalendarEntry> calendars = calendars(root.path("calendars"));

        JsonNode tsdb = root.path("theSportsDb");
        SportsSettings.KeyKind keyKind = "personal".equals(tsdb.path("key").asString(""))
                ? SportsSettings.KeyKind.PERSONAL : SportsSettings.KeyKind.FREE;
        List<SportsSettings.CompetitionEntry> competitions = competitions(tsdb.path("competitions"));

        return new SportsSettings(timeZone, calendars, keyKind, competitions);
    }

    private static String timeZone(JsonNode node) {
        if (!node.isString()) {
            return null;
        }
        String value = node.asString();
        if (SportsTimeZones.parse(value).isPresent()) {
            return value;
        }
        if (!value.isBlank()) {
            log.warn("Dropping unreadable sports time zone {}", value);
        }
        return null;
    }

    /** Valid entries in file order; a repeated id keeps its first entry. */
    private static List<SportsSettings.CalendarEntry> calendars(JsonNode entries) {
        List<SportsSettings.CalendarEntry> calendars = new ArrayList<>();
        Set<String> seenCalendarIds = new HashSet<>();
        int index = 0;
        for (JsonNode entry : entries) {
            SportsSettings.CalendarEntry parsed = parseCalendar(entry, index);
            index++;
            if (parsed != null && seenCalendarIds.add(parsed.id())) {
                calendars.add(parsed);
            }
        }
        return calendars;
    }

    /** Valid entries in file order; a repeated league id keeps its first entry. */
    private static List<SportsSettings.CompetitionEntry> competitions(JsonNode entries) {
        List<SportsSettings.CompetitionEntry> competitions = new ArrayList<>();
        Set<String> seenLeagueIds = new HashSet<>();
        int index = 0;
        for (JsonNode entry : entries) {
            SportsSettings.CompetitionEntry parsed = parseCompetition(entry, index);
            index++;
            if (parsed != null && seenLeagueIds.add(parsed.leagueId())) {
                competitions.add(parsed);
            }
        }
        return competitions;
    }

    private static SportsSettings.CalendarEntry parseCalendar(JsonNode entry, int index) {
        String id = entry.path("id").asString("");
        String label = entry.path("label").asString("").strip();
        String host = entry.path("host").asString("").strip();
        if (!CALENDAR_ID.matcher(id).matches() || label.isEmpty() || label.length() > MAX_LABEL
                || host.isEmpty() || host.length() > MAX_HOST) {
            log.warn("Skipping sports calendar at index {}: invalid fields", index);
            return null;
        }
        String provider = provider(entry.path(PROVIDER));
        Instant addedAt = addedAt(entry.path(ADDED_AT));
        return new SportsSettings.CalendarEntry(id, label, host, provider, addedAt);
    }

    private static SportsSettings.CompetitionEntry parseCompetition(JsonNode entry, int index) {
        String leagueId = entry.path("leagueId").asString("");
        if (!LEAGUE_ID.matcher(leagueId).matches()) {
            log.warn("Skipping sports competition at index {}: invalid leagueId", index);
            return null;
        }
        String name = entry.path("name").asString("").strip();
        if (name.isEmpty() || name.length() > MAX_NAME) {
            name = "Competition " + leagueId;
        }
        String sport = shortField(entry.path(SPORT));
        String country = shortField(entry.path(COUNTRY));
        URI badge = absoluteHttps(entry.path(BADGE));
        String provider = provider(entry.path(PROVIDER));
        Instant addedAt = addedAt(entry.path(ADDED_AT));
        return new SportsSettings.CompetitionEntry(leagueId, name, sport, country, badge, provider, addedAt);
    }

    private static String shortField(JsonNode node) {
        if (!node.isString()) {
            return null;
        }
        String value = node.asString().strip();
        return value.isEmpty() || value.length() > MAX_FIELD ? null : value;
    }

    private static URI absoluteHttps(JsonNode node) {
        if (!node.isString()) {
            return null;
        }
        String raw = node.asString();
        if (!raw.startsWith("https://")) {
            return null;
        }
        try {
            return new URI(raw);
        } catch (URISyntaxException _) {
            return null;
        }
    }

    private static String provider(JsonNode node) {
        if (!node.isString()) {
            return null;
        }
        String value = node.asString();
        return StreamingProviders.KNOWN.containsKey(value) ? value : null;
    }

    private static Instant addedAt(JsonNode node) {
        if (!node.isString()) {
            return Instant.EPOCH;
        }
        try {
            return Instant.parse(node.asString());
        } catch (DateTimeParseException _) {
            return Instant.EPOCH;
        }
    }

    private static ObjectNode writeSettings(SportsSettings settings) {
        ObjectNode root = MAPPER.createObjectNode();
        putOrNull(root, TIME_ZONE, settings.timeZone());
        ArrayNode calendarsNode = root.putArray("calendars");
        for (SportsSettings.CalendarEntry entry : settings.calendars()) {
            writeCalendar(calendarsNode.addObject(), entry);
        }
        ObjectNode tsdb = root.putObject("theSportsDb");
        tsdb.put("key", settings.keyKind() == SportsSettings.KeyKind.PERSONAL ? "personal" : "free");
        ArrayNode competitionsNode = tsdb.putArray("competitions");
        for (SportsSettings.CompetitionEntry entry : settings.competitions()) {
            writeCompetition(competitionsNode.addObject(), entry);
        }
        return root;
    }

    private static void writeCalendar(ObjectNode node, SportsSettings.CalendarEntry entry) {
        node.put("id", entry.id());
        node.put("label", entry.label());
        node.put("host", entry.host());
        putOrNull(node, PROVIDER, entry.provider());
        node.put(ADDED_AT, entry.addedAt().toString());
    }

    private static void writeCompetition(ObjectNode node, SportsSettings.CompetitionEntry entry) {
        node.put("leagueId", entry.leagueId());
        node.put("name", entry.name());
        putOrNull(node, SPORT, entry.sport());
        putOrNull(node, COUNTRY, entry.country());
        putOrNull(node, BADGE, entry.badge() == null ? null : entry.badge().toString());
        putOrNull(node, PROVIDER, entry.provider());
        node.put(ADDED_AT, entry.addedAt().toString());
    }

    private static void putOrNull(ObjectNode node, String name, String value) {
        if (value == null) {
            node.putNull(name);
        } else {
            node.put(name, value);
        }
    }
}
