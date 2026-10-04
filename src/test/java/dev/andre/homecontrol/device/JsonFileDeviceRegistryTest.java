package dev.andre.homecontrol.device;

import dev.andre.homecontrol.adapters.androidtv.AndroidTvSettings;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceRegistry;
import dev.andre.homecontrol.storage.StorageException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

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

        var registry = new JsonFileDeviceRegistry(file);
        assertThatThrownBy(registry::findAll)
                .isInstanceOf(StorageException.class)
                .hasMessageContaining(file.toString())
                .hasMessageContaining("fix or delete it");
    }

    @Test
    void nullRegistryDocumentIsAPathBearingStorageFailure() throws Exception {
        Path file = dir.resolve("devices.json");
        java.nio.file.Files.writeString(file, "null");

        var registry = new JsonFileDeviceRegistry(file);
        assertThatThrownBy(registry::findAll)
                .isInstanceOf(StorageException.class)
                .hasMessageContaining(file.toString())
                .hasMessageContaining("fix or delete it");
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

        var registry = new JsonFileDeviceRegistry(file);
        assertThatThrownBy(registry::findAll)
                .isInstanceOf(StorageException.class)
                .hasMessageContaining(file.toString())
                .hasMessageContaining("fix or delete it");
    }

    @ParameterizedTest(name = "{1}")
    @CsvSource(delimiter = '|', value = {
            "null| record is null",
            "{\"id\":\"tv\",\"host\":\"10.0.0.9\",\"kind\":\"WEBOS\",\"lastSeen\":\"2026-08-29T18:00:00Z\"}| name is required",
            "{\"id\":\"tv\",\"name\":\"TV\",\"host\":\" \",\"kind\":\"WEBOS\",\"lastSeen\":\"2026-08-29T18:00:00Z\"}| host is required",
            "{\"id\":\"tv\",\"name\":\"TV\",\"host\":\"10.0.0.9\",\"lastSeen\":\"2026-08-29T18:00:00Z\"}| kind is required",
            "{\"id\":\"tv\",\"name\":\"TV\",\"host\":\"10.0.0.9\",\"kind\":\"WEBOS\"}| lastSeen is required"})
    void aRecordMissingWhatEveryDeviceHasNamesTheField(String entry, String reason) throws Exception {
        Path file = dir.resolve("devices.json");
        Files.writeString(file, "{\"version\":3,\"devices\":[" + entry + "]}");

        var registry = new JsonFileDeviceRegistry(file);
        assertThatThrownBy(registry::findAll)
                .isInstanceOf(StorageException.class)
                .hasMessageContaining(file.toString())
                .hasStackTraceContaining("invalid device record at index 0: " + reason);
    }

    @Test
    void devicesThatAreNoListAreAPathBearingStorageFailure() throws Exception {
        Path file = dir.resolve("devices.json");
        Files.writeString(file, "{\"version\":3,\"devices\":{}}");

        var registry = new JsonFileDeviceRegistry(file);
        assertThatThrownBy(registry::findAll)
                .isInstanceOf(StorageException.class)
                .hasMessageContaining(file.toString())
                .hasStackTraceContaining("devices must be a JSON array");
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
        JsonNode rewritten = JsonMapper.builder().build().readTree(Files.readAllBytes(file));
        assertThat(rewritten.path("version").asInt()).isEqualTo(3);
        JsonNode entry = rewritten.path("devices").path(0);
        assertThat(entry.path("kind").asString()).isEqualTo("ANDROID_TV");
        assertThat(entry.path("adapters").path("androidtv").path("certificateFingerprint").asString()).isEqualTo("AB:CD");
        assertThat(entry.has("certificateFingerprint")).isFalse();

        // The rewritten file must be readable on its own, without going through migration again.
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
    void aVersionThreeFileIsNotBackedUp() throws Exception {
        Path file = dir.resolve("devices.json");
        new JsonFileDeviceRegistry(file).save(shield());

        new JsonFileDeviceRegistry(file).findAll();

        try (var files = Files.list(dir)) {
            assertThat(files.map(path -> path.getFileName().toString())).containsExactly("devices.json");
        }
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

    @ParameterizedTest(name = "port field: {0}")
    @ValueSource(strings = {"\"port\":99999,", "\"port\":\"not-a-number\",", ""})
    void aVersionOneRecordWithAnUnusablePortIsAPathBearingStorageFailure(String portField) throws Exception {
        Path file = dir.resolve("devices.json");
        Files.writeString(file, "[{\"id\":\"x\",\"name\":\"X\",\"host\":\"10.0.0.9\"," + portField
                + "\"certificateFingerprint\":null,\"lastSeen\":\"2026-08-29T18:00:00Z\"}]");

        var registry = new JsonFileDeviceRegistry(file);
        assertThatThrownBy(registry::findAll)
                .isInstanceOf(StorageException.class)
                .hasMessageContaining(file.toString())
                .hasMessageContaining("fix or delete it");
    }

    @Test
    void aVersionTwoFileIsWrappedIntoVersionThreeAndKeptAsABackup() throws Exception {
        Path file = dir.resolve("devices.json");
        Files.copy(Path.of("src/test/resources/fixtures/devices/devices-v2.json"), file);
        String original = Files.readString(file);

        List<Device> devices = new JsonFileDeviceRegistry(file).findAll();

        assertThat(devices).extracting(Device::id).containsExactly("shield-1");
        assertThat(devices.getFirst().adapterSettings("cast")).containsEntry("castId", "abc");
        JsonNode root = JsonMapper.builder().build().readTree(Files.readAllBytes(file));
        assertThat(root.path("version").asInt()).isEqualTo(3);
        assertThat(root.path("devices").isArray()).isTrue();
        assertThat(dir.resolve("devices.v2.json")).hasContent(original);
    }

    @Test
    void theBackupLeavesOutTheTvKeysThatNowLiveOnlyInSecrets() throws Exception {
        Path file = dir.resolve("devices.json");
        Files.copy(Path.of("src/test/resources/fixtures/devices/devices-v2-tv-keys.json"), file);

        new JsonFileDeviceRegistry(file).findAll();

        String backup = Files.readString(dir.resolve("devices.v2.json"));
        assertThat(backup).doesNotContain("lg-client-key").doesNotContain("sam-token")
                .contains("lg-1").contains("aa:bb:cc:dd:ee:ff").contains("\"paired\"");
        assertThat(Files.readString(file)).contains("lg-client-key"); // moved to secrets by the adapter at startup
    }

    @Test
    void theRegistryIsReadOnceAndThenServedFromMemory() throws Exception {
        Path file = dir.resolve("devices.json");
        JsonFileDeviceRegistry registry = new JsonFileDeviceRegistry(file);
        registry.save(shield());

        Files.writeString(file, "not json any more");

        assertThat(registry.findAll()).extracting(Device::id).containsExactly("shield-1");
    }

    @Test
    void aNewerRegistryIsRefusedByName() throws Exception {
        Path file = dir.resolve("devices.json");
        Files.writeString(file, "{\"version\":4,\"devices\":[]}");

        assertThatThrownBy(new JsonFileDeviceRegistry(file)::findAll)
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("newer Home Control");
    }
}
