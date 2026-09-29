package dev.andre.homecontrol.device;

import dev.andre.homecontrol.adapters.androidtv.AndroidTvAdapter;
import dev.andre.homecontrol.storage.DataDirectory;
import dev.andre.homecontrol.storage.StorageException;
import dev.andre.homecontrol.testsupport.TestCredentials;
import dev.andre.homecontrol.adapters.androidtv.AndroidTvProperties;
import dev.andre.homecontrol.adapters.androidtv.AndroidTvSettings;
import dev.andre.homecontrol.adapters.androidtv.MdnsDiscovery;
import dev.andre.homecontrol.adapters.androidtv.CertificateStore;
import dev.andre.homecontrol.adapters.androidtv.protocol.FakeRemoteServer;
import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.AdapterDiscovery;
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
import dev.andre.homecontrol.core.ForegroundAppReporting;
import dev.andre.homecontrol.core.GroupListing;
import dev.andre.homecontrol.core.GroupMember;
import dev.andre.homecontrol.core.InputListing;
import dev.andre.homecontrol.core.LearnedSettings;
import dev.andre.homecontrol.core.RemoteKey;
import dev.andre.homecontrol.core.SpeakerGroup;
import dev.andre.homecontrol.core.SpeakerTopology;
import dev.andre.homecontrol.core.TvInput;
import dev.andre.homecontrol.core.UnsupportedActionException;
import java.time.Duration;
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
import dev.andre.homecontrol.adapters.upnp.UpnpAdapter;
import dev.andre.homecontrol.adapters.upnp.UpnpDiscovery;
import dev.andre.homecontrol.adapters.upnp.UpnpProperties;
import dev.andre.homecontrol.core.DeviceCommands;
import dev.andre.homecontrol.core.DeviceDiscoveredEvent;
import dev.andre.homecontrol.core.DeviceEnrollment;
import dev.andre.homecontrol.core.DeviceSettings;
import dev.andre.homecontrol.discovery.ssdp.SsdpDiscovery;
import dev.andre.homecontrol.discovery.ssdp.SsdpProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class DevicesTest {

    @TempDir
    Path dir;

    private final List<Object> published = new CopyOnWriteArrayList<>();
    private final ApplicationEventPublisher publisher = published::add;

    private AndroidTvProperties properties() {
        return new AndroidTvProperties(true, "shield", Duration.ofSeconds(10), Duration.ofSeconds(1),
                Duration.ofSeconds(4));
    }

    private CertificateStore certificates() {
        return new CertificateStore(dir.resolve(DataDirectory.KEYSTORE), "shield".toCharArray());
    }

    private Devices devices(DeviceRegistry registry, CertificateStore certificates) {
        return Devices.assemble(registry,
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
            certificates.save("living", TestCredentials.clientCertificate());
            certificates.save("bedroom", TestCredentials.clientCertificate());

            try (Devices devices = devices(registry, certificates)) {
                devices.start();

                await().until(() -> devices.queries().state("living").status() == DeviceStatus.CONNECTED
                        && devices.queries().state("bedroom").status() == DeviceStatus.CONNECTED);
                assertThat(devices.queries().states()).containsOnlyKeys("living", "bedroom");
                assertThat(devices.queries().defaultDevice()).get().extracting(Device::id).isEqualTo("bedroom");
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
            certificates.save("living", TestCredentials.clientCertificate());
            certificates.save("bedroom", TestCredentials.clientCertificate());

            try (Devices devices = devices(registry, certificates)) {
                devices.start();
                await().until(() -> devices.queries().state("bedroom").status() == DeviceStatus.CONNECTED);

                devices.commands().execute("bedroom", new Action.PressKey(RemoteKey.HOME));

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
            certificates.save("127-0-0-1", TestCredentials.clientCertificate());

            try (Devices devices = devices(new JsonFileDeviceRegistry(file), certificates)) {
                devices.start();

                await().until(() -> devices.queries().state("127-0-0-1").status() == DeviceStatus.CONNECTED);
                devices.commands().execute("127-0-0-1", new Action.PressKey(RemoteKey.HOME));

                assertThat(remote.nextKeyPress()).isEqualTo(RemoteKey.HOME.code());
            }
        }
    }

    /** Cast switched off: an Android TV box whose devices.json still carries a cast entry keeps its own controls. */
    @Test
    void anEntryItsAdapterRejectsStopsStartupNamingTheDevice() {
        DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
        registry.save(new Device("bad-port", "Bad", DeviceKind.ANDROID_TV, "10.0.0.9",
                Map.of("androidtv", Map.of("port", "70000")), Instant.now()));

        try (Devices devices = devices(registry, certificates())) {
            assertThatThrownBy(devices::start)
                    .isInstanceOf(StorageException.class)
                    .hasMessage("Invalid device record bad-port in devices.json: androidtv port must be an integer"
                            + " between 1 and 65535; fix or delete it");
        }
    }

    @Test
    void anEntryOfASwitchedOffAdapterIsNotValidated() {
        DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
        registry.save(new Device("bad-port", "Bad", DeviceKind.ANDROID_TV, "10.0.0.9",
                Map.of("androidtv", Map.of("port", "70000")), Instant.now()));

        try (Devices devices = Devices.assemble(registry, List.of(), publisher)) {
            devices.start();

            assertThat(devices.queries().devices()).extracting(Device::id).containsExactly("bad-port");
        }
    }

    @Test
    void aDeviceAnAdapterMigratesIsSavedBeforeItConnects() {
        DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
        registry.save(new Device("old", "Old", DeviceKind.WEBOS, "10.0.0.9", Map.of("probe", Map.of("legacy", "1")),
                Instant.now()));
        StubAdapter migrating = new StubAdapter("probe", DeviceKind.WEBOS, false, false) {
            @Override
            public Device migrate(Device device) {
                return device.withAdapter("probe", Map.of("current", "1"));
            }
        };

        try (Devices devices = Devices.assemble(registry, List.of(migrating), publisher)) {
            devices.start();

            assertThat(migrating.handles.get("old")).isNotNull();
        }

        assertThat(new JsonFileDeviceRegistry(dir.resolve("devices.json")).findById("old")).get()
                .extracting(device -> device.adapterSettings("probe")).isEqualTo(Map.of("current", "1"));
    }

    @Test
    void anEntryForAnAdapterThatIsSwitchedOffAddsNothing() {
        DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
        registry.save(new Device("shield-c", "Shield C", DeviceKind.ANDROID_TV, "127.0.0.1",
                Map.of("androidtv", Map.of(), "cast", Map.of("port", "8009")), Instant.now()));

        try (Devices devices = devices(registry, certificates())) {
            devices.start();

            assertThat(devices.queries().capabilities("shield-c"))
                    .containsExactlyInAnyOrder(Capability.REMOTE_KEYS, Capability.APP_LINK, Capability.ANDROID_APPS);
        }
    }

    @Test
    void onlyAnAdapterThatIsSwitchedOnIsEnabled() {
        try (Devices devices = devices(new JsonFileDeviceRegistry(dir.resolve("devices.json")), certificates())) {
            assertThat(devices.queries().adapterEnabled("androidtv")).isTrue();
            assertThat(devices.queries().adapterEnabled("webos")).isFalse();
        }
    }

    /** With Android TV switched off, its pairing (bound to the device id) must not move to another id or device. */
    @Test
    void anEntryOfASwitchedOffModuleIsNeitherMergedNorSplit() {
        DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
        registry.save(new Device("shield-10-0-0-5", "Shield", DeviceKind.ANDROID_TV, "10.0.0.5",
                Map.of("androidtv", Map.of()), Instant.now()));
        registry.save(new Device("cast-10-0-0-6", "Receiver", DeviceKind.CAST, "10.0.0.6",
                Map.of("cast", Map.of("port", "8009")), Instant.now()));
        registry.save(new Device("tv-10-0-0-7", "Living Room", DeviceKind.ANDROID_TV, "10.0.0.7",
                Map.of("androidtv", Map.of(), "cast", Map.of("port", "8009")), Instant.now()));

        try (Devices devices = Devices.assemble(registry, List.of(), publisher)) {
            devices.start();

            DeviceEnrollment enrollment = devices.enrollment();
            assertThatThrownBy(() -> enrollment.merge("cast-10-0-0-6", "shield-10-0-0-5"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("androidtv module is switched off");
            assertThatThrownBy(() -> enrollment.split("tv-10-0-0-7", "androidtv"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("androidtv module is switched off");
            assertThat(registry.findById("shield-10-0-0-5")).isPresent();
            assertThat(registry.findById("cast-10-0-0-6").orElseThrow().hasAdapter("androidtv")).isFalse();
            assertThat(registry.findById("tv-10-0-0-7").orElseThrow().adapters()).containsOnlyKeys("androidtv", "cast");
        }
    }

    @Test
    void anUnknownDeviceIsNotFoundAndAnUnsupportedActionIsRejected() {
        DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
        registry.save(new Device("speaker", "Speaker", DeviceKind.UPNP, "10.0.0.7",
                Map.of("upnp", Map.of()), Instant.now()));

        try (Devices devices = devices(registry, certificates())) {
            devices.start();

            var pressHome = new Action.PressKey(RemoteKey.HOME);
            DeviceCommands commands = devices.commands();
            assertThatThrownBy(() -> commands.execute("nope", pressHome))
                    .isInstanceOf(DeviceNotFoundException.class);
            assertThatThrownBy(() -> commands.execute("speaker", pressHome))
                    .isInstanceOf(UnsupportedActionException.class);
            assertThat(devices.queries().capabilities("speaker")).isEmpty();
            assertThat(devices.queries().state("speaker").status()).isEqualTo(DeviceStatus.DISCONNECTED);
        }
    }

    @Test
    void forgetClosesTheHandleRemovesTheRecordAndItsCredentialAndPublishesDisconnected() throws Exception {
        try (FakeRemoteServer remote = new FakeRemoteServer()) {
            DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
            registry.save(AndroidTvSettings.device("gone", "Gone", "127.0.0.1", remote.port(), null, Instant.now()));
            CertificateStore certificates = certificates();
            certificates.save("gone", TestCredentials.clientCertificate());
            certificates.save("kept", TestCredentials.clientCertificate());

            try (Devices devices = devices(registry, certificates)) {
                devices.start();
                await().until(() -> devices.queries().state("gone").status() == DeviceStatus.CONNECTED);
                published.clear();

                devices.enrollment().forget("gone");

                assertThat(registry.findById("gone")).isEmpty();
                assertThat(certificates.load("gone")).isEmpty();
                assertThat(certificates.load("kept")).isPresent();
                assertThat(devices.queries().states()).doesNotContainKey("gone");
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

        try (Devices devices = Devices.assemble(registry,
                List.of(new AndroidTvAdapter(certificates(), properties(), new MdnsDiscovery(false)), fakeAdapter),
                publisher)) {
            assertThat(devices.queries().capabilities("shield"))
                    .contains(Capability.REMOTE_KEYS, Capability.APP_LINK, Capability.CAST_RECEIVER);
        }
    }

    @Test
    void executingBeforeConnectingIsOfflineNotUnsupported() {
        DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
        registry.save(AndroidTvSettings.device("shield", "Shield", "127.0.0.1", 6466, null, Instant.now()));

        try (Devices devices = devices(registry, certificates())) {
            // Nothing was started, so "shield" has no handle even though its
            // adapter declares REMOTE_KEYS — that is offline, not unsupported.
            var pressHome = new Action.PressKey(RemoteKey.HOME);
            DeviceCommands commands = devices.commands();
            assertThatThrownBy(() -> commands.execute("shield", pressHome))
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

        try (Devices devices = Devices.assemble(registry, List.of(adapter), publisher)) {
            devices.enrollment().adopt(device);
            devices.enrollment().adopt(device);

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

        try (Devices devices = Devices.assemble(registry, List.of(adapter), publisher)) {
            devices.start();

            assertThat(devices.queries().state("bad").status()).isEqualTo(DeviceStatus.DISCONNECTED);
            var pressHome = new Action.PressKey(RemoteKey.HOME);
            DeviceCommands commands = devices.commands();
            assertThatThrownBy(() -> commands.execute("bad", pressHome))
                    .isInstanceOf(DeviceOfflineException.class);
            assertThatCode(() -> devices.commands().execute("good", new Action.PressKey(RemoteKey.HOME)))
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

        try (Devices devices = Devices.assemble(registry, List.of(adapter), publisher)) {
            devices.enrollment().adopt(device);
            published.clear();
            failNext.set(true);

            devices.enrollment().adopt(device);

            assertThat(published).last().isInstanceOfSatisfying(DeviceStateChangedEvent.class, event -> {
                assertThat(event.deviceId()).isEqualTo("flaky");
                assertThat(event.state().status()).isEqualTo(DeviceStatus.DISCONNECTED);
            });
        }
    }

    /** A TV adapter that wakes its devices; remembers the {@link LearnedSettings} each connect got. */
    static class WakingAdapter extends StubAdapter {

        final Map<String, LearnedSettings> learned = new ConcurrentHashMap<>();

        WakingAdapter() {
            super("waking", DeviceKind.WEBOS, false, false, Capability.REMOTE_KEYS, Capability.WAKE_ON_LAN);
        }

        @Override
        public DeviceHandle connect(Device device, Consumer<DeviceState> onChange, LearnedSettings settings) {
            learned.put(device.id(), settings);
            return connect(device, onChange);
        }
    }

    private final StubAdapter alpha = new StubAdapter("alpha", DeviceKind.CAST, false, false, Capability.VOLUME);
    private final WakingAdapter waking = new WakingAdapter();

    private Devices wakingDevices(DeviceRegistry registry) {
        registry.save(new Device("tv", "TV", DeviceKind.WEBOS, "10.0.0.60",
                orderedAdapters("alpha", Map.of("host", "10.0.0.60"), "waking", Map.of("clientKey", "k")), Instant.now()));
        registry.save(new Device("box", "Box", DeviceKind.CAST, "10.0.0.61",
                Map.of("alpha", Map.of("host", "10.0.0.61")), Instant.now()));
        Devices devices = Devices.assemble(registry, List.of(alpha, waking), publisher);
        devices.start();
        return devices;
    }

    private static Map<String, Map<String, String>> orderedAdapters(String first, Map<String, String> firstSettings,
                                                                    String second, Map<String, String> secondSettings) {
        Map<String, Map<String, String>> adapters = new LinkedHashMap<>();
        adapters.put(first, firstSettings);
        adapters.put(second, secondSettings);
        return adapters;
    }

    @Test
    void onlyDevicesWithAWakeOnLanCapabilityWakeOnLan() {
        DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
        try (Devices devices = wakingDevices(registry)) {
            assertThat(devices.settings().wakesOnLan("tv")).isTrue();
            assertThat(devices.settings().wakesOnLan("box")).isFalse();
            assertThat(devices.settings().wakesOnLan("nope")).isFalse();
        }
    }

    @Test
    void aHandEnteredMacIsNormalisedAndMarkedManualOnEveryWakingAdapter() {
        DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
        try (Devices devices = wakingDevices(registry)) {
            StubAdapter.StubHandle before = alpha.handles.get("tv");

            devices.settings().setWakeOnLanMac("tv", "a8-23-fe-01-02-03");

            Device stored = registry.findById("tv").orElseThrow();
            assertThat(stored.adapterSettings("waking"))
                    .containsEntry("macAddress", "A8:23:FE:01:02:03")
                    .containsEntry("macAddressManual", "true")
                    .containsEntry("clientKey", "k");
            assertThat(stored.adapterSettings("alpha")).isEqualTo(Map.of("host", "10.0.0.60"));
            assertThat(devices.settings().wakeOnLanMac("tv")).contains("A8:23:FE:01:02:03");
            assertThat(alpha.handles.get("tv")).as("no reconnect").isSameAs(before);
        }
    }

    @Test
    void aBlankMacClearsItSoItIsLearnedAgain() {
        DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
        try (Devices devices = wakingDevices(registry)) {
            devices.settings().setWakeOnLanMac("tv", "a8-23-fe-01-02-03");

            devices.settings().setWakeOnLanMac("tv", " ");

            assertThat(registry.findById("tv").orElseThrow().adapterSettings("waking"))
                    .doesNotContainKeys("macAddress", "macAddressManual");
            assertThat(devices.settings().wakeOnLanMac("tv")).isEmpty();
        }
    }

    @Test
    void anInvalidMacIsRejected() throws Exception {
        DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
        try (Devices devices = wakingDevices(registry)) {
            String before = Files.readString(dir.resolve("devices.json"));

            DeviceSettings deviceSettings = devices.settings();
            assertThatThrownBy(() -> deviceSettings.setWakeOnLanMac("tv", "nope")).isInstanceOf(IllegalArgumentException.class);

            assertThat(Files.readString(dir.resolve("devices.json"))).isEqualTo(before);
        }
    }

    @Test
    void whatAHandleLearnsIsStoredUnderItsAdapterWithoutReconnecting() {
        DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
        try (var _ = wakingDevices(registry)) {
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
        try (Devices devices = wakingDevices(registry)) {
            devices.settings().setWakeOnLanMac("tv", "11:22:33:44:55:66");

            waking.learned.get("tv").store(Map.of("macAddress", "A8:23:FE:01:02:03"));

            assertThat(devices.settings().wakeOnLanMac("tv")).contains("11:22:33:44:55:66");
        }
    }

    @Test
    void aLateLearnedSettingNeverResurrectsAForgottenDevice() {
        DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
        try (Devices devices = wakingDevices(registry)) {
            LearnedSettings late = waking.learned.get("tv");
            devices.enrollment().forget("tv");

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

        try (Devices devices = Devices.assemble(registry, List.of(plain, listing), publisher)) {
            devices.start();

            assertThat(devices.queries().inputs("tv")).isEqualTo(hdmi);
            assertThat(devices.queries().inputs("box")).isEmpty();
            assertThat(devices.queries().inputs("nope")).isEmpty();
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

        try (Devices devices = Devices.assemble(registry, List.of(plain, grouping), publisher)) {
            devices.start();

            assertThat(devices.queries().speakerTopology("speaker")).contains(topology);
            assertThat(devices.queries().speakerTopology("box")).isEmpty();
            assertThat(devices.queries().speakerTopology("nope")).isEmpty();
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
            // these tests only read what the handle knows; actions are never sent
        }

        @Override
        public void close() {
            // holds no connection or thread to release
        }
    }

    @Test
    void aRendererInsideARegisteredTvIsMergedIntoIt() {
        DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
        registry.save(new Device("webos-10-0-0-60", "Living Room TV", DeviceKind.WEBOS, "10.0.0.60",
                Map.of("webos", Map.of("clientKey", "k")), Instant.now()));
        SsdpDiscovery ssdp = new SsdpDiscovery(new SsdpProperties(false, "239.255.255.250", 1900, 1900,
                Duration.ofSeconds(60), 2));
        UpnpDiscovery upnpDiscovery = new UpnpDiscovery(ssdp, event -> { }, true);
        UpnpAdapter upnp = new UpnpAdapter(new UpnpProperties(true, Duration.ofSeconds(1), Duration.ofSeconds(1),
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(2)),
                upnpDiscovery);

        try (Devices devices = Devices.assemble(registry, List.of(upnp), publisher)) {
            devices.onDiscovered(new DeviceDiscoveredEvent(new DiscoveredDevice("upnp", "[LG] webOS TV", "10.0.0.60", 1780,
                    Map.of("udn", "uuid:tv", "location", "http://10.0.0.60:1780/", "model", "LG"))));

            assertThat(registry.findAll()).hasSize(1);
            assertThat(registry.findById("webos-10-0-0-60").orElseThrow().adapters().keySet()).containsExactly("webos", "upnp");
        } finally {
            upnpDiscovery.close();
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

        try (Devices devices = Devices.assemble(registry, List.of(reporting("none", ForegroundAppReporting.NONE),
                reporting("polled", ForegroundAppReporting.POLLED), reporting("live", ForegroundAppReporting.LIVE)),
                publisher)) {
            assertThat(devices.queries().foregroundAppReporting("tv")).isEqualTo(ForegroundAppReporting.POLLED);
            assertThat(devices.queries().foregroundAppReporting("box")).isEqualTo(ForegroundAppReporting.LIVE);
            assertThat(devices.queries().foregroundAppReporting("nope")).isEqualTo(ForegroundAppReporting.NONE);
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
            // these tests only read what the handle knows; actions are never sent
        }

        @Override
        public void close() {
            // holds no connection or thread to release
        }
    }

    /** A minimal {@link DeviceAdapter} test double: fixed id/capabilities, a pluggable connect. */
    private static final class FakeAdapter implements DeviceAdapter, AdapterDiscovery {

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
            // only closing is observed; actions are irrelevant here
        }

        @Override
        public void close() {
            closed = true;
        }

        boolean closed() {
            return closed;
        }
    }

    @Test
    void assembleWiresOneConnectionMapForQueriesCommandsAndEnrollment() {
        StubAdapter stub = new StubAdapter("stub", DeviceKind.ANDROID_TV, false, false, Capability.REMOTE_KEYS);
        Action pressHome = new Action.PressKey(RemoteKey.HOME);

        try (Devices devices = Devices.assemble(new JsonFileDeviceRegistry(dir.resolve("devices.json")),
                List.of(stub), publisher)) {
            devices.start();
            devices.enrollment().adopt(new Device("tv", "TV", DeviceKind.ANDROID_TV, "10.0.0.5",
                    Map.of("stub", Map.of()), Instant.EPOCH));
            devices.commands().execute("tv", pressHome);

            assertThat(devices.queries().state("tv").status()).isEqualTo(DeviceStatus.CONNECTED);
            assertThat(stub.handles.get("tv").executed).containsExactly(pressHome);
        }
        assertThat(stub.handles.get("tv").closed).isTrue();
    }
}
