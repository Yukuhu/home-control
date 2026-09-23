package dev.andre.homecontrol.device;

import dev.andre.homecontrol.adapters.androidtv.AndroidTvSettings;
import dev.andre.homecontrol.adapters.cast.CastSettings;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceRegistry;
import dev.andre.homecontrol.storage.StorageException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.core.JacksonException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Registry backed by a small JSON file, written atomically via a temp file and rename. */
public class JsonFileDeviceRegistry implements DeviceRegistry {

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final Path file;

    public JsonFileDeviceRegistry(Path file) {
        this.file = file;
    }

    @Override
    public synchronized List<Device> findAll() {
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            JsonNode root = mapper.readTree(Files.readAllBytes(file));
            if (root == null || !root.isArray()) {
                throw new IllegalArgumentException("registry document must be a JSON array");
            }
            List<Device> devices = new ArrayList<>();
            boolean migrated = false;
            for (JsonNode node : root) {
                if (node.isObject() && !node.has("kind")) {
                    node = migrateVersionOne((ObjectNode) node);
                    migrated = true;
                }
                devices.add(mapper.treeToValue(node, Device.class));
            }
            validateDevices(devices);
            if (migrated) {
                backUpVersionOne();
                writeAll(devices);
            }
            return devices;
        } catch (IOException | JacksonException | IllegalArgumentException e) {
            throw new StorageException(
                    "Could not read device registry " + file
                            + "; check file permissions and JSON integrity",
                    e);
        }
    }

    /**
     * v0.3 wrote {@code {id, name, host, port, certificateFingerprint, lastSeen}} for the one
     * Android TV. v2 keeps id/name/host/lastSeen and moves the rest under
     * {@code adapters.androidtv} — the certificate alias is still the id, so the keystore is
     * untouched and nobody re-pairs (spec §8).
     */
    private ObjectNode migrateVersionOne(ObjectNode v1) {
        ObjectNode androidtv = mapper.createObjectNode();
        androidtv.put("port", String.valueOf(requireValidPort(v1.get("port"))));
        JsonNode fingerprint = v1.get("certificateFingerprint");
        if (fingerprint != null && !fingerprint.isNull()) {
            androidtv.put("certificateFingerprint", fingerprint.asString());
        }
        ObjectNode adapters = mapper.createObjectNode();
        adapters.set("androidtv", androidtv);

        ObjectNode v2 = mapper.createObjectNode();
        v2.set("id", v1.get("id"));
        v2.set("name", v1.get("name"));
        v2.put("kind", "ANDROID_TV");
        v2.set("host", v1.get("host"));
        v2.set("adapters", adapters);
        v2.set("lastSeen", v1.get("lastSeen"));
        return v2;
    }

    /**
     * The migration is one-way — an older image cannot read the rewritten file — so the
     * original is copied to {@code devices.v1.json} beside it first, for a rollback. Only once:
     * an existing backup is the older, and so the more valuable, of the two.
     */
    private void backUpVersionOne() throws IOException {
        Path backup = file.resolveSibling("devices.v1.json");
        if (!Files.exists(backup)) {
            Files.copy(file, backup);
        }
    }

    /**
     * v0.3 never validated the port it wrote, so a hand-edited or corrupted file could carry
     * a missing, non-numeric, or out-of-range value. Migrating it as-is would just move the
     * bad value under {@code adapters.androidtv}, where nothing catches it until a connection
     * attempt fails with a raw {@code NumberFormatException} — so it is rejected here instead,
     * at the same point v0.3's own port-range check used to run.
     */
    private static int requireValidPort(JsonNode portNode) {
        if (portNode == null || portNode.isNull() || !portNode.canConvertToInt()) {
            throw new IllegalArgumentException("port must be an integer between 1 and 65535");
        }
        int port = portNode.asInt();
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port must be an integer between 1 and 65535");
        }
        return port;
    }

    private static void validateDevices(List<Device> devices) {
        for (int index = 0; index < devices.size(); index++) {
            validateDevice(devices.get(index), index);
        }
    }

    private static void validateDevice(Device device, int index) {
        if (device == null) {
            throw invalidDevice(index, "record is null");
        }
        if (device.id() == null || device.id().isBlank()) {
            throw invalidDevice(index, "id is required");
        }
        if (device.name() == null || device.name().isBlank()) {
            throw invalidDevice(index, "name is required");
        }
        if (device.host() == null || device.host().isBlank()) {
            throw invalidDevice(index, "host is required");
        }
        if (device.kind() == null) {
            throw invalidDevice(index, "kind is required");
        }
        if (device.lastSeen() == null) {
            throw invalidDevice(index, "lastSeen is required");
        }
        if (device.hasAdapter(AndroidTvSettings.ADAPTER_ID)) {
            try {
                AndroidTvSettings.of(device);
            } catch (IllegalArgumentException _) {
                throw invalidDevice(index, "androidtv port must be an integer between 1 and 65535");
            }
        }
        if (device.hasAdapter(CastSettings.ADAPTER_ID)) {
            try {
                CastSettings.of(device);
            } catch (IllegalArgumentException _) {
                throw invalidDevice(index, "cast port must be an integer between 1 and 65535");
            }
        }
    }

    private static IllegalArgumentException invalidDevice(int index, String reason) {
        return new IllegalArgumentException("invalid device record at index " + index + ": " + reason);
    }

    @Override
    public Optional<Device> findById(String id) {
        return findAll().stream().filter(device -> device.id().equals(id)).findFirst();
    }

    /**
     * The most recently paired device, by {@link Device#lastSeen}, not file order.
     * A re-pair at a changed address gets a new id (spec §6) and so a new entry
     * alongside the stale one; the freshly paired device must win.
     */
    @Override
    public Optional<Device> first() {
        return findAll().stream().max(Comparator.comparing(Device::lastSeen));
    }

    @Override
    public synchronized void save(Device device) {
        List<Device> devices = new ArrayList<>(findAll());
        devices.removeIf(existing -> existing.id().equals(device.id()));
        devices.add(device);
        writeAll(devices);
    }

    @Override
    public synchronized void delete(String id) {
        List<Device> devices = new ArrayList<>(findAll());
        devices.removeIf(existing -> existing.id().equals(id));
        writeAll(devices);
    }

    private void writeAll(List<Device> devices) {
        Path parent = file.toAbsolutePath().getParent();
        Path temp = null;
        try {
            Files.createDirectories(parent);
            temp = Files.createTempFile(parent, "devices", ".json");
            Files.write(temp, mapper.writeValueAsBytes(devices));
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | JacksonException e) {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException _) {
                    // Cleanup error; let the original exception propagate
                }
            }
            throw new StorageException(
                    "Could not write device registry " + file + "; check file permissions",
                    e);
        }
    }
}
