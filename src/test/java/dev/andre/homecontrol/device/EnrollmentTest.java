package dev.andre.homecontrol.device;

import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceAdapter;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceRegistry;
import dev.andre.homecontrol.core.DeviceStateChangedEvent;
import dev.andre.homecontrol.core.DeviceStatus;
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
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        return wire(new JsonFileDeviceRegistry(dir.resolve("devices.json")), resolver, adapters);
    }

    private Wiring wire(DeviceRegistry registry, Function<String, Optional<InetAddress>> resolver,
                        DeviceAdapter... adapters) {
        Map<String, DeviceAdapter> byId = new LinkedHashMap<>();
        for (DeviceAdapter adapter : adapters) {
            byId.put(adapter.id(), adapter);
        }
        RegistryLock lock = new RegistryLock();
        AdapterSettingsStore settings = new AdapterSettingsStore(registry, byId, lock);
        DeviceConnections connections = new DeviceConnections(byId, published::add, settings::updateAdapterSettings);
        return new Wiring(registry, connections, new Enrollment(registry, byId, connections, lock, published::add, resolver));
    }

    /** A registry on a disk that is full once {@code full} is set: every write fails, reads still work. */
    private static final class FullDisk implements DeviceRegistry {

        private final DeviceRegistry files;
        volatile boolean full;

        FullDisk(DeviceRegistry files) {
            this.files = files;
        }

        @Override
        public List<Device> findAll() {
            return files.findAll();
        }

        @Override
        public Optional<Device> findById(String id) {
            return files.findById(id);
        }

        @Override
        public Optional<Device> first() {
            return files.first();
        }

        @Override
        public void save(Device device) {
            write();
            files.save(device);
        }

        @Override
        public void delete(String id) {
            write();
            files.delete(id);
        }

        private void write() {
            if (full) {
                throw new IllegalStateException("No space left on device");
            }
        }
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
            } catch (InterruptedException _) {
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
    void anAdapterWithoutDiscoveryAddsNothingAndStillConnects() {
        DeviceAdapter plain = new DeviceAdapter() {
            @Override
            public String id() {
                return "plain";
            }

            @Override
            public DeviceKind kind() {
                return DeviceKind.ANDROID_TV;
            }

            @Override
            public Set<Capability> capabilities(Device device) {
                return Set.of();
            }

            @Override
            public DeviceHandle connect(Device device, Consumer<DeviceState> onChange) {
                return new StubAdapter.StubHandle(onChange);
            }
        };
        Wiring wiring = wire(HostAddresses::lookup, plain);

        wiring.enrollment().adopt(device("a", "plain", "10.0.0.5"));

        assertThat(wiring.enrollment().discovered()).isEmpty();
        assertThat(wiring.enrollment().addable()).isEmpty();
        assertThat(wiring.connections().handles("a")).containsKey("plain");
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

    @Test
    void aForgetThatFailsLeavesTheDeviceConnected() {
        StubAdapter stub = new StubAdapter("stub", DeviceKind.ANDROID_TV, false, false) {
            @Override
            public void forget(Device device) {
                throw new IllegalStateException("secrets.json is read-only");
            }
        };
        Wiring wiring = wire(HostAddresses::lookup, stub);
        wiring.enrollment().adopt(device("tv", "stub", "10.0.0.5"));

        assertThatThrownBy(() -> wiring.enrollment().forget("tv")).hasMessageContaining("read-only");

        assertThat(wiring.registry().findById("tv")).isPresent();
        assertThat(wiring.connections().handles("tv")).containsValue(stub.handles.get("tv"));
        assertThat(stub.handles.get("tv").closed).isFalse();
    }

    @Test
    void aMergeThatCannotBeSavedLeavesTheSourceConnected() {
        StubAdapter one = new StubAdapter("one", DeviceKind.ANDROID_TV, false, false);
        StubAdapter two = new StubAdapter("two", DeviceKind.CAST, false, false);
        FullDisk disk = new FullDisk(new JsonFileDeviceRegistry(dir.resolve("devices.json")));
        Wiring wiring = wire(disk, HostAddresses::lookup, one, two);
        wiring.enrollment().adopt(device("a", "one", "10.0.0.5"));
        wiring.enrollment().adopt(device("b", "two", "10.0.0.6"));
        disk.full = true;

        assertThatThrownBy(() -> wiring.enrollment().merge("a", "b")).hasMessageContaining("No space");

        assertThat(wiring.connections().handles("b")).containsValue(two.handles.get("b"));
        assertThat(two.handles.get("b").closed).isFalse();
    }

    @Test
    void aHandleThatFailsToCloseStopsNoOtherStepOfAMerge() {
        StubAdapter one = new StubAdapter("one", DeviceKind.ANDROID_TV, false, false);
        StubAdapter two = new StubAdapter("two", DeviceKind.CAST, false, false);
        Wiring wiring = wire(HostAddresses::lookup, one, two);
        wiring.enrollment().adopt(device("a", "one", "10.0.0.5"));
        wiring.enrollment().adopt(device("b", "two", "10.0.0.6"));
        two.handles.get("b").closeFailure = new IllegalStateException("socket already gone");

        wiring.enrollment().merge("a", "b");

        assertThat(wiring.connections().handles("a")).containsOnlyKeys("one", "two");
        assertThat(wiring.connections().state("a").status()).isEqualTo(DeviceStatus.CONNECTED);
        assertThat(published).anySatisfy(event -> assertThat(event).isInstanceOfSatisfying(
                DeviceStateChangedEvent.class, e -> {
                    assertThat(e.deviceId()).isEqualTo("b");
                    assertThat(e.state().status()).isEqualTo(DeviceStatus.DISCONNECTED);
                }));
    }
}
