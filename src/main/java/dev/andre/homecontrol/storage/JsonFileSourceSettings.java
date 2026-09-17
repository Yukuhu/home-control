package dev.andre.homecontrol.storage;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Non-secret per-source settings (e.g. a Jellyfin server address and user) in one small JSON file,
 * written atomically via a temp file and rename, the same pattern as the device registry. Secrets
 * never live here — they go in {@link SecretStore}.
 */
public class JsonFileSourceSettings {

    private static final int VERSION = 1;

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Path file;

    public JsonFileSourceSettings(Path file) {
        this.file = file;
    }

    /** {@code Map.of()} when the file, or that source within it, does not exist. Never creates the file. */
    public synchronized Map<String, String> get(String sourceId) {
        return readAll().getOrDefault(sourceId, Map.of());
    }

    public synchronized void put(String sourceId, Map<String, String> settings) {
        Map<String, Map<String, String>> all = new LinkedHashMap<>(readAll());
        all.put(sourceId, Map.copyOf(settings));
        writeAll(all);
    }

    public synchronized void remove(String sourceId) {
        Map<String, Map<String, String>> all = new LinkedHashMap<>(readAll());
        if (all.remove(sourceId) != null) {
            writeAll(all);
        }
    }

    private Map<String, Map<String, String>> readAll() {
        if (!Files.exists(file)) {
            return Map.of();
        }
        try {
            JsonNode root = mapper.readTree(Files.readAllBytes(file));
            JsonNode sourcesNode = root == null ? null : root.get("sources");
            if (sourcesNode == null || !sourcesNode.isObject()) {
                throw new IllegalArgumentException("document must have a \"sources\" object");
            }
            Map<String, Map<String, String>> all = new LinkedHashMap<>();
            sourcesNode.properties().forEach(entry -> {
                Map<String, String> values = new LinkedHashMap<>();
                entry.getValue().properties().forEach(field -> values.put(field.getKey(), field.getValue().asString("")));
                all.put(entry.getKey(), values);
            });
            return all;
        } catch (IOException | JacksonException | IllegalArgumentException e) {
            throw new StorageException(
                    "Could not read source settings " + file + "; check file permissions and JSON integrity", e);
        }
    }

    private void writeAll(Map<String, Map<String, String>> all) {
        ObjectNode root = mapper.createObjectNode();
        root.put("version", VERSION);
        ObjectNode sourcesNode = root.putObject("sources");
        new TreeMap<>(all).forEach((sourceId, values) -> {
            ObjectNode sourceNode = sourcesNode.putObject(sourceId);
            new TreeMap<>(values).forEach(sourceNode::put);
        });

        Path parent = file.toAbsolutePath().getParent();
        Path temp = null;
        try {
            Files.createDirectories(parent);
            temp = Files.createTempFile(parent, "sources", ".json");
            Files.write(temp, mapper.writeValueAsBytes(root));
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | JacksonException e) {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // Cleanup error; let the original exception propagate
                }
            }
            throw new StorageException("Could not write source settings " + file + "; check file permissions", e);
        }
    }
}
