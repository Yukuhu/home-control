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
import dev.andre.homecontrol.core.SpeakerTopology;
import dev.andre.homecontrol.core.SpeakerGroup;
import dev.andre.homecontrol.core.GroupMember;
import dev.andre.homecontrol.core.GroupListing;
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
import dev.andre.homecontrol.core.ForegroundAppReporting;
import dev.andre.homecontrol.core.InputListing;
import dev.andre.homecontrol.core.LearnedSettings;
import dev.andre.homecontrol.core.RemoteKey;
import dev.andre.homecontrol.core.TvInput;
import dev.andre.homecontrol.core.UnsupportedActionException;
import dev.andre.homecontrol.core.WakeOnLanAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
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
    void aVersionOneRegistryAndItsExistingCredentialConnectWithoutRePairing() throws Exception {
        // The upgrade path end to end: a v0.3 devices.json and the keystore entry pairing
        // created under the device id. Nothing is re-paired — the migrated record finds the
        // same credential, connects, and takes commands.
        try (FakeRemoteServer remote = new FakeRemoteServer()) {
            Path file = dir.resolve("devices.json");
            Files.writeString(file, "[{\"id\":\"127-0-0-1\",\"name\":\"Living Room Shield\","
                    + "\"host\":\"127.0.0.1\",\"port\":" + remote.port() + ","
                    + "\"certificateFingerprint\":null,\"lastSeen\":\"2026-08-29T18:00:00Z\"}]");
            CertificateStore certificates = certificates();
            certificates.loadOrCreate("127-0-0-1");

            try (DeviceManager manager = manager(new JsonFileDeviceRegistry(file), certificates)) {
                manager.start();

                await().until(() -> manager.state("127-0-0-1").status() == DeviceStatus.CONNECTED);
                manager.execute("127-0-0-1", new Action.PressKey(RemoteKey.HOME));

                assertThat(remote.nextKeyPress()).isEqualTo(RemoteKey.HOME.code());
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

    @Test
    void aFailedConnectPublishesDisconnectedSoNoTabKeepsAStaleBadge() {
        // The device's previous handle is closed and silenced before the new connect, so
        // whatever it last published (say CONNECTED) would otherwise stay on screen.
        AtomicBoolean failNext = new AtomicBoolean();
        DeviceAdapter adapter = new FakeAdapter("fake", Set.of(Capability.REMOTE_KEYS), device -> {
            if (failNext.get()) {
                throw new RuntimeException("boom");
            }
            return new RecordingHandle();
        });
        DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
        Device device = new Device("flaky", "Flaky", DeviceKind.UPNP, "10.0.0.1", Map.of("fake", Map.of()), Instant.now());

        try (DeviceManager manager = new DeviceManager(registry, List.of(adapter), publisher)) {
            manager.adopt(device);
            published.clear();
            failNext.set(true);

            manager.adopt(device);

            assertThat(published).last().isInstanceOfSatisfying(DeviceStateChangedEvent.class, event -> {
                assertThat(event.deviceId()).isEqualTo("flaky");
                assertThat(event.state().status()).isEqualTo(DeviceStatus.DISCONNECTED);
            });
        }
    }

    /** A TV adapter that wakes its devices; remembers the {@link LearnedSettings} each connect got. */
    static class WakingAdapter extends StubAdapter implements WakeOnLanAdapter {

        final Map<String, LearnedSettings> learned = new ConcurrentHashMap<>();

        WakingAdapter() {
            super("waking", DeviceKind.WEBOS, false, false, Capability.REMOTE_KEYS, Capability.POWER);
        }

        @Override
        public DeviceHandle connect(Device device, Consumer<DeviceState> onChange, LearnedSettings settings) {
            learned.put(device.id(), settings);
            return connect(device, onChange);
        }
    }

    private final StubAdapter alpha = new StubAdapter("alpha", DeviceKind.CAST, false, false, Capability.VOLUME);
    private final WakingAdapter waking = new WakingAdapter();

    private DeviceManager wakingManager(DeviceRegistry registry) {
        registry.save(new Device("tv", "TV", DeviceKind.WEBOS, "10.0.0.60",
                orderedAdapters("alpha", Map.of("host", "10.0.0.60"), "waking", Map.of("clientKey", "k")), Instant.now()));
        registry.save(new Device("box", "Box", DeviceKind.CAST, "10.0.0.61",
                Map.of("alpha", Map.of("host", "10.0.0.61")), Instant.now()));
        DeviceManager manager = new DeviceManager(registry, List.of(alpha, waking), publisher);
        manager.start();
        return manager;
    }

    private static Map<String, Map<String, String>> orderedAdapters(String first, Map<String, String> firstSettings,
                                                                    String second, Map<String, String> secondSettings) {
        Map<String, Map<String, String>> adapters = new LinkedHashMap<>();
        adapters.put(first, firstSettings);
        adapters.put(second, secondSettings);
        return adapters;
    }

    @Test
    void onlyDevicesWithAWakeOnLanAdapterWakeOnLan() {
        DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
        try (DeviceManager manager = wakingManager(registry)) {
            assertThat(manager.wakesOnLan("tv")).isTrue();
            assertThat(manager.wakesOnLan("box")).isFalse();
            assertThat(manager.wakesOnLan("nope")).isFalse();
        }
    }

    @Test
    void aHandEnteredMacIsNormalisedAndMarkedManualOnEveryWakingAdapter() {
        DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
        try (DeviceManager manager = wakingManager(registry)) {
            StubAdapter.StubHandle before = alpha.handles.get("tv");

            manager.setWakeOnLanMac("tv", "a8-23-fe-01-02-03");

            Device stored = registry.findById("tv").orElseThrow();
            assertThat(stored.adapterSettings("waking"))
                    .containsEntry("macAddress", "A8:23:FE:01:02:03")
                    .containsEntry("macAddressManual", "true")
                    .containsEntry("clientKey", "k");
            assertThat(stored.adapterSettings("alpha")).isEqualTo(Map.of("host", "10.0.0.60"));
            assertThat(manager.wakeOnLanMac("tv")).contains("A8:23:FE:01:02:03");
            assertThat(alpha.handles.get("tv")).as("no reconnect").isSameAs(before);
        }
    }

    @Test
    void aBlankMacClearsItSoItIsLearnedAgain() {
        DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
        try (DeviceManager manager = wakingManager(registry)) {
            manager.setWakeOnLanMac("tv", "a8-23-fe-01-02-03");

            manager.setWakeOnLanMac("tv", " ");

            assertThat(registry.findById("tv").orElseThrow().adapterSettings("waking"))
                    .doesNotContainKeys("macAddress", "macAddressManual");
            assertThat(manager.wakeOnLanMac("tv")).isEmpty();
        }
    }

    @Test
    void anInvalidMacIsRejected() throws Exception {
        DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
        try (DeviceManager manager = wakingManager(registry)) {
            String before = Files.readString(dir.resolve("devices.json"));

            assertThatThrownBy(() -> manager.setWakeOnLanMac("tv", "nope")).isInstanceOf(IllegalArgumentException.class);

            assertThat(Files.readString(dir.resolve("devices.json"))).isEqualTo(before);
        }
    }

    @Test
    void whatAHandleLearnsIsStoredUnderItsAdapterWithoutReconnecting() {
        DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
        try (DeviceManager manager = wakingManager(registry)) {
            StubAdapter.StubHandle before = waking.handles.get("tv");

            waking.learned.get("tv").store(Map.of("macAddress", "A8:23:FE:01:02:03", "clientKey", "k2"));

            assertThat(registry.findById("tv").orElseThrow().adapterSettings("waking"))
                    .isEqualTo(Map.of("macAddress", "A8:23:FE:01:02:03", "clientKey", "k2"));
            assertThat(registry.findById("tv").orElseThrow().adapterSettings("alpha")).isEqualTo(Map.of("host", "10.0.0.60"));
            assertThat(waking.handles.get("tv")).isSameAs(before);
        }
    }

    @Test
    void aLearnedMacNeverReplacesAHandEnteredOne() {
        DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
        try (DeviceManager manager = wakingManager(registry)) {
            manager.setWakeOnLanMac("tv", "11:22:33:44:55:66");

            waking.learned.get("tv").store(Map.of("macAddress", "A8:23:FE:01:02:03"));

            assertThat(manager.wakeOnLanMac("tv")).contains("11:22:33:44:55:66");
        }
    }

    @Test
    void aLateLearnedSettingNeverResurrectsAForgottenDevice() {
        DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
        try (DeviceManager manager = wakingManager(registry)) {
            LearnedSettings late = waking.learned.get("tv");
            manager.forget("tv");

            late.store(Map.of("clientKey", "k2"));

            assertThat(registry.findById("tv")).isEmpty();
        }
    }

    @Test
    void inputsComeFromTheFirstHandleThatListsThem() {
        List<TvInput> hdmi = List.of(new TvInput("HDMI_1", "HDMI 1"));
        DeviceAdapter listing = new FakeAdapter("listing", Set.of(Capability.REMOTE_KEYS), device -> new ListingHandle(hdmi));
        DeviceAdapter plain = new FakeAdapter("plain", Set.of(Capability.VOLUME), device -> new RecordingHandle());
        DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
        registry.save(new Device("tv", "TV", DeviceKind.WEBOS, "10.0.0.60",
                orderedAdapters("plain", Map.of(), "listing", Map.of()), Instant.now()));
        registry.save(new Device("box", "Box", DeviceKind.CAST, "10.0.0.61", Map.of("plain", Map.of()), Instant.now()));

        try (DeviceManager manager = new DeviceManager(registry, List.of(plain, listing), publisher)) {
            manager.start();

            assertThat(manager.inputs("tv")).isEqualTo(hdmi);
            assertThat(manager.inputs("box")).isEmpty();
            assertThat(manager.inputs("nope")).isEmpty();
        }
    }

    @Test
    void speakerTopologyComesFromTheFirstHandleThatHasOne() {
        SpeakerTopology topology = new SpeakerTopology("K", List.of(new SpeakerGroup("K", List.of(new GroupMember("K", "Kitchen")))));
        DeviceAdapter grouping = new FakeAdapter("grouping", Set.of(Capability.MEDIA_RENDERER), device -> new GroupingHandle(topology));
        DeviceAdapter plain = new FakeAdapter("plain", Set.of(Capability.VOLUME), device -> new RecordingHandle());
        DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
        registry.save(new Device("speaker", "Speaker", DeviceKind.SONOS, "10.0.0.71",
                orderedAdapters("plain", Map.of(), "grouping", Map.of()), Instant.now()));
        registry.save(new Device("box", "Box", DeviceKind.CAST, "10.0.0.61", Map.of("plain", Map.of()), Instant.now()));

        try (DeviceManager manager = new DeviceManager(registry, List.of(plain, grouping), publisher)) {
            manager.start();

            assertThat(manager.speakerTopology("speaker")).contains(topology);
            assertThat(manager.speakerTopology("box")).isEmpty();
            assertThat(manager.speakerTopology("nope")).isEmpty();
        }
    }

    /** A handle that knows fixed speaker groups. */
    private static final class GroupingHandle implements DeviceHandle, GroupListing {

        private final SpeakerTopology topology;

        GroupingHandle(SpeakerTopology topology) {
            this.topology = topology;
        }

        @Override
        public Optional<SpeakerTopology> speakerTopology() {
            return Optional.of(topology);
        }

        @Override
        public DeviceState state() {
            return DeviceState.initial();
        }

        @Override
        public void execute(Action action) {
        }

        @Override
        public void close() {
        }
    }

    /** A stub adapter that reports the foreground app the given way. */
    private static StubAdapter reporting(String id, ForegroundAppReporting how) {
        return new StubAdapter(id, DeviceKind.UPNP, false, false, Capability.APP_LINK) {
            @Override
            public ForegroundAppReporting foregroundAppReporting(Device device) {
                return how;
            }
        };
    }

    @Test
    void foregroundAppReportingIsTheBestOfTheDevicesAdapters() {
        DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
        registry.save(new Device("tv", "TV", DeviceKind.TIZEN, "10.0.0.60",
                orderedAdapters("none", Map.of(), "polled", Map.of()), Instant.now()));
        registry.save(new Device("box", "Box", DeviceKind.ANDROID_TV, "10.0.0.61",
                orderedAdapters("polled", Map.of(), "live", Map.of()), Instant.now()));

        try (DeviceManager manager = new DeviceManager(registry, List.of(reporting("none", ForegroundAppReporting.NONE),
                reporting("polled", ForegroundAppReporting.POLLED), reporting("live", ForegroundAppReporting.LIVE)),
                publisher)) {
            assertThat(manager.foregroundAppReporting("tv")).isEqualTo(ForegroundAppReporting.POLLED);
            assertThat(manager.foregroundAppReporting("box")).isEqualTo(ForegroundAppReporting.LIVE);
            assertThat(manager.foregroundAppReporting("nope")).isEqualTo(ForegroundAppReporting.NONE);
        }
    }

    /** A handle that lists fixed inputs. */
    private static final class ListingHandle implements DeviceHandle, InputListing {

        private final List<TvInput> inputs;

        ListingHandle(List<TvInput> inputs) {
            this.inputs = inputs;
        }

        @Override
        public List<TvInput> inputs() {
            return inputs;
        }

        @Override
        public DeviceState state() {
            return DeviceState.initial();
        }

        @Override
        public void execute(Action action) {
        }

        @Override
        public void close() {
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
        public DeviceKind kind() {
            return DeviceKind.UPNP;
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
