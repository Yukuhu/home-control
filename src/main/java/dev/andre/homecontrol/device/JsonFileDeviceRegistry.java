package dev.andre.homecontrol.device;

import dev.andre.homecontrol.config.Json;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceRegistry;
import dev.andre.homecontrol.storage.VersionedJsonFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Registry backed by devices.json, version 3: {@code {"version": 3, "devices": [...]}}. It is read once, then served
 * from memory and written through. Versions 1 (v0.3's single Android TV) and 2 were bare arrays. Each adapter checks
 * its own settings when Devices starts.
 */
public class JsonFileDeviceRegistry implements DeviceRegistry {

    private static final int VERSION = 3;
    private static final String DEVICES = "devices";
    private static final String ADAPTERS = "adapters";
    private static final JsonMapper MAPPER = Json.MAPPER;

    private final VersionedJsonFile<List<Device>> file;

    public JsonFileDeviceRegistry(Path path) {
        this.file = new VersionedJsonFile<>(path, "the device registry", VERSION, List::of,
                JsonFileDeviceRegistry::readDevices, JsonFileDeviceRegistry::writeDevices)
                .versionOf(JsonFileDeviceRegistry::versionOf)
                .migrate(1, JsonFileDeviceRegistry::migrateVersionOne)
                .migrate(2, JsonFileDeviceRegistry::wrap)
                .redactBackup(JsonFileDeviceRegistry::withoutTvKeys);
    }

    /**
     * The webOS client keys and Tizen tokens that versions 1 and 2 kept in plain text. Since version 3 they are
     * device secrets, encrypted in secrets.json, so the backup of an older registry leaves them out.
     */
    private static JsonNode withoutTvKeys(JsonNode array) {
        for (JsonNode device : array) {
            if (device.path(ADAPTERS).path("webos") instanceof ObjectNode webos) {
                webos.remove("clientKey");
            }
            if (device.path(ADAPTERS).path("tizen") instanceof ObjectNode tizen) {
                tizen.remove("token");
            }
        }
        return array;
    }

    /** A bare array is version 1 when an element has no kind (v0.3), else version 2. */
    private static int versionOf(JsonNode root) {
        if (!root.isArray()) {
            return VersionedJsonFile.versionField(root);
        }
        for (JsonNode element : root) {
            if (element.isObject() && !element.has("kind")) {
                return 1;
            }
        }
        return 2;
    }

    private static JsonNode migrateVersionOne(JsonNode array) {
        ArrayNode migrated = MAPPER.createArrayNode();
        for (JsonNode element : array) {
            migrated.add(element.isObject() && !element.has("kind") ? migrateDevice((ObjectNode) element) : element);
        }
        return migrated;
    }

    /**
     * v0.3 wrote {@code {id, name, host, port, certificateFingerprint, lastSeen}} for the one
     * Android TV. v2 keeps id/name/host/lastSeen and moves the rest under
     * {@code adapters.androidtv} — the certificate alias is still the id, so the keystore is
     * untouched and nobody re-pairs (spec §8).
     */
    private static ObjectNode migrateDevice(ObjectNode v1) {
        ObjectNode androidtv = MAPPER.createObjectNode();
        androidtv.put("port", String.valueOf(requireValidPort(v1.get("port"))));
        JsonNode fingerprint = v1.get("certificateFingerprint");
        if (fingerprint != null && !fingerprint.isNull()) {
            androidtv.put("certificateFingerprint", fingerprint.asString());
        }
        ObjectNode adapters = MAPPER.createObjectNode();
        adapters.set("androidtv", androidtv);

        ObjectNode v2 = MAPPER.createObjectNode();
        v2.set("id", v1.get("id"));
        v2.set("name", v1.get("name"));
        v2.put("kind", "ANDROID_TV");
        v2.set("host", v1.get("host"));
        v2.set(ADAPTERS, adapters);
        v2.set("lastSeen", v1.get("lastSeen"));
        return v2;
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

    private static JsonNode wrap(JsonNode array) {
        ObjectNode root = MAPPER.createObjectNode();
        root.set(DEVICES, array);
        return root;
    }

    private static List<Device> readDevices(JsonNode root) {
        JsonNode array = root.path(DEVICES);
        if (!array.isArray()) {
            throw new IllegalArgumentException("devices must be a JSON array");
        }
        List<Device> devices = array.valueStream()
                .map(node -> MAPPER.treeToValue(node, Device.class))
                .collect(Collectors.toCollection(ArrayList::new));
        validateDevices(devices);
        return List.copyOf(devices);
    }

    private static ObjectNode writeDevices(List<Device> devices) {
        ObjectNode root = MAPPER.createObjectNode();
        root.set(DEVICES, MAPPER.valueToTree(devices));
        return root;
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
    }

    private static IllegalArgumentException invalidDevice(int index, String reason) {
        return new IllegalArgumentException("invalid device record at index " + index + ": " + reason);
    }

    @Override
    public List<Device> findAll() {
        return file.read();
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
    public void save(Device device) {
        file.update(devices -> {
            List<Device> next = new ArrayList<>(devices);
            next.removeIf(existing -> existing.id().equals(device.id()));
            next.add(device);
            return List.copyOf(next);
        });
    }

    @Override
    public void delete(String id) {
        file.update(devices -> devices.stream().filter(existing -> !existing.id().equals(id)).toList());
    }
}
