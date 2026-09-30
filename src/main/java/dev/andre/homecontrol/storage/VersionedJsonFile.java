package dev.andre.homecontrol.storage;

import dev.andre.homecontrol.config.Json;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.function.UnaryOperator;

/**
 * One JSON document under /data with a schema version. It is read once and cached, and migrated forward step by step
 * on that first read, keeping the original once as {@code <name>.v<n>.json}. Its store, the single writer, writes it
 * whole through {@link AtomicFiles}. A file from a newer Home Control is refused, never guessed at. {@code T} must be
 * immutable: the snapshot is handed out as it is.
 */
public final class VersionedJsonFile<T> {

    private static final String VERSION_FIELD = "version";
    private static final String JSON = ".json";
    private static final JsonMapper MAPPER = Json.MAPPER;

    private final Path file;
    private final String description;
    private final int version;
    private final Supplier<T> empty;
    private final Function<JsonNode, T> reader;
    private final Function<T, ObjectNode> writer;
    private final Map<Integer, UnaryOperator<JsonNode>> steps = new HashMap<>();
    private ToIntFunction<JsonNode> versionOf = VersionedJsonFile::versionField;
    private UnaryOperator<JsonNode> redact = UnaryOperator.identity();
    private T snapshot;

    /**
     * {@code description} names the document in messages ("the device registry"). {@code reader} turns the current
     * version's document into a value and throws {@link IllegalArgumentException} for one it cannot use;
     * {@code writer} turns a value back into the document, without the version, which is added here.
     */
    public VersionedJsonFile(Path file, String description, int version, Supplier<T> empty,
                             Function<JsonNode, T> reader, Function<T, ObjectNode> writer) {
        this.file = file;
        this.description = description;
        this.version = version;
        this.empty = empty;
        this.reader = reader;
        this.writer = writer;
    }

    /** Registers the step from version {@code from} to {@code from + 1}, as a JSON tree. */
    public VersionedJsonFile<T> migrate(int from, UnaryOperator<JsonNode> step) {
        steps.put(from, step);
        return this;
    }

    /** For files that predate the version field: how to tell their version from their content. */
    public VersionedJsonFile<T> versionOf(ToIntFunction<JsonNode> lookup) {
        this.versionOf = lookup;
        return this;
    }

    /**
     * What the backup of an older version leaves out, such as credentials the new version keeps encrypted elsewhere.
     * Given a copy of the original tree; a backup it does not change keeps the original bytes.
     */
    public VersionedJsonFile<T> redactBackup(UnaryOperator<JsonNode> redaction) {
        this.redact = redaction;
        return this;
    }

    /** The {@code version} field of an object, or -1 when it has none. */
    public static int versionField(JsonNode root) {
        JsonNode field = root.path(VERSION_FIELD);
        return root.isObject() && field.isIntegralNumber() ? field.asInt() : -1;
    }

    public Path file() {
        return file;
    }

    public synchronized T read() {
        if (snapshot == null) {
            snapshot = load();
        }
        return snapshot;
    }

    public synchronized T update(UnaryOperator<T> change) {
        T next = change.apply(read());
        write(next);
        return next;
    }

    public synchronized void write(T value) {
        ObjectNode document = MAPPER.createObjectNode();
        document.put(VERSION_FIELD, version);
        writer.apply(value).properties().forEach(field -> {
            if (!VERSION_FIELD.equals(field.getKey())) {
                document.set(field.getKey(), field.getValue());
            }
        });
        try {
            AtomicFiles.write(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(document), true);
        } catch (IOException | JacksonException e) {
            throw new StorageException("Could not write " + description + " to " + file
                    + "; check that /data is bind-mounted and writable", e);
        }
        snapshot = value;
    }

    /** Removes the file and forgets the snapshot. Exists for the shared test context. */
    public synchronized void delete() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new StorageException("Could not delete " + file, e);
        }
        snapshot = null;
    }

    private T load() {
        if (!Files.exists(file)) {
            return empty.get();
        }
        byte[] original;
        JsonNode root;
        try {
            original = Files.readAllBytes(file);
            root = MAPPER.readTree(original);
        } catch (IOException | JacksonException e) {
            throw unreadable(e);
        }
        int found = root == null ? -1 : versionOf.applyAsInt(root);
        if (found > version) {
            throw new StorageException(description + " in " + file + " was written by a newer Home Control (version "
                    + found + "); upgrade Home Control or restore a backup");
        }
        T value;
        try {
            JsonNode migrated = root;
            for (int from = found; from < version; from++) {
                UnaryOperator<JsonNode> step = steps.get(from);
                if (step == null) {
                    throw new IllegalArgumentException("no migration from version " + from);
                }
                migrated = step.apply(migrated);
            }
            value = reader.apply(migrated);
        } catch (StorageException e) {
            throw e;
        } catch (RuntimeException e) {
            throw unreadable(e);
        }
        if (found < version) {
            backUp(original, root, found);
            write(value);
        }
        return value;
    }

    /** One-way migrations: the original is kept once for a rollback. An existing backup is the older, so it stays. */
    private void backUp(byte[] original, JsonNode root, int from) {
        String name = file.getFileName().toString();
        String base = name.endsWith(JSON) ? name.substring(0, name.length() - JSON.length()) : name;
        Path backup = file.resolveSibling(base + ".v" + from + JSON);
        if (Files.exists(backup)) {
            return;
        }
        try {
            JsonNode redacted = redact.apply(root.deepCopy());
            byte[] bytes = redacted.equals(root)
                    ? original : MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(redacted);
            AtomicFiles.write(backup, bytes, false);
        } catch (IOException e) {
            throw new StorageException("Could not keep " + file + " as " + backup
                    + " before migrating it; check that /data is bind-mounted and writable", e);
        }
    }

    private StorageException unreadable(Exception cause) {
        return new StorageException("Could not read " + description + " in " + file + "; fix or delete it", cause);
    }
}
