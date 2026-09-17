package dev.andre.homecontrol.adapters.sonos;

import dev.andre.homecontrol.adapters.sonos.protocol.ZoneGroupState;
import dev.andre.homecontrol.core.DeviceDiscoveredEvent;
import dev.andre.homecontrol.core.DiscoveredDevice;
import dev.andre.homecontrol.discovery.ssdp.FakeSsdpResponder;
import dev.andre.homecontrol.discovery.ssdp.SsdpDiscovery;
import dev.andre.homecontrol.discovery.ssdp.SsdpProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceRegistry;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.device.JsonFileDeviceRegistry;
import org.junit.jupiter.api.io.TempDir;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class SonosDiscoveryTest {

    static final String LIVING = "RINCON_000E58A0B1C201400";
    static final String KITCHEN = "RINCON_000E58C3D4E501400";

    private final FakeSonosHousehold household = new FakeSonosHousehold();
    private final List<Object> events = new CopyOnWriteArrayList<>();
    private FakeSonosPlayer living;
    private FakeSonosPlayer kitchen;
    private FakeSsdpResponder responder;
    private SsdpDiscovery ssdp;
    private SonosDiscovery discovery;

    @BeforeEach
    void setUp() throws IOException {
        living = household.addPlayer("127.0.0.2", LIVING, "Living Room");
        kitchen = household.addPlayer("127.0.0.3", KITCHEN, "Kitchen");
        // The announcement comes from the living room's own address, as a real player's does.
        responder = new FakeSsdpResponder(InetAddress.getByName("127.0.0.2"));
        responder.answer(SonosDiscovery.SEARCH_TARGET, living.searchResponse());
        ssdp = new SsdpDiscovery(new SsdpProperties(true, "127.0.0.2", responder.port(), 0, 1, 1));
        discovery = new SonosDiscovery(ssdp, new SonosProperties(true, 1, 1, 0, 1, 1, 1, 2), events::add);
    }

    /** Searching starts only once a test has set up what the responder answers. */
    private void search() {
        ssdp.start();
    }

    @AfterEach
    void tearDown() {
        discovery.close();
        ssdp.close();
        responder.close();
        household.close();
    }

    private DiscoveredDevice kitchenRoom() {
        return new DiscoveredDevice("sonos", "Kitchen", "127.0.0.3", kitchen.port(), Map.of("uuid", KITCHEN));
    }

    private DiscoveredDevice livingRoom() {
        return new DiscoveredDevice("sonos", "Living Room", "127.0.0.2", living.port(), Map.of("uuid", LIVING));
    }

    @Test
    void findsEveryVisiblePlayerFromOneAnnouncement() {
        search();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(discovery.devices()).containsExactly(kitchenRoom(), livingRoom()));
    }

    @Test
    void publishesAnEventOnlyForTheAnnouncingPlayer() {
        search();
        await().atMost(Duration.ofSeconds(5)).until(() -> discovery.devices().size() == 2 && !events.isEmpty());
        int searches = responder.searches();
        await().atMost(Duration.ofSeconds(10)).until(() -> responder.searches() >= searches + 4);

        // The living room speaks only for itself; the kitchen is listed for Setup but never auto-merged.
        assertThat(events).containsExactly(new DeviceDiscoveredEvent(livingRoom()));
    }

    @Test
    void aForgedMemberAtARegisteredHostMergesNothing(@TempDir Path dir) {
        DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
        Device tv = new Device("tv", "TV", DeviceKind.WEBOS, "127.0.0.3", Map.of("webos", Map.of()), Instant.EPOCH);
        registry.save(tv);
        assertRegistryUntouchedBy(registry, tv);
    }

    @Test
    void aForgedMemberCannotRepointARegisteredRoom(@TempDir Path dir) {
        DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
        Device room = new Device("sonos-kitchen", "Kitchen", DeviceKind.SONOS, "127.0.0.3",
                Map.of("sonos", Map.of("uuid", KITCHEN, "port", "1400")), Instant.EPOCH);
        registry.save(room);
        assertRegistryUntouchedBy(registry, room);
    }

    /** Runs a real device manager fed by this discovery's events and checks {@code registered} stays as it was. */
    private void assertRegistryUntouchedBy(DeviceRegistry registry, Device registered) {
        SonosProperties properties = new SonosProperties(true, 1, 1, 0, 1, 1, 1, 2);
        DeviceManager[] manager = new DeviceManager[1];
        List<Object> managerEvents = new CopyOnWriteArrayList<>();
        SonosDiscovery fed = new SonosDiscovery(ssdp, properties, event -> {
            managerEvents.add(event);
            if (event instanceof DeviceDiscoveredEvent discovered && manager[0] != null) {
                manager[0].onDiscovered(discovered);
            }
        });
        manager[0] = new DeviceManager(registry, List.of(new SonosAdapter(properties, fed)), event -> { });
        try {
            search();
            await().atMost(Duration.ofSeconds(5)).until(() -> fed.devices().size() == 2 && !managerEvents.isEmpty());
            int searches = responder.searches();
            await().atMost(Duration.ofSeconds(10)).until(() -> responder.searches() >= searches + 3);

            assertThat(registry.findAll()).containsExactly(registered);
        } finally {
            manager[0].close();
            fed.close();
        }
    }

    @Test
    void dropsPlayersThatLeftTheHousehold() {
        search();
        await().atMost(Duration.ofSeconds(5)).until(() -> discovery.devices().size() == 2);

        household.removePlayer(kitchen);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(discovery.devices()).containsExactly(livingRoom()));
    }

    @Test
    void tellsListenersWhichPlayerAnnouncedItself() {
        List<String> uuids = new CopyOnWriteArrayList<>();
        discovery.onAlive(uuids::add);
        search();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(uuids).contains(LIVING));
    }

    @Test
    void aLocationOffTheAnnouncingAddressIsNeverAsked() throws InterruptedException {
        // A datagram from 127.0.0.2 naming the kitchen's address must not make us call the kitchen.
        responder.answer(SonosDiscovery.SEARCH_TARGET, living.searchResponse().replace("127.0.0.2:" + living.port(),
                "127.0.0.3:" + kitchen.port()));
        search();

        Thread.sleep(2500);

        assertThat(discovery.devices()).isEmpty();
        assertThat(kitchen.calls()).isEmpty();
        assertThat(living.calls()).isEmpty();
    }

    @Test
    void hiddenMembersAreNotDevices() throws IOException {
        ZoneGroupState state = ZoneGroupState.parse(Files.readString(Path.of("src/test/resources/fixtures/sonos/zone-group-state.xml")));

        assertThat(SonosDiscovery.toDevices(state))
                .extracting(DiscoveredDevice::name, DiscoveredDevice::host, DiscoveredDevice::port)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Living Room", "192.168.1.70", 1400),
                        org.assertj.core.groups.Tuple.tuple("Kitchen", "192.168.1.71", 1400),
                        org.assertj.core.groups.Tuple.tuple("Office & Studio", "192.168.1.73", 1400));
    }
}
