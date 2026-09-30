package dev.andre.homecontrol.storage;

import dev.andre.homecontrol.config.Json;
import dev.andre.homecontrol.core.content.SourcePreferences;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * sources.json, version 2. It holds non-secret per-source settings, each source's section being its own settings
 * record as JSON, plus the household's rail and source preferences (D4). Version 1 flattened every section into
 * strings. Its sections wait under {@code unmigrated} until their source reads them and converts them with its own
 * {@code fromVersionOne}, so this store needs no source's types, and a switched-off source's section survives
 * untouched. Secrets never live here; they go in {@link SecretStore}.
 */
public class JsonFileSourceSettings {

    private static final int VERSION = 2;
    private static final String SOURCES = "sources";
    private static final String UNMIGRATED = "unmigrated";
    private static final String PREFERENCES = "preferences";
    private static final JsonMapper MAPPER = Json.MAPPER;

    /** The whole file. {@code preferences} is null until they are first saved. */
    record Document(Map<String, JsonNode> sources, Map<String, Map<String, String>> unmigrated, JsonNode preferences) {

        static final Document EMPTY = new Document(Map.of(), Map.of(), null);

        Document {
            sources = Collections.unmodifiableMap(new TreeMap<>(sources));
            unmigrated = Collections.unmodifiableMap(new TreeMap<>(unmigrated));
        }

        Document withSource(String id, JsonNode section) {
            Map<String, JsonNode> next = new TreeMap<>(sources);
            next.put(id, section);
            Map<String, Map<String, String>> rest = new TreeMap<>(unmigrated);
            rest.remove(id);
            return new Document(next, rest, preferences);
        }

        Document without(String id) {
            Map<String, JsonNode> next = new TreeMap<>(sources);
            next.remove(id);
            Map<String, Map<String, String>> rest = new TreeMap<>(unmigrated);
            rest.remove(id);
            return new Document(next, rest, preferences);
        }

        Document withPreferences(JsonNode node) {
            return new Document(sources, unmigrated, node);
        }
    }

    private final Path path;
    private final VersionedJsonFile<Document> file;

    public JsonFileSourceSettings(Path path) {
        this.path = path;
        this.file = new VersionedJsonFile<>(path, "source settings", VERSION, () -> Document.EMPTY,
                JsonFileSourceSettings::readDocument, JsonFileSourceSettings::writeDocument)
                .migrate(1, JsonFileSourceSettings::versionOneToTwo);
    }

    /**
     * The source's settings, or empty when it has none. A section still in version 1's flat form is converted by
     * {@code fromVersionOne} and stored typed in the same step. A flat section it cannot use is dropped, reading as
     * "not connected".
     */
    public synchronized <T> Optional<T> get(String sourceId, Class<T> type,
                                            Function<Map<String, String>, Optional<T>> fromVersionOne) {
        Document document = file.read();
        JsonNode section = document.sources().get(sourceId);
        if (section != null) {
            return Optional.of(bind(sourceId, section, type));
        }
        Map<String, String> flat = document.unmigrated().get(sourceId);
        if (flat == null) {
            return Optional.empty();
        }
        Optional<T> converted = fromVersionOne.apply(flat);
        file.update(current -> converted
                .map(value -> current.withSource(sourceId, MAPPER.valueToTree(value)))
                .orElseGet(() -> current.without(sourceId)));
        return converted;
    }

    public synchronized void put(String sourceId, Object settings) {
        file.update(document -> document.withSource(sourceId, MAPPER.valueToTree(settings)));
    }

    public synchronized void remove(String sourceId) {
        Document document = file.read();
        if (document.sources().containsKey(sourceId) || document.unmigrated().containsKey(sourceId)) {
            file.update(current -> current.without(sourceId));
        }
    }

    /** Deletes the file. Exists for the shared test context. */
    public synchronized void reset() {
        file.delete();
    }

    /** Empty when nothing was ever saved. A malformed {@code preferences} object is a named {@link StorageException}. */
    public synchronized Optional<SourcePreferences> preferences() {
        JsonNode node = file.read().preferences();
        if (node == null || node.isNull()) {
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
            throw new StorageException("Could not read source preferences in " + path
                    + "; fix or delete the \"preferences\" object", e);
        }
    }

    public synchronized void putPreferences(SourcePreferences preferences) {
        ObjectNode node = MAPPER.createObjectNode();
        preferences.railOrder().forEach(node.putArray("railOrder")::add);
        preferences.hiddenRails().forEach(node.putArray("hiddenRails")::add);
        preferences.disabledSources().forEach(node.putArray("disabledSources")::add);
        ObjectNode minutesNode = node.putObject("refreshMinutes");
        preferences.refreshMinutes().forEach(minutesNode::put);
        node.put("locale", preferences.locale());
        node.put("region", preferences.region());
        preferences.providers().forEach(node.putArray("providers")::add);
        file.update(document -> document.withPreferences(node));
    }

    private <T> T bind(String sourceId, JsonNode section, Class<T> type) {
        try {
            return MAPPER.treeToValue(section, type);
        } catch (JacksonException | IllegalArgumentException e) {
            throw new StorageException("Could not read the " + sourceId + " settings in " + path
                    + "; fix or delete that section", e);
        }
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

    private static Document readDocument(JsonNode root) {
        if (!root.isObject()) {
            throw new IllegalArgumentException("document must be a JSON object");
        }
        Map<String, JsonNode> sources = new TreeMap<>();
        root.path(SOURCES).properties().forEach(field -> sources.put(field.getKey(), field.getValue()));
        Map<String, Map<String, String>> unmigrated = new TreeMap<>();
        root.path(UNMIGRATED).properties().forEach(field -> unmigrated.put(field.getKey(), flat(field.getValue())));
        return new Document(sources, unmigrated, root.get(PREFERENCES));
    }

    private static Map<String, String> flat(JsonNode section) {
        Map<String, String> values = new TreeMap<>();
        section.properties().forEach(field -> values.put(field.getKey(), field.getValue().asString("")));
        return Collections.unmodifiableMap(values);
    }

    private static ObjectNode writeDocument(Document document) {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode sources = root.putObject(SOURCES);
        document.sources().forEach(sources::set);
        if (!document.unmigrated().isEmpty()) {
            ObjectNode unmigrated = root.putObject(UNMIGRATED);
            document.unmigrated().forEach((id, values) -> {
                ObjectNode section = unmigrated.putObject(id);
                values.forEach(section::put);
            });
        }
        if (document.preferences() != null) {
            root.set(PREFERENCES, document.preferences());
        }
        return root;
    }

    /** Every flat section waits under "unmigrated" for its source; the preferences keep their shape. */
    private static JsonNode versionOneToTwo(JsonNode v1) {
        if (!v1.isObject()) {
            throw new IllegalArgumentException("document must be a JSON object");
        }
        ObjectNode v2 = MAPPER.createObjectNode();
        v2.putObject(SOURCES);
        JsonNode sections = v1.path(SOURCES);
        if (sections.isObject()) {
            v2.set(UNMIGRATED, sections);
        }
        JsonNode preferences = v1.get(PREFERENCES);
        if (preferences != null) {
            v2.set(PREFERENCES, preferences);
        }
        return v2;
    }
}
