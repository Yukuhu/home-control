package dev.andre.homecontrol.device;

import dev.andre.homecontrol.adapters.androidtv.AndroidTvAdapter;
import dev.andre.homecontrol.adapters.androidtv.AndroidTvProperties;
import dev.andre.homecontrol.adapters.androidtv.AndroidTvSettings;
import dev.andre.homecontrol.adapters.androidtv.MdnsDiscovery;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceRegistry;
import dev.andre.homecontrol.core.DeviceStateChangedEvent;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.adapters.androidtv.protocol.CertificateStore;
import dev.andre.homecontrol.adapters.androidtv.protocol.FakeRemoteServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

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
            registry.save(AndroidTvSettings.device("shield-stale", "Shield", "127.0.0.1", staleAddress.port(),
                    null, Instant.parse("2026-08-29T18:00:00Z")));
            registry.save(AndroidTvSettings.device("shield-current", "Shield", "127.0.0.1", currentAddress.port(),
                    null, Instant.parse("2026-08-29T19:00:00Z")));

            AndroidTvProperties properties = new AndroidTvProperties(dir, "shield", false, 10, 1, 4);
            CertificateStore certificates = new CertificateStore(
                    properties.keystoreFile(), "shield".toCharArray());
            certificates.loadOrCreate("shield-current");
            try (DeviceSessionManager manager = new DeviceSessionManager(registry,
                    new AndroidTvAdapter(certificates, properties, new MdnsDiscovery(false)),
                    event -> {
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
            registry.save(AndroidTvSettings.device("shield-missing-key", "Shield", "127.0.0.1", remote.port(),
                    null, Instant.now()));
            AndroidTvProperties properties = new AndroidTvProperties(dir, "shield", false, 10, 1, 4);
            CertificateStore certificates = new CertificateStore(
                    properties.keystoreFile(), "shield".toCharArray());

            try (DeviceSessionManager manager = new DeviceSessionManager(
                    registry, new AndroidTvAdapter(certificates, properties, new MdnsDiscovery(false)),
                    event -> { })) {
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
        Device forgotten = AndroidTvSettings.device("shield-forgotten", "Shield", "127.0.0.1", 6466,
                null, Instant.now());
        registry.save(forgotten);
        AndroidTvProperties properties = new AndroidTvProperties(dir, "shield", false, 10, 1, 4);
        CertificateStore certificates = new CertificateStore(
                properties.keystoreFile(), "shield".toCharArray());
        certificates.loadOrCreate(AndroidTvSettings.certificateAlias(forgotten));
        certificates.loadOrCreate("keep-this-alias");

        try (DeviceSessionManager manager = new DeviceSessionManager(
                registry, new AndroidTvAdapter(certificates, properties, new MdnsDiscovery(false)),
                event -> { })) {
            manager.forget(forgotten.id());
        }

        assertThat(registry.findById(forgotten.id())).isEmpty();
        assertThat(certificates.load(AndroidTvSettings.certificateAlias(forgotten))).isEmpty();
        assertThat(certificates.load("keep-this-alias")).isPresent();
    }

    @Test
    void forgetUnknownDevicePreservesItsOrphanedCredential() {
        DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
        AndroidTvProperties properties = new AndroidTvProperties(dir, "shield", false, 10, 1, 4);
        CertificateStore certificates = new CertificateStore(
                properties.keystoreFile(), "shield".toCharArray());
        certificates.loadOrCreate("orphaned-alias");

        try (DeviceSessionManager manager = new DeviceSessionManager(
                registry, new AndroidTvAdapter(certificates, properties, new MdnsDiscovery(false)),
                event -> { })) {
            manager.forget("orphaned-alias");
        }

        assertThat(certificates.load("orphaned-alias")).isPresent();
    }

    @Test
    void forgetPublishesTheReplacementState() throws Exception {
        try (FakeRemoteServer remote = new FakeRemoteServer()) {
            DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
            Device forgotten = AndroidTvSettings.device("shield-forgotten", "Shield", "127.0.0.1",
                    remote.port(), null, Instant.now());
            registry.save(forgotten);
            AndroidTvProperties properties = new AndroidTvProperties(dir, "shield", false, 10, 1, 4);
            CertificateStore certificates = new CertificateStore(
                    properties.keystoreFile(), "shield".toCharArray());
            certificates.loadOrCreate(AndroidTvSettings.certificateAlias(forgotten));
            List<Object> published = new CopyOnWriteArrayList<>();
            ApplicationEventPublisher publisher = published::add;

            try (DeviceSessionManager manager = new DeviceSessionManager(
                    registry, new AndroidTvAdapter(certificates, properties, new MdnsDiscovery(false)),
                    publisher)) {
                manager.startRegisteredDevices();
                await().until(() -> manager.state().status() == DeviceStatus.CONNECTED);
                remote.pushCurrentApp("com.netflix.ninja");
                await().untilAsserted(() -> assertThat(manager.state().currentApp())
                        .isEqualTo("com.netflix.ninja"));
                published.clear();

                manager.forget(forgotten.id());
            }

            assertThat(published).singleElement()
                    .isInstanceOfSatisfying(DeviceStateChangedEvent.class, event -> {
                        assertThat(event.state().status()).isEqualTo(DeviceStatus.DISCONNECTED);
                        assertThat(event.state().currentApp()).isNull();
                    });
        }
    }
}
