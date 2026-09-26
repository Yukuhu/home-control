package dev.andre.homecontrol.device;

import dev.andre.homecontrol.adapters.androidtv.AndroidTvSettings;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceRegistry;
import dev.andre.homecontrol.storage.StorageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonFileDeviceRegistryTest {

    @TempDir
    Path dir;

    private Device shield() {
        return AndroidTvSettings.device("shield-1", "Living Room Shield", "192.168.1.50", 6466,
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
        registry.save(AndroidTvSettings.device("shield-1", "Renamed", "192.168.1.51", 6466,
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
        registry.save(AndroidTvSettings.device("shield-stale", "Old Address", "192.168.1.50", 6466,
                "AA:BB:CC", Instant.parse("2026-08-29T18:00:00Z")));
        // Saved second but has the MORE RECENT lastSeen — the freshly paired device,
        // e.g. after the physical device's address changed and it got a new id.
        registry.save(AndroidTvSettings.device("shield-fresh", "New Address", "192.168.1.60", 6466,
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

        var preparedReceiver86 = new JsonFileDeviceRegistry(file);
        assertThatThrownBy(() -> preparedReceiver86.findAll())
                .isInstanceOf(StorageException.class)
                .hasMessageContaining(file.toString())
                .hasMessageContaining("permissions");
    }

    @Test
    void nullRegistryDocumentIsAPathBearingStorageFailure() throws Exception {
        Path file = dir.resolve("devices.json");
        java.nio.file.Files.writeString(file, "null");

        var preparedReceiver97 = new JsonFileDeviceRegistry(file);
        assertThatThrownBy(() -> preparedReceiver97.findAll())
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

        var preparedReceiver116 = new JsonFileDeviceRegistry(file);
        assertThatThrownBy(() -> preparedReceiver116.findAll())
                .isInstanceOf(StorageException.class)
                .hasMessageContaining(file.toString())
                .hasMessageContaining("integrity");
    }

    @Test
    void migratesAVersionOneFileToTheAdapterShapeAndRewritesIt() throws Exception {
        Path file = dir.resolve("devices.json");
        Files.copy(Path.of("src/test/resources/devices-v1.json"), file);
        JsonFileDeviceRegistry registry = new JsonFileDeviceRegistry(file);

        List<Device> devices = registry.findAll();

        assertThat(devices).singleElement().satisfies(device -> {
            assertThat(device.id()).isEqualTo("192-168-1-50");
            assertThat(device.kind()).isEqualTo(DeviceKind.ANDROID_TV);
            assertThat(device.host()).isEqualTo("192.168.1.50");
            assertThat(device.adapterSettings("androidtv"))
                    .containsEntry("port", "6466")
                    .containsEntry("certificateFingerprint", "AB:CD");
            assertThat(device.lastSeen()).isEqualTo(Instant.parse("2026-08-29T18:00:00Z"));
        });
        String rewritten = Files.readString(file);
        assertThat(rewritten).contains("\"kind\":\"ANDROID_TV\"").contains("\"adapters\"")
                .doesNotContain("\"certificateFingerprint\":\"AB:CD\",\"lastSeen\"");

        // The rewritten file must be readable as v2 on its own, without going through
        // migration again (it already has "kind", so the migration branch is skipped).
        List<Device> reread = new JsonFileDeviceRegistry(file).findAll();
        assertThat(reread).isEqualTo(devices);
    }

    @Test
    void keepsTheVersionOneFileAsABackupBeforeRewritingIt() throws Exception {
        // The upgrade is one-way: an older image cannot read the rewritten file, so the
        // original bytes must survive next to it for a rollback.
        Path file = dir.resolve("devices.json");
        Files.copy(Path.of("src/test/resources/devices-v1.json"), file);
        byte[] original = Files.readAllBytes(file);

        new JsonFileDeviceRegistry(file).findAll();

        assertThat(dir.resolve("devices.v1.json")).exists().hasBinaryContent(original);
    }

    @Test
    void neverOverwritesAnExistingVersionOneBackup() throws Exception {
        Path file = dir.resolve("devices.json");
        Files.copy(Path.of("src/test/resources/devices-v1.json"), file);
        Path backup = dir.resolve("devices.v1.json");
        Files.writeString(backup, "the first backup");

        new JsonFileDeviceRegistry(file).findAll();

        assertThat(backup).hasContent("the first backup");
    }

    @Test
    void aVersionTwoFileIsNotBackedUp() {
        Path file = dir.resolve("devices.json");
        new JsonFileDeviceRegistry(file).save(shield());

        new JsonFileDeviceRegistry(file).findAll();

        assertThat(dir.resolve("devices.v1.json")).doesNotExist();
    }

    @Test
    void aVersionOneRecordWithoutAFingerprintMigratesWithoutOne() throws Exception {
        Path file = dir.resolve("devices.json");
        Files.writeString(file, "[{\"id\":\"x\",\"name\":\"X\",\"host\":\"10.0.0.9\",\"port\":6466,"
                + "\"certificateFingerprint\":null,\"lastSeen\":\"2026-08-29T18:00:00Z\"}]");

        Device device = new JsonFileDeviceRegistry(file).findAll().getFirst();

        assertThat(device.adapterSettings("androidtv")).containsEntry("port", "6466")
                .doesNotContainKey("certificateFingerprint");
    }

    @Test
    void aVersionOneRecordWithAnOutOfRangePortIsAPathBearingStorageFailure() throws Exception {
        Path file = dir.resolve("devices.json");
        Files.writeString(file, "[{\"id\":\"x\",\"name\":\"X\",\"host\":\"10.0.0.9\",\"port\":99999,"
                + "\"certificateFingerprint\":null,\"lastSeen\":\"2026-08-29T18:00:00Z\"}]");

        var preparedReceiver202 = new JsonFileDeviceRegistry(file);
        assertThatThrownBy(() -> preparedReceiver202.findAll())
                .isInstanceOf(StorageException.class)
                .hasMessageContaining(file.toString())
                .hasMessageContaining("integrity");
    }

    @Test
    void aVersionOneRecordWithANonNumericPortIsAPathBearingStorageFailure() throws Exception {
        Path file = dir.resolve("devices.json");
        Files.writeString(file, "[{\"id\":\"x\",\"name\":\"X\",\"host\":\"10.0.0.9\",\"port\":\"not-a-number\","
                + "\"certificateFingerprint\":null,\"lastSeen\":\"2026-08-29T18:00:00Z\"}]");

        var preparedReceiver214 = new JsonFileDeviceRegistry(file);
        assertThatThrownBy(() -> preparedReceiver214.findAll())
                .isInstanceOf(StorageException.class)
                .hasMessageContaining(file.toString())
                .hasMessageContaining("integrity");
    }

    @Test
    void aVersionOneRecordWithAMissingPortIsAPathBearingStorageFailure() throws Exception {
        Path file = dir.resolve("devices.json");
        Files.writeString(file, "[{\"id\":\"x\",\"name\":\"X\",\"host\":\"10.0.0.9\","
                + "\"certificateFingerprint\":null,\"lastSeen\":\"2026-08-29T18:00:00Z\"}]");

        var preparedReceiver226 = new JsonFileDeviceRegistry(file);
        assertThatThrownBy(() -> preparedReceiver226.findAll())
                .isInstanceOf(StorageException.class)
                .hasMessageContaining(file.toString())
                .hasMessageContaining("integrity");
    }

    @Test
    void aVersionTwoRecordWithAnInvalidAndroidTvPortIsAPathBearingStorageFailure() throws Exception {
        Path file = dir.resolve("devices.json");
        Files.writeString(file, """
                [{
                  "id": "x",
                  "name": "X",
                  "kind": "ANDROID_TV",
                  "host": "10.0.0.9",
                  "adapters": {"androidtv": {"port": "abc"}},
                  "lastSeen": "2026-08-29T18:00:00Z"
                }]
                """);

        var preparedReceiver246 = new JsonFileDeviceRegistry(file);
        assertThatThrownBy(() -> preparedReceiver246.findAll())
                .isInstanceOf(StorageException.class)
                .hasMessageContaining(file.toString())
                .hasMessageContaining("integrity");
    }

    @Test
    void aVersionTwoRecordWithAnInvalidCastPortIsAPathBearingStorageFailure() throws Exception {
        Path file = dir.resolve("devices.json");
        Files.writeString(file, """
                [{
                  "id": "cast-10-0-0-9",
                  "name": "Kitchen",
                  "kind": "CAST",
                  "host": "10.0.0.9",
                  "adapters": {"cast": {"port": "70000"}},
                  "lastSeen": "2026-08-29T18:00:00Z"
                }]
                """);

        var preparedReceiver266 = new JsonFileDeviceRegistry(file);
        assertThatThrownBy(() -> preparedReceiver266.findAll())
                .isInstanceOf(StorageException.class)
                .hasMessageContaining(file.toString())
                .hasMessageContaining("integrity");
    }
}
