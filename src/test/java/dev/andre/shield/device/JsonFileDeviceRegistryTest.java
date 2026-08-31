package dev.andre.shield.device;

import dev.andre.shield.storage.StorageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void firstReturnsTheMostRecentlyPairedDeviceNotTheFirstOneOnFile() {
        DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
        // Saved first (so it is first in file order) but has the OLDER lastSeen —
        // if first() just returned file order, this would win, incorrectly.
        registry.save(new Device("shield-stale", "Old Address", "192.168.1.50", 6466,
                "AA:BB:CC", Instant.parse("2026-08-29T18:00:00Z")));
        // Saved second but has the MORE RECENT lastSeen — the freshly paired device,
        // e.g. after the physical device's address changed and it got a new id.
        registry.save(new Device("shield-fresh", "New Address", "192.168.1.60", 6466,
                "DD:EE:FF", Instant.parse("2026-08-29T19:00:00Z")));

        assertThat(registry.first()).get().extracting(Device::id).isEqualTo("shield-fresh");
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

    @Test
    void malformedRegistryIsAStorageFailureNotAnEmptyRegistry() throws Exception {
        Path file = dir.resolve("devices.json");
        java.nio.file.Files.writeString(file, "{not-json");

        assertThatThrownBy(() -> new JsonFileDeviceRegistry(file).findAll())
                .isInstanceOf(StorageException.class)
                .hasMessageContaining(file.toString())
                .hasMessageContaining("permissions");
    }

    @Test
    void nullRegistryDocumentIsAPathBearingStorageFailure() throws Exception {
        Path file = dir.resolve("devices.json");
        java.nio.file.Files.writeString(file, "null");

        assertThatThrownBy(() -> new JsonFileDeviceRegistry(file).findAll())
                .isInstanceOf(StorageException.class)
                .hasMessageContaining(file.toString())
                .hasMessageContaining("integrity");
    }

    @Test
    void incompleteDeviceRecordIsAPathBearingStorageFailure() throws Exception {
        Path file = dir.resolve("devices.json");
        java.nio.file.Files.writeString(file, """
                [{
                  "name": "Living Room Shield",
                  "host": "192.168.1.50",
                  "port": 6466,
                  "certificateFingerprint": "AA:BB:CC",
                  "lastSeen": "2026-08-29T18:00:00Z"
                }]
                """);

        assertThatThrownBy(() -> new JsonFileDeviceRegistry(file).findAll())
                .isInstanceOf(StorageException.class)
                .hasMessageContaining(file.toString())
                .hasMessageContaining("integrity");
    }
}
