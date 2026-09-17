package dev.andre.homecontrol.sources.pinned;

import dev.andre.homecontrol.core.playback.AppLinks;
import dev.andre.homecontrol.core.playback.ContentKind;
import dev.andre.homecontrol.storage.StorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pinned shortcuts, in a small JSON file written atomically via a temp file and rename — the same
 * pattern as the device registry and the source settings file.
 */
public class JsonFilePinStore {

    private static final Logger log = LoggerFactory.getLogger(JsonFilePinStore.class);

    private static final int VERSION = 1;
    private static final Pattern ID = Pattern.compile("^p-[0-9a-f]{12}$");
    private static final Pattern UPGRADE_OF =
            Pattern.compile("^[a-z0-9][a-z0-9._-]{0,63}/[A-Za-z0-9._:-]{1,128}$");
    private static final int MAX_TITLE = 120;

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Path file;

    public JsonFilePinStore(Path file) {
        this.file = file;
    }

    public synchronized List<Pin> load() {
        if (!Files.exists(file)) {
            return List.of();
        }
        JsonNode root;
        try {
            root = mapper.readTree(Files.readAllBytes(file));
        } catch (IOException | JacksonException e) {
            throw new StorageException("Could not read pinned shortcuts in " + file + "; fix or delete it", e);
        }
        if (root == null || !root.isObject()) {
            throw new StorageException("Could not read pinned shortcuts in " + file + "; fix or delete it", null);
        }
        int version = root.path("version").isIntegralNumber() ? root.path("version").asInt() : -1;
        if (version != VERSION) {
            String reason = version > VERSION ? "; it was written by a newer Home Control" : "";
            throw new StorageException("Could not read pinned shortcuts in " + file + reason + "; fix or delete it", null);
        }
        List<Pin> pins = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        int index = 0;
        for (JsonNode entry : root.path("pins")) {
            Pin pin = parsePin(entry, index);
            index++;
            if (pin == null) {
                continue;
            }
            if (!seenIds.add(pin.id())) {
                continue;
            }
            pins.add(pin);
        }
        return pins;
    }

    private Pin parsePin(JsonNode entry, int index) {
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
            } catch (IllegalArgumentException e) {
                log.warn("Skipping pinned shortcut at index {}: invalid kind", index);
                return null;
            }
        }
        String service = AppLinks.serviceOf(url.getHost().toLowerCase(Locale.ROOT), url.getPath());
        String subtitle = entry.path("subtitle").isString() ? entry.path("subtitle").asString() : null;
        URI artwork = parseArtwork(entry.path("artwork"));
        String upgradeOf = entry.path("upgradeOf").isString() ? entry.path("upgradeOf").asString() : null;
        if (upgradeOf != null && !UPGRADE_OF.matcher(upgradeOf).matches()) {
            upgradeOf = null;
        }
        Instant createdAt;
        try {
            createdAt = Instant.parse(entry.path("createdAt").asString(""));
        } catch (DateTimeParseException e) {
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
        } catch (URISyntaxException e) {
            return null;
        }
    }

    public synchronized void save(List<Pin> pins) {
        ObjectNode root = mapper.createObjectNode();
        root.put("version", VERSION);
        ArrayNode pinsNode = root.putArray("pins");
        for (Pin pin : pins) {
            ObjectNode node = pinsNode.addObject();
            node.put("id", pin.id());
            node.put("url", pin.url().toString());
            node.put("service", pin.service());
            node.put("title", pin.title());
            if (pin.subtitle() == null) {
                node.putNull("subtitle");
            } else {
                node.put("subtitle", pin.subtitle());
            }
            if (pin.artwork() == null) {
                node.putNull("artwork");
            } else {
                node.put("artwork", pin.artwork().toString());
            }
            node.put("kind", pin.kind().name());
            if (pin.upgradeOf() == null) {
                node.putNull("upgradeOf");
            } else {
                node.put("upgradeOf", pin.upgradeOf());
            }
            node.put("createdAt", pin.createdAt().toString());
        }

        Path parent = file.toAbsolutePath().getParent();
        Path temp = null;
        try {
            Files.createDirectories(parent);
            temp = Files.createTempFile(parent, "pinned", ".json");
            Files.write(temp, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | JacksonException e) {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // Cleanup error; let the original exception propagate
                }
            }
            throw new StorageException("Could not write pinned shortcuts to " + file, e);
        }
    }
}
