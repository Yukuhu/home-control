package dev.andre.homecontrol.adapters.webos;

import dev.andre.homecontrol.adapters.net.WakeOnLan;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.device.JsonFileDeviceRegistry;
import dev.andre.homecontrol.discovery.ssdp.SsdpDiscovery;
import dev.andre.homecontrol.discovery.ssdp.SsdpProperties;
import dev.andre.homecontrol.testsupport.InMemoryDeviceSecrets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Since 2B the client key is a device secret named by the entry's keyRef; entries that still hold it move it. */
class WebOsKeyMigrationTest {

    private static final String REF = "0123456789abcdef";

    @TempDir
    Path dir;

    private final InMemoryDeviceSecrets secrets = new InMemoryDeviceSecrets();

    private WebOsAdapter adapter(JsonFileDeviceRegistry registry) {
        WebOsProperties properties = new WebOsProperties(true, 3000, 3001, Duration.ofSeconds(2), Duration.ofSeconds(2),
                Duration.ofSeconds(2), Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ZERO);
        SsdpDiscovery ssdp = new SsdpDiscovery(new SsdpProperties(false, "127.0.0.1", 1900, 0, Duration.ofSeconds(60), 2));
        return new WebOsAdapter(properties, ssdp, registry, new WakeOnLan(new InetSocketAddress("127.0.0.1", 9)), secrets);
    }

    private WebOsAdapter adapter() {
        return adapter(new JsonFileDeviceRegistry(dir.resolve("devices.json")));
    }

    private static Device lg(Map<String, String> settings) {
        return new Device("lg-1", "LG", DeviceKind.WEBOS, "192.168.1.30", Map.of("webos", settings), Instant.now());
    }

    @Test
    void aKeyInTheRegistryMovesToADeviceSecretAtStartup() throws IOException {
        Path file = dir.resolve("devices.json");
        Files.copy(Path.of("src/test/resources/fixtures/devices/devices-v2-tv-keys.json"), file);
        JsonFileDeviceRegistry registry = new JsonFileDeviceRegistry(file);

        try (DeviceManager manager = new DeviceManager(registry, List.of(adapter(registry)), event -> { })) {
            manager.start();
        }

        Map<String, String> settings = new JsonFileDeviceRegistry(file).findById("lg-1").orElseThrow().adapterSettings("webos");
        assertThat(settings).doesNotContainKey("clientKey").containsKey("keyRef")
                .containsEntry("macAddress", "aa:bb:cc:dd:ee:ff");
        assertThat(secrets.deviceSecret(WebOsSettings.secretName(settings.get("keyRef")))).contains("lg-client-key");
        assertThat(Files.readString(file)).doesNotContain("lg-client-key").contains("sam-token");
    }

    @Test
    void migrationIsIdempotent() {
        WebOsAdapter adapter = adapter();

        Device once = adapter.migrate(lg(Map.of("clientKey", "lg-client-key")));
        Device twice = adapter.migrate(once);

        assertThat(twice).isEqualTo(once);
        assertThat(secrets.all()).hasSize(1);
    }

    @Test
    void aMigrationThatCrashedBeforeTheRegistryWasSavedReusesItsReference() {
        Device migrated = adapter().migrate(lg(Map.of("clientKey", "lg-client-key", "keyRef", REF)));

        assertThat(migrated.adapterSettings("webos")).containsEntry("keyRef", REF).doesNotContainKey("clientKey");
        assertThat(secrets.all()).containsOnlyKeys("device.webos." + REF + ".client-key");
    }

    @Test
    void theSettingsReadTheKeyFromTheSecretAndNeverPrintIt() {
        secrets.putDeviceSecret(WebOsSettings.secretName(REF), "the-key");

        WebOsSettings settings = WebOsSettings.of(lg(Map.of("keyRef", REF)), secrets);

        assertThat(settings.clientKey()).isEqualTo("the-key");
        assertThat(settings.toString()).doesNotContain("the-key").contains("clientKey=stored");
        assertThat(WebOsSettings.of(lg(Map.of()), secrets).clientKey()).isNull();
    }

    @Test
    void forgettingTheDeviceRemovesItsSecret() {
        secrets.putDeviceSecret(WebOsSettings.secretName(REF), "the-key");

        adapter().forget(lg(Map.of("keyRef", REF)));

        assertThat(secrets.all()).isEmpty();
    }

    @Test
    void forgettingOneOfTwoEntriesThatShareAReferenceKeepsTheKeyForTheOther() {
        secrets.putDeviceSecret(WebOsSettings.secretName(REF), "the-key");
        JsonFileDeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
        Device stale = new Device("lg-old", "LG (cast)", DeviceKind.WEBOS, "192.168.1.30",
                Map.of("webos", Map.of("keyRef", REF)), Instant.now());
        registry.save(stale);
        registry.save(lg(Map.of("keyRef", REF)));

        adapter(registry).forget(stale);

        assertThat(secrets.deviceSecret(WebOsSettings.secretName(REF))).contains("the-key");
    }

    @Test
    void aMergeCarriesTheReferenceSoThePairingStillWorks() {
        secrets.putDeviceSecret(WebOsSettings.secretName(REF), "the-key");
        JsonFileDeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
        registry.save(new Device("tv", "Living Room TV", DeviceKind.WEBOS, "192.168.1.31",
                Map.of("upnp", Map.of("location", "http://192.168.1.31:1400/xml")), Instant.now()));
        registry.save(lg(Map.of("keyRef", REF)));

        Device merged;
        try (DeviceManager manager = new DeviceManager(registry, List.of(adapter(registry)), event -> { })) {
            merged = manager.merge("tv", "lg-1");
        }

        assertThat(merged.adapterSettings("webos")).containsEntry("keyRef", REF);
        assertThat(WebOsSettings.of(merged, secrets).clientKey()).isEqualTo("the-key");
        assertThat(secrets.all()).hasSize(1);
    }
}
