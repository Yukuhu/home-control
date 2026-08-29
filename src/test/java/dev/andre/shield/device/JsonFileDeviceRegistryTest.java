package dev.andre.shield.device;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class JsonFileDeviceRegistryTest {

    @TempDir
    Path dir;

    private Device shield() {
        return new Device("shield-1", "Living Room Shield", "192.168.1.50", 6466,
                "AA:BB:CC", Instant.parse("2026-08-29T18:00:00Z"));
    }

    @Test
    void savesAndReadsBackADevice() {
        Path file = dir.resolve("devices.json");
        new JsonFileDeviceRegistry(file).save(shield());

        DeviceRegistry reopened = new JsonFileDeviceRegistry(file);

        assertThat(reopened.findById("shield-1")).contains(shield());
    }

    @Test
    void replacesADeviceWithTheSameId() {
        DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
        registry.save(shield());
        registry.save(new Device("shield-1", "Renamed", "192.168.1.51", 6466,
                "AA:BB:CC", Instant.parse("2026-08-29T19:00:00Z")));

        assertThat(registry.findAll()).hasSize(1);
        assertThat(registry.findById("shield-1")).get()
                .extracting(Device::host).isEqualTo("192.168.1.51");
    }

    @Test
    void deletesADevice() {
        DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
        registry.save(shield());
        registry.delete("shield-1");

        assertThat(registry.findAll()).isEmpty();
        assertThat(registry.first()).isEmpty();
    }

    @Test
    void startsEmptyWhenTheFileDoesNotExist() {
        assertThat(new JsonFileDeviceRegistry(dir.resolve("missing.json")).findAll()).isEmpty();
    }
}
