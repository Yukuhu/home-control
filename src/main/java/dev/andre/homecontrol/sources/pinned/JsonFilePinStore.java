package dev.andre.homecontrol.sources.pinned;

import dev.andre.homecontrol.core.playback.AppLinks;
import dev.andre.homecontrol.core.playback.ContentKind;
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
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Pinned shortcuts in pinned.json, version 1: read once, then served from memory and written through. */
public class JsonFilePinStore {

    private static final String SUBTITLE = "subtitle";
    private static final String ARTWORK = "artwork";

    private static final Logger log = LoggerFactory.getLogger(JsonFilePinStore.class);

    private static final int VERSION = 1;
    private static final String UPGRADE_OF_KEY = "upgradeOf";
    private static final Pattern ID = Pattern.compile("^p-[0-9a-f]{12}$");
    private static final Pattern UPGRADE_OF =
            Pattern.compile("^[a-z0-9][a-z0-9._-]{0,63}/[A-Za-z0-9._:-]{1,128}$");
    private static final int MAX_TITLE = 120;

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final VersionedJsonFile<List<Pin>> file;

    public JsonFilePinStore(Path path) {
        this.file = new VersionedJsonFile<>(path, "pinned shortcuts", VERSION, List::of,
                JsonFilePinStore::readPins, JsonFilePinStore::writePins);
    }

    public synchronized List<Pin> load() {
        return file.read();
    }

    public synchronized void save(List<Pin> pins) {
        file.write(List.copyOf(pins));
    }

    /** Valid entries in file order; an invalid one is skipped with a warning, a repeated id keeps its first entry. */
    private static List<Pin> readPins(JsonNode root) {
        if (!root.isObject()) {
            throw new IllegalArgumentException("document must be a JSON object");
        }
        List<Pin> pins = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        int index = 0;
        for (JsonNode entry : root.path("pins")) {
            Pin pin = parsePin(entry, index);
            index++;
            if (pin != null && seenIds.add(pin.id())) {
                pins.add(pin);
            }
        }
        return List.copyOf(pins);
    }

    private static Pin parsePin(JsonNode entry, int index) {
        String id = entry.path("id").asString("");
        if (!ID.matcher(id).matches()) {
            log.warn("Skipping pinned shortcut at index {}: invalid or missing id", index);
            return null;
        }
        URI url;
        try {
            url = AppLinks.parseHttpUrl(entry.path("url").asString(""));
        } catch (IllegalArgumentException e) {
            log.warn("Skipping pinned shortcut at index {}: {}", index, e.getMessage());
            return null;
        }
        String title = entry.path("title").asString("").strip();
        if (title.isEmpty() || title.length() > MAX_TITLE) {
            log.warn("Skipping pinned shortcut at index {}: invalid title", index);
            return null;
        }
        JsonNode kindNode = entry.path("kind");
        ContentKind kind;
        if (kindNode.isMissingNode() || kindNode.isNull()) {
            kind = ContentKind.VIDEO;
        } else {
            try {
                kind = ContentKind.valueOf(kindNode.asString(""));
            } catch (IllegalArgumentException _) {
                log.warn("Skipping pinned shortcut at index {}: invalid kind", index);
                return null;
            }
        }
        String service = AppLinks.serviceOf(url.getHost().toLowerCase(Locale.ROOT), url.getPath());
        String subtitle = entry.path(SUBTITLE).isString() ? entry.path(SUBTITLE).asString() : null;
        URI artwork = parseArtwork(entry.path(ARTWORK));
        String upgradeOf = entry.path(UPGRADE_OF_KEY).isString() ? entry.path(UPGRADE_OF_KEY).asString() : null;
        if (upgradeOf != null && !UPGRADE_OF.matcher(upgradeOf).matches()) {
            upgradeOf = null;
        }
        Instant createdAt;
        try {
            createdAt = Instant.parse(entry.path("createdAt").asString(""));
        } catch (DateTimeParseException _) {
            createdAt = Instant.EPOCH;
        }
        return new Pin(id, url, service, title, subtitle, artwork, kind, upgradeOf, createdAt);
    }

    private static URI parseArtwork(JsonNode node) {
        if (!node.isString()) {
            return null;
        }
        String raw = node.asString();
        boolean acceptable = raw.startsWith("https://") || (raw.startsWith("/") && !raw.startsWith("//"));
        if (!acceptable) {
            return null;
        }
        try {
            return new URI(raw);
        } catch (URISyntaxException _) {
            return null;
        }
    }

    private static ObjectNode writePins(List<Pin> pins) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode pinsNode = root.putArray("pins");
        for (Pin pin : pins) {
            ObjectNode node = pinsNode.addObject();
            node.put("id", pin.id());
            node.put("url", pin.url().toString());
            node.put("service", pin.service());
            node.put("title", pin.title());
            putOrNull(node, SUBTITLE, pin.subtitle());
            putOrNull(node, ARTWORK, Objects.toString(pin.artwork(), null));
            node.put("kind", pin.kind().name());
            putOrNull(node, UPGRADE_OF_KEY, pin.upgradeOf());
            node.put("createdAt", pin.createdAt().toString());
        }
        return root;
    }

    /** Optional fields are always written, as JSON null when absent. */
    private static void putOrNull(ObjectNode node, String key, String value) {
        if (value == null) {
            node.putNull(key);
        } else {
            node.put(key, value);
        }
    }
}
