package dev.andre.shield.device;

import dev.andre.shield.ShieldProperties;
import dev.andre.shield.protocol.CertificateStore;
import dev.andre.shield.protocol.FakeRemoteServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class DeviceSessionManagerTest {

    @TempDir
    Path dir;

    /**
     * A re-pair at a changed address leaves the old entry behind (spec §6 derives the id
     * from the host). Since {@link DeviceStateChangedEvent} carries no device id, a session
     * for the stale entry would fan its DISCONNECTED events out to every tab and overwrite
     * the live device's badge — so only the active device gets a session.
     */
    @Test
    void startsASessionOnlyForTheActiveDevice() throws Exception {
        try (FakeRemoteServer staleAddress = new FakeRemoteServer();
             FakeRemoteServer currentAddress = new FakeRemoteServer()) {

            DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
            registry.save(new Device("shield-stale", "Shield", "127.0.0.1", staleAddress.port(),
                    null, Instant.parse("2026-08-29T18:00:00Z")));
            registry.save(new Device("shield-current", "Shield", "127.0.0.1", currentAddress.port(),
                    null, Instant.parse("2026-08-29T19:00:00Z")));

            ShieldProperties properties = new ShieldProperties(dir, "shield", false, 10, 1, 4);
            CertificateStore certificates = new CertificateStore(
                    properties.keystoreFile(), "shield".toCharArray());
            certificates.loadOrCreate("shield-current");
            try (DeviceSessionManager manager = new DeviceSessionManager(registry,
                    certificates,
                    properties, event -> {
            })) {
                manager.startRegisteredDevices();

                await().until(() -> manager.state().status() == DeviceStatus.CONNECTED);

                assertThat(manager.activeDevice()).get()
                        .extracting(Device::id).isEqualTo("shield-current");
                assertThat(staleAddress.connections())
                        .as("the stale entry must not get a session of its own")
                        .isZero();
            }
        }
    }

    @Test
    void registryOnlyDeviceIsUnpairedWithoutConnectionOrCredentialCreation() throws Exception {
        try (FakeRemoteServer remote = new FakeRemoteServer()) {
            DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
            registry.save(new Device("shield-missing-key", "Shield", "127.0.0.1", remote.port(),
                    null, Instant.now()));
            ShieldProperties properties = new ShieldProperties(dir, "shield", false, 10, 1, 4);
            CertificateStore certificates = new CertificateStore(
                    properties.keystoreFile(), "shield".toCharArray());

            try (DeviceSessionManager manager = new DeviceSessionManager(
                    registry, certificates, properties, event -> { })) {
                manager.startRegisteredDevices();

                assertThat(manager.state().status()).isEqualTo(DeviceStatus.UNPAIRED);
                assertThat(remote.connections()).isZero();
                assertThat(certificates.load("shield-missing-key")).isEmpty();
                assertThat(Files.exists(properties.keystoreFile())).isFalse();
            }
        }
    }

    @Test
    void forgetDeletesTheRegistryRecordAndOnlyItsCredential() {
        DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
        Device forgotten = new Device("shield-forgotten", "Shield", "127.0.0.1", 6466,
                null, Instant.now());
        registry.save(forgotten);
        ShieldProperties properties = new ShieldProperties(dir, "shield", false, 10, 1, 4);
        CertificateStore certificates = new CertificateStore(
                properties.keystoreFile(), "shield".toCharArray());
        certificates.loadOrCreate(forgotten.certificateAlias());
        certificates.loadOrCreate("keep-this-alias");

        try (DeviceSessionManager manager = new DeviceSessionManager(
                registry, certificates, properties, event -> { })) {
            manager.forget(forgotten.id());
        }

        assertThat(registry.findById(forgotten.id())).isEmpty();
        assertThat(certificates.load(forgotten.certificateAlias())).isEmpty();
        assertThat(certificates.load("keep-this-alias")).isPresent();
    }
}
