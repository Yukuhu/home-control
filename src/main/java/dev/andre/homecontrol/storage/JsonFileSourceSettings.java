package dev.andre.homecontrol.storage;

import dev.andre.homecontrol.core.content.SourcePreferences;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Non-secret per-source settings (e.g. a Jellyfin server address and user) plus the household's
 * rail/source preferences (D4), in one small JSON file written atomically via a temp file and
 * rename, the same pattern as the device registry. The whole document is read and rewritten as a
 * tree so that top-level keys neither {@code get}/{@code put}/{@code remove} nor
 * {@link #putPreferences} know about are preserved. Secrets never live here — they go in
 * {@link SecretStore}.
 */
public class JsonFileSourceSettings {

    private static final String SOURCES = "sources";
    private static final String PREFERENCES = "preferences";

    private static final int VERSION = 1;

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Path file;

    public JsonFileSourceSettings(Path file) {
        this.file = file;
    }

    /** {@code Map.of()} when the file, or that source within it, does not exist. Never creates the file. */
    public synchronized Map<String, String> get(String sourceId) {
        Map<String, String> values = new LinkedHashMap<>();
        read().path(SOURCES).path(sourceId).properties()
                .forEach(field -> values.put(field.getKey(), field.getValue().asString("")));
        return values;
    }

    public synchronized void put(String sourceId, Map<String, String> settings) {
        ObjectNode root = read();
        ObjectNode sourceNode = objectChild(root, SOURCES).putObject(sourceId);
        new TreeMap<>(settings).forEach(sourceNode::put);
        write(root);
    }

    public synchronized void remove(String sourceId) {
        ObjectNode root = read();
        if (objectChild(root, SOURCES).remove(sourceId) != null) {
            write(root);
        }
    }

    /** Empty when nothing was ever saved. A malformed {@code preferences} object is a named {@link StorageException}. */
    public synchronized Optional<SourcePreferences> preferences() {
        JsonNode node = read().path(PREFERENCES);
        if (node.isMissingNode() || node.isNull()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new SourcePreferences(
                    strings(node.path("railOrder")),
                    new LinkedHashSet<>(strings(node.path("hiddenRails"))),
                    new LinkedHashSet<>(strings(node.path("disabledSources"))),
                    minutes(node.path("refreshMinutes")),
                    node.path("locale").asString(null),
                    node.path("region").asString(null),
                    strings(node.path("providers"))));
        } catch (RuntimeException e) {
            throw new StorageException("Could not read source preferences in " + file
                    + "; fix or delete the \"preferences\" object", e);
        }
    }

    public synchronized void putPreferences(SourcePreferences preferences) {
        ObjectNode root = read();
        root.remove(PREFERENCES);
        ObjectNode node = root.putObject(PREFERENCES);
        preferences.railOrder().forEach(node.putArray("railOrder")::add);
        preferences.hiddenRails().forEach(node.putArray("hiddenRails")::add);
        preferences.disabledSources().forEach(node.putArray("disabledSources")::add);
        ObjectNode minutesNode = node.putObject("refreshMinutes");
        preferences.refreshMinutes().forEach(minutesNode::put);
        node.put("locale", preferences.locale());
        node.put("region", preferences.region());
        preferences.providers().forEach(node.putArray("providers")::add);
        write(root);
    }

    private static ObjectNode objectChild(ObjectNode root, String field) {
        JsonNode existing = root.get(field);
        return existing instanceof ObjectNode object ? object : root.putObject(field);
    }

    private static List<String> strings(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException("expected a JSON array of strings");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode element : node) {
            if (!element.isString()) {
                throw new IllegalArgumentException("expected an array of strings");
            }
            values.add(element.asString());
        }
        return values;
    }

    private static Map<String, Integer> minutes(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("expected an object of integers");
        }
        Map<String, Integer> values = new LinkedHashMap<>();
        node.properties().forEach(field -> {
            JsonNode value = field.getValue();
            if (!value.isIntegralNumber()) {
                throw new IllegalArgumentException("expected integer minute values");
            }
            values.put(field.getKey(), value.asInt());
        });
        return values;
    }

    /** Reads the whole document, defaulting to an empty one; never creates the file. */
    private ObjectNode read() {
        if (!Files.exists(file)) {
            ObjectNode root = mapper.createObjectNode();
            root.put("version", VERSION);
            root.putObject(SOURCES);
            return root;
        }
        try {
            JsonNode root = mapper.readTree(Files.readAllBytes(file));
            if (!(root instanceof ObjectNode object)) {
                throw new IllegalArgumentException("document must be a JSON object");
            }
            return object;
        } catch (IOException | JacksonException | IllegalArgumentException e) {
            throw new StorageException(
                    "Could not read source settings " + file + "; check file permissions and JSON integrity", e);
        }
    }

    private void write(ObjectNode root) {
        root.put("version", VERSION);
        Path parent = file.toAbsolutePath().getParent();
        Path temp = null;
        try {
            Files.createDirectories(parent);
            temp = Files.createTempFile(parent, SOURCES, ".json");
            Files.write(temp, mapper.writeValueAsBytes(root));
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | JacksonException e) {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException _) {
                    // Cleanup error; let the original exception propagate
                }
            }
            throw new StorageException("Could not write source settings " + file + "; check file permissions", e);
        }
    }
}
