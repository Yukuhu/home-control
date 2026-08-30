package dev.andre.shield.device;

import dev.andre.shield.storage.StorageException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.type.TypeReference;
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
            return mapper.readValue(Files.readAllBytes(file), new TypeReference<List<Device>>() {
            });
        } catch (IOException | JacksonException e) {
            throw new StorageException(
                    "Could not read device registry " + file
                            + "; check file permissions and JSON integrity",
                    e);
        }
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
                } catch (IOException ignored) {
                    // Cleanup error; let the original exception propagate
                }
            }
            throw new StorageException(
                    "Could not write device registry " + file + "; check file permissions",
                    e);
        }
    }
}
