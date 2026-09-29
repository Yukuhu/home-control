package dev.andre.homecontrol.sources.youtube;

import dev.andre.homecontrol.storage.StorageException;
import dev.andre.homecontrol.storage.VersionedJsonFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** YouTube Data API units per Pacific-time day, charged before each call and kept in /data/youtube-quota.json. */
public class QuotaLedger {

    private static final Logger log = LoggerFactory.getLogger(QuotaLedger.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    public static final ZoneId PACIFIC = ZoneId.of("America/Los_Angeles");

    public enum Call {
        SUBSCRIPTIONS_LIST("subscriptions.list", 1),
        CHANNELS_LIST("channels.list", 1),
        PLAYLIST_ITEMS_LIST("playlistItems.list", 1),
        PLAYLISTS_LIST("playlists.list", 1),
        VIDEOS_LIST("videos.list", 1),
        SEARCH_LIST("search.list", 100);

        private final String apiName;
        private final int units;

        Call(String apiName, int units) {
            this.apiName = apiName;
            this.units = units;
        }

        public String apiName() {
            return apiName;
        }

        public int units() {
            return units;
        }
    }

    public record Usage(LocalDate day, int units, int dailyUnits, int searches, int searchesPerDay,
                        Map<String, Integer> calls, ZonedDateTime resetsAt) {
        public int searchesLeft() {
            return Math.max(0, searchesPerDay - searches);
        }

        public boolean exhausted() {
            return units >= dailyUnits;
        }
    }

    /** What youtube-quota.json holds: one Pacific-time day's usage. {@code day} is null when nothing was stored. */
    record Stored(LocalDate day, int units, int searches, Map<String, Integer> calls) {
        Stored {
            calls = Collections.unmodifiableMap(new LinkedHashMap<>(calls));
        }
    }

    private final VersionedJsonFile<Stored> file;
    private final Clock clock;
    private final int dailyUnits;
    private final int searchesPerDay;
    private LocalDate day;
    private int units;
    private int searches;
    private final Map<String, Integer> calls = new LinkedHashMap<>();

    public QuotaLedger(Path path, Clock clock, int dailyUnits, int searchesPerDay) {
        this.file = new VersionedJsonFile<>(path, "the YouTube quota", 1, () -> new Stored(null, 0, 0, Map.of()),
                QuotaLedger::readStored, QuotaLedger::writeStored);
        this.clock = clock;
        this.dailyUnits = dailyUnits;
        this.searchesPerDay = searchesPerDay;
        this.day = today();
        load();
    }

    public synchronized void charge(Call call) {
        roll();
        if (call == Call.SEARCH_LIST && searches >= searchesPerDay) {
            throw new YouTubeException(YouTubeException.Kind.SEARCH_LIMIT, "You have used today's " + searchesPerDay
                    + " YouTube searches. More " + resetPhrase(resetsAt()) + ".");
        }
        if (units + call.units() > dailyUnits) {
            throw exhaustedException();
        }
        units += call.units();
        if (call == Call.SEARCH_LIST) {
            searches++;
        }
        calls.merge(call.apiName(), 1, Integer::sum);
        write();
    }

    public synchronized void markExhausted() {
        roll();
        units = Math.max(units, dailyUnits);
        write();
    }

    public synchronized Usage usage() {
        roll();
        return new Usage(day, units, dailyUnits, searches, searchesPerDay,
                Collections.unmodifiableMap(new LinkedHashMap<>(calls)), resetsAt());
    }

    /**
     * Forgets today's usage and deletes its file. Exists for the shared test context, which reuses one application
     * for many test classes.
     */
    public synchronized void reset() {
        day = today();
        units = 0;
        searches = 0;
        calls.clear();
        file.delete();
    }

    YouTubeException exhaustedException() {
        return new YouTubeException(YouTubeException.Kind.QUOTA_EXHAUSTED, "YouTube's daily API quota is used up ("
                + Math.min(units, dailyUnits) + " of " + dailyUnits + " units). Rails refresh again "
                + resetPhrase(resetsAt()) + ".", "quotaExceeded");
    }

    public static String resetPhrase(ZonedDateTime resetsAt) {
        return "after midnight Pacific time (" + resetsAt.format(DateTimeFormatter.ofPattern("HH:mm")) + " here)";
    }

    private ZonedDateTime resetsAt() {
        return day.plusDays(1).atStartOfDay(PACIFIC).withZoneSameInstant(clock.getZone());
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(PACIFIC));
    }

    private void roll() {
        LocalDate now = today();
        if (!now.equals(day)) {
            day = now;
            units = 0;
            searches = 0;
            calls.clear();
        }
    }

    /** Today's stored usage, if any. An unreadable file is moved aside and the count starts from zero. */
    private void load() {
        try {
            Stored stored = file.read();
            if (stored.day() == null || !stored.day().equals(day)) {
                return;
            }
            units = stored.units();
            searches = stored.searches();
            calls.putAll(stored.calls());
        } catch (StorageException e) {
            Path path = file.file();
            Path aside = path.resolveSibling(path.getFileName() + ".corrupt-" + clock.instant().getEpochSecond());
            log.warn("YouTube quota file {} is unreadable ({}); moved to {} and counting from zero", path,
                    e.getCause() == null ? e.getMessage() : e.getCause().getMessage(), aside);
            try {
                Files.move(path, aside, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveFailed) {
                log.warn("Could not move {} aside: {}", path, moveFailed.getMessage());
            }
            units = 0;
            searches = 0;
            calls.clear();
        }
    }

    private void write() {
        file.write(new Stored(day, units, searches, calls));
    }

    private static Stored readStored(JsonNode root) {
        String storedDay = root.path("day").asString("");
        if (storedDay.isBlank()) {
            throw new IllegalArgumentException("day is required");
        }
        Map<String, Integer> storedCalls = new LinkedHashMap<>();
        root.path("calls").properties().forEach(entry -> storedCalls.put(entry.getKey(), entry.getValue().asInt(0)));
        return new Stored(LocalDate.parse(storedDay), root.path("units").asInt(0), root.path("searches").asInt(0),
                storedCalls);
    }

    private static ObjectNode writeStored(Stored stored) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("day", stored.day().toString());
        root.put("units", stored.units());
        root.put("searches", stored.searches());
        ObjectNode callsNode = root.putObject("calls");
        stored.calls().forEach(callsNode::put);
        return root;
    }
}
