package dev.andre.homecontrol.sources.youtube;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
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
    private static final JsonMapper MAPPER = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
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

    private final Path file;
    private final Clock clock;
    private final int dailyUnits;
    private final int searchesPerDay;
    private LocalDate day;
    private int units;
    private int searches;
    private final Map<String, Integer> calls = new LinkedHashMap<>();

    public QuotaLedger(Path file, Clock clock, int dailyUnits, int searchesPerDay) {
        this.file = file;
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

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            JsonNode root = MAPPER.readTree(Files.readAllBytes(file));
            if (root.path("version").asInt(0) != 1 || root.path("day").asString("").isBlank()) {
                throw new IllegalStateException("unexpected shape");
            }
            if (!LocalDate.parse(root.path("day").asString("")).equals(day)) {
                return;
            }
            units = root.path("units").asInt(0);
            searches = root.path("searches").asInt(0);
            root.path("calls").properties().forEach(entry -> calls.put(entry.getKey(), entry.getValue().asInt(0)));
        } catch (IOException | RuntimeException e) {
            Path aside = file.resolveSibling(file.getFileName() + ".corrupt-" + clock.instant().getEpochSecond());
            log.warn("YouTube quota file {} is unreadable ({}); moved to {} and counting from zero", file, e.getMessage(), aside);
            try {
                Files.move(file, aside, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveFailed) {
                log.warn("Could not move {} aside: {}", file, moveFailed.getMessage());
            }
            units = 0;
            searches = 0;
            calls.clear();
        }
    }

    private void write() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("version", 1);
        root.put("day", day.toString());
        root.put("units", units);
        root.put("searches", searches);
        ObjectNode callsNode = root.putObject("calls");
        calls.forEach(callsNode::put);
        try {
            Files.createDirectories(file.getParent());
            Path temp = Files.createTempFile(file.getParent(), ".youtube-quota-", ".tmp");
            Files.write(temp, MAPPER.writeValueAsBytes(root));
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + file, e);
        }
    }
}
