package dev.andre.homecontrol.device;

import dev.andre.homecontrol.adapters.androidtv.AndroidTvAdapter;
import dev.andre.homecontrol.adapters.androidtv.AndroidTvProperties;
import dev.andre.homecontrol.adapters.androidtv.AndroidTvSettings;
import dev.andre.homecontrol.adapters.androidtv.MdnsDiscovery;
import dev.andre.homecontrol.adapters.androidtv.protocol.CertificateStore;
import dev.andre.homecontrol.adapters.androidtv.protocol.FakeRemoteServer;
import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceAdapter;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceNotFoundException;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DeviceRegistry;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStateChangedEvent;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.DiscoveredDevice;
import dev.andre.homecontrol.core.RemoteKey;
import dev.andre.homecontrol.core.UnsupportedActionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class DeviceManagerTest {

    @TempDir
    Path dir;

    private final List<Object> published = new CopyOnWriteArrayList<>();
    private final ApplicationEventPublisher publisher = published::add;

    private AndroidTvProperties properties() {
        return new AndroidTvProperties(dir, "shield", false, 10, 1, 4);
    }

    private CertificateStore certificates() {
        return new CertificateStore(properties().keystoreFile(), "shield".toCharArray());
    }

    private DeviceManager manager(DeviceRegistry registry, CertificateStore certificates) {
        return new DeviceManager(registry,
                List.of(new AndroidTvAdapter(certificates, properties(), new MdnsDiscovery(false))),
                publisher);
    }

    @Test
    void connectsEveryRegisteredDeviceAndReportsStatePerId() throws Exception {
        try (FakeRemoteServer living = new FakeRemoteServer(); FakeRemoteServer bedroom = new FakeRemoteServer()) {
            DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
            registry.save(AndroidTvSettings.device("living", "Living Room", "127.0.0.1", living.port(), null,
                    Instant.parse("2026-09-01T10:00:00Z")));
            registry.save(AndroidTvSettings.device("bedroom", "Bedroom", "127.0.0.1", bedroom.port(), null,
                    Instant.parse("2026-09-02T10:00:00Z")));
            CertificateStore certificates = certificates();
            certificates.loadOrCreate("living");
            certificates.loadOrCreate("bedroom");

            try (DeviceManager manager = manager(registry, certificates)) {
                manager.start();

                await().until(() -> manager.state("living").status() == DeviceStatus.CONNECTED
                        && manager.state("bedroom").status() == DeviceStatus.CONNECTED);
                assertThat(manager.states()).containsOnlyKeys("living", "bedroom");
                assertThat(manager.defaultDevice()).get().extracting(Device::id).isEqualTo("bedroom");
                assertThat(published).anySatisfy(event -> assertThat(event)
                        .isInstanceOfSatisfying(DeviceStateChangedEvent.class, e ->
                                assertThat(e.deviceId()).isEqualTo("living")));
            }
        }
    }

    @Test
    void executesAgainstTheAddressedDeviceOnly() throws Exception {
        try (FakeRemoteServer living = new FakeRemoteServer(); FakeRemoteServer bedroom = new FakeRemoteServer()) {
            DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
            registry.save(AndroidTvSettings.device("living", "Living Room", "127.0.0.1", living.port(), null, Instant.now()));
            registry.save(AndroidTvSettings.device("bedroom", "Bedroom", "127.0.0.1", bedroom.port(), null, Instant.now()));
            CertificateStore certificates = certificates();
            certificates.loadOrCreate("living");
            certificates.loadOrCreate("bedroom");

            try (DeviceManager manager = manager(registry, certificates)) {
                manager.start();
                await().until(() -> manager.state("bedroom").status() == DeviceStatus.CONNECTED);

                manager.execute("bedroom", new Action.PressKey(RemoteKey.HOME));

                assertThat(bedroom.nextKeyPress()).isEqualTo(RemoteKey.HOME.code());
                assertThat(living.nextKeyPress()).isNull();
            }
        }
    }

    @Test
    void anUnknownDeviceIsNotFoundAndAnUnsupportedActionIsRejected() {
        DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
        registry.save(new Device("speaker", "Speaker", DeviceKind.UPNP, "10.0.0.7",
                Map.of("upnp", Map.of()), Instant.now()));

        try (DeviceManager manager = manager(registry, certificates())) {
            manager.start();

            assertThatThrownBy(() -> manager.execute("nope", new Action.PressKey(RemoteKey.HOME)))
                    .isInstanceOf(DeviceNotFoundException.class);
            assertThatThrownBy(() -> manager.execute("speaker", new Action.PressKey(RemoteKey.HOME)))
                    .isInstanceOf(UnsupportedActionException.class);
            assertThat(manager.capabilities("speaker")).isEmpty();
            assertThat(manager.state("speaker").status()).isEqualTo(DeviceStatus.DISCONNECTED);
        }
    }

    @Test
    void forgetClosesTheHandleRemovesTheRecordAndItsCredentialAndPublishesDisconnected() throws Exception {
        try (FakeRemoteServer remote = new FakeRemoteServer()) {
            DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
            registry.save(AndroidTvSettings.device("gone", "Gone", "127.0.0.1", remote.port(), null, Instant.now()));
            CertificateStore certificates = certificates();
            certificates.loadOrCreate("gone");
            certificates.loadOrCreate("kept");

            try (DeviceManager manager = manager(registry, certificates)) {
                manager.start();
                await().until(() -> manager.state("gone").status() == DeviceStatus.CONNECTED);
                published.clear();

                manager.forget("gone");

                assertThat(registry.findById("gone")).isEmpty();
                assertThat(certificates.load("gone")).isEmpty();
                assertThat(certificates.load("kept")).isPresent();
                assertThat(manager.states()).doesNotContainKey("gone");
                assertThat(published).last().isInstanceOfSatisfying(DeviceStateChangedEvent.class, event -> {
                    assertThat(event.deviceId()).isEqualTo("gone");
                    assertThat(event.state().status()).isEqualTo(DeviceStatus.DISCONNECTED);
                });
            }
        }
    }

    @Test
    void capabilitiesAreTheUnionOverTheDevicesAdapters() {
        DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
        Device device = AndroidTvSettings.device("shield", "Shield", "127.0.0.1", 6466, null, Instant.now())
                .withAdapter("fake", Map.of());
        registry.save(device);
        DeviceAdapter fakeAdapter = new FakeAdapter("fake", Set.of(Capability.CAST_RECEIVER),
                d -> new RecordingHandle());

        try (DeviceManager manager = new DeviceManager(registry,
                List.of(new AndroidTvAdapter(certificates(), properties(), new MdnsDiscovery(false)), fakeAdapter),
                publisher)) {
            assertThat(manager.capabilities("shield"))
                    .contains(Capability.REMOTE_KEYS, Capability.APP_LINK, Capability.CAST_RECEIVER);
        }
    }

    @Test
    void executingBeforeConnectingIsOfflineNotUnsupported() {
        DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
        registry.save(AndroidTvSettings.device("shield", "Shield", "127.0.0.1", 6466, null, Instant.now()));

        try (DeviceManager manager = manager(registry, certificates())) {
            // The manager was never started, so "shield" has no handle even though its
            // adapter declares REMOTE_KEYS — that is offline, not unsupported.
            assertThatThrownBy(() -> manager.execute("shield", new Action.PressKey(RemoteKey.HOME)))
                    .isInstanceOf(DeviceOfflineException.class);
        }
    }

    @Test
    void adoptingTheSameDeviceTwiceClosesTheFirstHandle() {
        List<RecordingHandle> created = new CopyOnWriteArrayList<>();
        DeviceAdapter adapter = new FakeAdapter("fake", Set.of(Capability.REMOTE_KEYS), device -> {
            RecordingHandle handle = new RecordingHandle();
            created.add(handle);
            return handle;
        });
        DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
        Device device = new Device("fake-1", "Fake", DeviceKind.UPNP, "10.0.0.1",
                Map.of("fake", Map.of()), Instant.now());

        try (DeviceManager manager = new DeviceManager(registry, List.of(adapter), publisher)) {
            manager.adopt(device);
            manager.adopt(device);

            assertThat(created).hasSize(2);
            assertThat(created.get(0).closed()).as("the first handle must be closed by the second adopt").isTrue();
            assertThat(created.get(1).closed()).isFalse();
        }
    }

    @Test
    void aThrowingAdapterForOneDeviceDoesNotStopAnotherDeviceFromConnecting() {
        DeviceAdapter adapter = new FakeAdapter("fake", Set.of(Capability.REMOTE_KEYS), device -> {
            if (device.id().equals("bad")) {
                throw new RuntimeException("boom");
            }
            return new RecordingHandle();
        });
        DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
        registry.save(new Device("bad", "Bad", DeviceKind.UPNP, "10.0.0.1", Map.of("fake", Map.of()), Instant.now()));
        registry.save(new Device("good", "Good", DeviceKind.UPNP, "10.0.0.2", Map.of("fake", Map.of()), Instant.now()));

        try (DeviceManager manager = new DeviceManager(registry, List.of(adapter), publisher)) {
            manager.start();

            assertThat(manager.state("bad").status()).isEqualTo(DeviceStatus.DISCONNECTED);
            assertThatThrownBy(() -> manager.execute("bad", new Action.PressKey(RemoteKey.HOME)))
                    .isInstanceOf(DeviceOfflineException.class);
            assertThatCode(() -> manager.execute("good", new Action.PressKey(RemoteKey.HOME)))
                    .as("the failing device's adapter must not stop the other device from getting a handle")
                    .doesNotThrowAnyException();
        }
    }

    /** A minimal {@link DeviceAdapter} test double: fixed id/capabilities, a pluggable connect. */
    private static final class FakeAdapter implements DeviceAdapter {

        private final String id;
        private final Set<Capability> capabilities;
        private final Function<Device, DeviceHandle> connector;

        FakeAdapter(String id, Set<Capability> capabilities, Function<Device, DeviceHandle> connector) {
            this.id = id;
            this.capabilities = capabilities;
            this.connector = connector;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public Set<Capability> capabilities(Device device) {
            return capabilities;
        }

        @Override
        public DeviceHandle connect(Device device, Consumer<DeviceState> onChange) {
            return connector.apply(device);
        }

        @Override
        public List<DiscoveredDevice> discovered() {
            return List.of();
        }
    }

    /** A {@link DeviceHandle} that only records whether it was closed. */
    private static final class RecordingHandle implements DeviceHandle {

        private volatile boolean closed;

        @Override
        public DeviceState state() {
            return DeviceState.initial();
        }

        @Override
        public void execute(Action action) {
        }

        @Override
        public void close() {
            closed = true;
        }

        boolean closed() {
            return closed;
        }
    }
}
