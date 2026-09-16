package dev.andre.homecontrol.device;

import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceDiscoveredEvent;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceRegistry;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStateChangedEvent;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.DiscoveredDevice;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeviceManagerMergeTest {

    @TempDir
    Path dir;

    private final List<Object> published = new CopyOnWriteArrayList<>();
    private final StubAdapter androidtv = new StubAdapter("androidtv", DeviceKind.ANDROID_TV, false, true,
            Capability.REMOTE_KEYS, Capability.APP_LINK, Capability.VOLUME);
    private final StubAdapter cast = new StubAdapter("cast", DeviceKind.CAST, true, false,
            Capability.CAST_RECEIVER, Capability.VOLUME);
    private DeviceRegistry registry;
    private DeviceManager manager;

    @BeforeEach
    void setUp() {
        registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
        manager = new DeviceManager(registry, List.of(androidtv, cast), published::add);
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    private static Device shield() {
        return new Device("10-0-0-5", "Living Room TV", DeviceKind.ANDROID_TV, "10.0.0.5",
                Map.of("androidtv", Map.of("port", "6466")), Instant.parse("2026-09-01T00:00:00Z"));
    }

    private static DiscoveredDevice receiver(String name, String host) {
        return new DiscoveredDevice("cast", name, host, 8009, Map.of("id", "abc"));
    }

    @Test
    void aReceiverAtTheAddressOfARegisteredDeviceIsMergedIntoIt() {
        registry.save(shield());
        manager.start();

        manager.onDiscovered(new DeviceDiscoveredEvent(receiver("SHIELD", "10.0.0.5")));

        Device merged = registry.findById("10-0-0-5").orElseThrow();
        assertThat(List.copyOf(merged.adapters().keySet())).containsExactly("androidtv", "cast");
        assertThat(merged.adapterSettings("cast")).containsEntry("port", "8009");
        assertThat(cast.handles).containsKey("10-0-0-5");
        assertThat(registry.findAll()).hasSize(1);
    }

    @Test
    void aReceiverWithTheSameFriendlyNameIsMergedWhenTheAddressDiffers() {
        registry.save(shield());

        manager.onDiscovered(new DeviceDiscoveredEvent(receiver("living room tv ", "10.0.0.77")));

        assertThat(registry.findById("10-0-0-5").orElseThrow().hasAdapter("cast")).isTrue();
    }

    @Test
    void anAmbiguousNameIsNotMerged() {
        registry.save(shield());
        registry.save(new Device("10-0-0-6", "Living Room TV", DeviceKind.ANDROID_TV, "10.0.0.6",
                Map.of("androidtv", Map.of()), Instant.EPOCH));

        manager.onDiscovered(new DeviceDiscoveredEvent(receiver("Living Room TV", "10.0.0.77")));

        assertThat(registry.findAll()).noneMatch(device -> device.hasAdapter("cast"));
    }

    @Test
    void anUnmatchedReceiverIsOfferedButNotRegistered() {
        registry.save(shield());
        cast.visible.add(receiver("Kitchen", "10.0.0.9"));

        manager.onDiscovered(new DeviceDiscoveredEvent(receiver("Kitchen", "10.0.0.9")));

        assertThat(registry.findAll()).hasSize(1);
        assertThat(manager.addable()).extracting(DiscoveredDevice::name).containsExactly("Kitchen");
        assertThat(manager.pairable()).isEmpty();
    }

    @Test
    void addingAnUnmatchedReceiverRegistersACastDevice() {
        cast.visible.add(receiver("Kitchen", "10.0.0.9"));

        Device added = manager.addDiscovered("cast", "10.0.0.9", 8009);

        assertThat(added.id()).isEqualTo("cast-10-0-0-9");
        assertThat(added.kind()).isEqualTo(DeviceKind.CAST);
        assertThat(added.name()).isEqualTo("Kitchen");
        assertThat(registry.findById("cast-10-0-0-9")).isPresent();
        assertThat(manager.addable()).isEmpty();
        assertThatThrownBy(() -> manager.addDiscovered("cast", "10.0.0.9", 8009))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already added");
        assertThatThrownBy(() -> manager.addDiscovered("cast", "10.0.0.99", 8009))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no longer visible");
    }

    @Test
    void rePairingAMergedDeviceKeepsItsCastEntry() {
        registry.save(shield().withAdapter("cast", Map.of("port", "8009")));

        manager.adopt(new Device("10-0-0-5", "Living Room TV", DeviceKind.ANDROID_TV, "10.0.0.5",
                Map.of("androidtv", Map.of("port", "6466", "certificateFingerprint", "AB")), Instant.now()));

        Device device = registry.findById("10-0-0-5").orElseThrow();
        assertThat(List.copyOf(device.adapters().keySet())).containsExactly("androidtv", "cast");
        assertThat(device.adapterSettings("androidtv")).containsEntry("certificateFingerprint", "AB");
    }

    @Test
    void adoptingAnAndroidTvAbsorbsAnUnregisteredReceiverAtTheSameAddress() {
        cast.visible.add(receiver("SHIELD", "10.0.0.5"));

        manager.adopt(shield());

        assertThat(registry.findById("10-0-0-5").orElseThrow().hasAdapter("cast")).isTrue();
    }

    @Test
    void mergeMovesTheSourceAdaptersAndDeletesTheSourceWithoutForgettingCredentials() {
        registry.save(shield());
        registry.save(new Device("cast-10-0-0-5", "SHIELD", DeviceKind.CAST, "10.0.0.5",
                Map.of("cast", Map.of("port", "8009")), Instant.EPOCH));
        manager.start();
        published.clear();

        Device merged = manager.merge("10-0-0-5", "cast-10-0-0-5");

        assertThat(List.copyOf(merged.adapters().keySet())).containsExactly("androidtv", "cast");
        assertThat(registry.findById("cast-10-0-0-5")).isEmpty();
        assertThat(cast.forgotten).isEmpty();
        assertThat(published).anySatisfy(event -> assertThat(event).isInstanceOfSatisfying(
                DeviceStateChangedEvent.class, e -> assertThat(e.deviceId()).isEqualTo("cast-10-0-0-5")));
    }

    @Test
    void mergeRefusesToMoveAPairingBoundToTheDeviceId() {
        registry.save(shield());
        registry.save(new Device("cast-10-0-0-5", "SHIELD", DeviceKind.CAST, "10.0.0.5",
                Map.of("cast", Map.of("port", "8009")), Instant.EPOCH));

        assertThatThrownBy(() -> manager.merge("cast-10-0-0-5", "10-0-0-5"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("other way round");
        assertThat(registry.findAll()).hasSize(2);
        assertThatThrownBy(() -> manager.merge("10-0-0-5", "10-0-0-5")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void splitMovesOneAdapterIntoANewDeviceThatIsNotMergedBack() {
        registry.save(shield().withAdapter("cast", Map.of("port", "8009")));
        manager.start();

        Device split = manager.split("10-0-0-5", "cast");

        assertThat(split.id()).isEqualTo("cast-10-0-0-5");
        assertThat(split.name()).isEqualTo("Living Room TV (cast)");
        assertThat(split.kind()).isEqualTo(DeviceKind.CAST);
        assertThat(registry.findById("10-0-0-5").orElseThrow().hasAdapter("cast")).isFalse();

        manager.onDiscovered(new DeviceDiscoveredEvent(receiver("Living Room TV", "10.0.0.5")));

        assertThat(registry.findById("10-0-0-5").orElseThrow().hasAdapter("cast")).isFalse();
    }

    @Test
    void splitRefusesTheOnlyAdapterAndABoundPairing() {
        registry.save(shield().withAdapter("cast", Map.of("port", "8009")));
        registry.save(new Device("cast-10-0-0-9", "Kitchen", DeviceKind.CAST, "10.0.0.9",
                Map.of("cast", Map.of()), Instant.EPOCH));

        assertThatThrownBy(() -> manager.split("10-0-0-5", "androidtv"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot be split off");
        assertThatThrownBy(() -> manager.split("cast-10-0-0-9", "cast"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("only one connection");
    }

    @Test
    void stateAndEventsCarryTheComposedStateOfAllAdapters() {
        registry.save(shield().withAdapter("cast", Map.of("port", "8009")));
        manager.start();
        published.clear();

        cast.handles.get("10-0-0-5").report(new DeviceState(DeviceStatus.CONNECTED, true, "Default Media Receiver",
                40, 100, false, Instant.now()));

        assertThat(manager.state("10-0-0-5").currentApp()).isEqualTo("Default Media Receiver");
        assertThat(manager.state("10-0-0-5").volumeLevel()).isEqualTo(40);
        assertThat(published).last().isInstanceOfSatisfying(DeviceStateChangedEvent.class, event -> {
            assertThat(event.deviceId()).isEqualTo("10-0-0-5");
            assertThat(event.state().currentApp()).isEqualTo("Default Media Receiver");
            assertThat(event.state().status()).isEqualTo(DeviceStatus.CONNECTED);
        });
    }

    @Test
    void aReceiverMergedByNameKeepsItsOwnAddressAndIsNotOfferedAgain() {
        registry.save(shield());
        DiscoveredDevice elsewhere = receiver("living room tv", "10.0.0.77");
        cast.visible.add(elsewhere);

        manager.onDiscovered(new DeviceDiscoveredEvent(elsewhere));

        assertThat(registry.findById("10-0-0-5").orElseThrow().adapterSettings("cast"))
                .containsEntry("host", "10.0.0.77");
        assertThat(manager.addable()).isEmpty();
        assertThatThrownBy(() -> manager.addDiscovered("cast", "10.0.0.77", 8009))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already added");
        manager.onDiscovered(new DeviceDiscoveredEvent(elsewhere));
        assertThat(registry.findAll()).hasSize(1);
    }

    @Test
    void splittingAReceiverMergedFromElsewhereGivesItItsOwnAddress() {
        registry.save(shield().withAdapter("cast", Map.of("host", "10.0.0.77", "port", "8009")));

        Device split = manager.split("10-0-0-5", "cast");

        assertThat(split.id()).isEqualTo("cast-10-0-0-77");
        assertThat(split.host()).isEqualTo("10.0.0.77");
    }

    @Test
    void adoptingPrefersTheReceiverAtTheSameAddressOverOneWithTheSameName() {
        cast.visible.add(receiver("Living Room TV", "10.0.0.99"));
        cast.visible.add(receiver("SHIELD", "10.0.0.5"));

        manager.adopt(shield());

        assertThat(registry.findById("10-0-0-5").orElseThrow().adapterSettings("cast"))
                .containsEntry("host", "10.0.0.5");
    }

    @Test
    void adoptingAbsorbsAReceiverByNameOnlyWhenTheNameIsUnambiguous() {
        cast.visible.add(receiver("Living Room TV", "10.0.0.98"));
        cast.visible.add(receiver("living room tv", "10.0.0.99"));

        manager.adopt(shield());

        assertThat(registry.findById("10-0-0-5").orElseThrow().hasAdapter("cast"))
                .as("two receivers share the name").isFalse();

        cast.visible.clear();
        cast.visible.add(receiver("Bedroom TV", "10.0.0.97"));
        registry.save(new Device("10-0-0-6", "Bedroom TV", DeviceKind.ANDROID_TV, "10.0.0.6",
                Map.of("androidtv", Map.of()), Instant.EPOCH));

        manager.adopt(new Device("10-0-0-7", "Bedroom TV", DeviceKind.ANDROID_TV, "10.0.0.7",
                Map.of("androidtv", Map.of()), Instant.EPOCH));

        assertThat(registry.findById("10-0-0-7").orElseThrow().hasAdapter("cast"))
                .as("another registered device has the name").isFalse();

        cast.visible.clear();
        cast.visible.add(receiver("Office TV", "10.0.0.96"));

        manager.adopt(new Device("10-0-0-8", "Office TV", DeviceKind.ANDROID_TV, "10.0.0.8",
                Map.of("androidtv", Map.of()), Instant.EPOCH));

        assertThat(registry.findById("10-0-0-8").orElseThrow().adapterSettings("cast"))
                .containsEntry("host", "10.0.0.96");
    }

    @Test
    void receiversResolvedBeforeTheApplicationWasReadyAreMergedOnceItIs() {
        registry.save(shield());
        cast.visible.add(receiver("SHIELD", "10.0.0.5"));

        manager.mergeVisibleReceivers();

        assertThat(registry.findById("10-0-0-5").orElseThrow().hasAdapter("cast")).isTrue();
        assertThat(manager.addable()).isEmpty();
    }
}
