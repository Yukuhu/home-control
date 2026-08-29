package dev.andre.shield.device;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.type.TypeReference;
import tools.jackson.core.JacksonException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Registry backed by a small JSON file, written atomically via a temp file and rename. */
public class JsonFileDeviceRegistry implements DeviceRegistry {

    private final ObjectMapper mapper = new ObjectMapper();
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
            throw new IllegalStateException("Could not read " + file, e);
        }
    }

    @Override
    public Optional<Device> findById(String id) {
        return findAll().stream().filter(device -> device.id().equals(id)).findFirst();
    }

    @Override
    public Optional<Device> first() {
        return findAll().stream().findFirst();
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
        try {
            Path parent = file.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Path temp = Files.createTempFile(parent, "devices", ".json");
            Files.write(temp, mapper.writeValueAsBytes(devices));
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | JacksonException e) {
            throw new IllegalStateException("Could not write " + file, e);
        }
    }
}
