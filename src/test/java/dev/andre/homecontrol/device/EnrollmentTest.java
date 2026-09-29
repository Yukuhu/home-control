package dev.andre.homecontrol.device;

import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceAdapter;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceRegistry;
import dev.andre.homecontrol.core.DiscoveredDevice;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class EnrollmentTest {

    @TempDir
    Path dir;

    private final List<Object> published = new CopyOnWriteArrayList<>();
    private final ExecutorService background = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void stop() {
        background.shutdownNow();
    }

    private record Wiring(DeviceRegistry registry, DeviceConnections connections, Enrollment enrollment) {
    }

    private Wiring wire(Function<String, Optional<InetAddress>> resolver, DeviceAdapter... adapters) {
        DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
        Map<String, DeviceAdapter> byId = new LinkedHashMap<>();
        for (DeviceAdapter adapter : adapters) {
            byId.put(adapter.id(), adapter);
        }
        RegistryLock lock = new RegistryLock();
        AdapterSettingsStore settings = new AdapterSettingsStore(registry, byId, lock);
        DeviceConnections connections = new DeviceConnections(byId, published::add, settings::updateAdapterSettings);
        return new Wiring(registry, connections, new Enrollment(registry, byId, connections, lock, published::add, resolver));
    }

    private static Device device(String id, String adapterId, String host) {
        return new Device(id, id, DeviceKind.ANDROID_TV, host, Map.of(adapterId, Map.of()), Instant.EPOCH);
    }

    /** A resolver that holds the first lookup until {@code answer} opens, and knows no host. */
    private static Function<String, Optional<InetAddress>> heldResolver(CountDownLatch resolving, CountDownLatch answer) {
        return host -> {
            resolving.countDown();
            try {
                answer.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        };
    }

    @Test
    void attachResolvesWithoutHoldingTheLock() throws Exception {
        CountDownLatch resolving = new CountDownLatch(1);
        CountDownLatch answer = new CountDownLatch(1);
        StubAdapter webos = new StubAdapter("webos", DeviceKind.WEBOS, false, false);
        Wiring wiring = wire(heldResolver(resolving, answer), webos);
        wiring.registry().save(device("other", "webos", "10.0.0.9"));
        Future<Device> attaching = background.submit(() ->
                wiring.enrollment().attach("tv.lan", "TV", DeviceKind.WEBOS, "webos", Map.of("keyRef", "r")));
        assertThat(resolving.await(5, TimeUnit.SECONDS)).isTrue();

        wiring.enrollment().forget("other");

        assertThat(wiring.registry().findById("other")).isEmpty();
        answer.countDown();
        assertThat(attaching.get(5, TimeUnit.SECONDS).adapterSettings("webos")).containsEntry("keyRef", "r");
    }

    @Test
    void aHostAddedWhileResolvingIsComparedByName() throws Exception {
        CountDownLatch resolving = new CountDownLatch(1);
        CountDownLatch answer = new CountDownLatch(1);
        StubAdapter webos = new StubAdapter("webos", DeviceKind.WEBOS, false, false);
        StubAdapter androidtv = new StubAdapter("androidtv", DeviceKind.ANDROID_TV, false, false);
        Wiring wiring = wire(heldResolver(resolving, answer), webos, androidtv);
        Future<Device> attaching = background.submit(() ->
                wiring.enrollment().attach("10.0.0.7", "TV", DeviceKind.WEBOS, "webos", Map.of()));
        assertThat(resolving.await(5, TimeUnit.SECONDS)).isTrue();
        wiring.enrollment().adopt(device("shield", "androidtv", "10.0.0.7"));

        answer.countDown();

        assertThat(attaching.get(5, TimeUnit.SECONDS).id()).isEqualTo("shield");
        assertThat(wiring.registry().findAll()).hasSize(1);
    }

    @Test
    void addingAReceiverWhoseNameBelongsToTwoDevicesRegistersANewOne() {
        StubAdapter androidtv = new StubAdapter("androidtv", DeviceKind.ANDROID_TV, false, false);
        StubAdapter cast = new StubAdapter("cast", DeviceKind.CAST, true, false);
        Wiring wiring = wire(HostAddresses::lookup, androidtv, cast);
        wiring.registry().save(new Device("a", "Living Room", DeviceKind.ANDROID_TV, "10.0.0.6",
                Map.of("androidtv", Map.of()), Instant.EPOCH));
        wiring.registry().save(new Device("b", "Living Room", DeviceKind.CAST, "10.0.0.7",
                Map.of("cast", Map.of("host", "10.0.0.7", "port", "8009")), Instant.EPOCH));
        cast.visible.add(new DiscoveredDevice("cast", "Living Room", "10.0.0.9", 8009, Map.of()));

        Device added = wiring.enrollment().addDiscovered("cast", "10.0.0.9", 8009);

        assertThat(added.id()).isNotIn("a", "b");
        assertThat(wiring.registry().findById("a").orElseThrow().hasAdapter("cast")).isFalse();
    }

    @Test
    void aForgetRacingAConnectLeavesNoHandle() throws Exception {
        BlockingAdapter slow = new BlockingAdapter("slow", "a");
        Wiring wiring = wire(HostAddresses::lookup, slow);
        Future<?> adopting = background.submit(() -> wiring.enrollment().adopt(device("a", "slow", "10.0.0.5")));
        assertThat(slow.entered.await(5, TimeUnit.SECONDS)).isTrue();

        wiring.enrollment().forget("a");
        slow.release();
        adopting.get(5, TimeUnit.SECONDS);

        assertThat(wiring.registry().findById("a")).isEmpty();
        assertThat(wiring.connections().handles("a")).isEmpty();
        assertThat(slow.handles.get("a").closed).isTrue();
    }
}
