package dev.andre.homecontrol.adapters.tizen;

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

/** Since 2B the token is a device secret named by the entry's keyRef; entries that still hold it move it. */
class TizenTokenMigrationTest {

    private static final String REF = "0123456789abcdef";

    @TempDir
    Path dir;

    private final InMemoryDeviceSecrets secrets = new InMemoryDeviceSecrets();

    private TizenAdapter adapter(JsonFileDeviceRegistry registry) {
        TizenProperties properties = new TizenProperties(true, 8002, 8001, 8080, "Home Control", Duration.ofSeconds(2),
                Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(60), Duration.ZERO);
        SsdpDiscovery ssdp = new SsdpDiscovery(new SsdpProperties(false, "127.0.0.1", 1900, 0, Duration.ofSeconds(60), 2));
        return new TizenAdapter(properties, ssdp, registry, new WakeOnLan(new InetSocketAddress("127.0.0.1", 9)), secrets);
    }

    private TizenAdapter adapter() {
        return adapter(new JsonFileDeviceRegistry(dir.resolve("devices.json")));
    }

    private static Device samsung(Map<String, String> settings) {
        return new Device("sam-1", "Samsung", DeviceKind.TIZEN, "192.168.1.31", Map.of("tizen", settings), Instant.now());
    }

    @Test
    void aTokenInTheRegistryMovesToADeviceSecretAtStartup() throws IOException {
        Path file = dir.resolve("devices.json");
        Files.copy(Path.of("src/test/resources/fixtures/devices/devices-v2-tv-keys.json"), file);
        JsonFileDeviceRegistry registry = new JsonFileDeviceRegistry(file);

        try (DeviceManager manager = new DeviceManager(registry, List.of(adapter(registry)), event -> { })) {
            manager.start();
        }

        Map<String, String> settings = new JsonFileDeviceRegistry(file).findById("sam-1").orElseThrow().adapterSettings("tizen");
        assertThat(settings).doesNotContainKey("token").containsKey("keyRef").containsEntry("paired", "true");
        assertThat(secrets.deviceSecret(TizenSettings.secretName(settings.get("keyRef")))).contains("sam-token");
        assertThat(Files.readString(file)).doesNotContain("sam-token").contains("lg-client-key");
    }

    @Test
    void migrationIsIdempotent() {
        TizenAdapter adapter = adapter();

        Device once = adapter.migrate(samsung(Map.of("paired", "true", "token", "sam-token")));
        Device twice = adapter.migrate(once);

        assertThat(twice).isEqualTo(once);
        assertThat(secrets.all()).hasSize(1);
    }

    @Test
    void aPairingWithoutATokenNeedsNoSecret() {
        Device device = samsung(Map.of("paired", "true"));

        assertThat(adapter().migrate(device)).isEqualTo(device);
        assertThat(secrets.all()).isEmpty();
    }

    @Test
    void theSettingsReadTheTokenFromTheSecretAndNeverPrintIt() {
        secrets.putDeviceSecret(TizenSettings.secretName(REF), "the-token");

        TizenSettings settings = TizenSettings.of(samsung(Map.of("paired", "true", "keyRef", REF)), secrets);

        assertThat(settings.token()).isEqualTo("the-token");
        assertThat(settings.paired()).isTrue();
        assertThat(settings.toString()).doesNotContain("the-token").contains("token=stored");
    }

    @Test
    void forgettingTheDeviceRemovesItsSecret() {
        secrets.putDeviceSecret(TizenSettings.secretName(REF), "the-token");

        adapter().forget(samsung(Map.of("paired", "true", "keyRef", REF)));

        assertThat(secrets.all()).isEmpty();
    }
}
