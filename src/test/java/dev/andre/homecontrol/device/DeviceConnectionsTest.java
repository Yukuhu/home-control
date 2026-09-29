package dev.andre.homecontrol.device;

import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceAdapter;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStateChangedEvent;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.LearnedSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class DeviceConnectionsTest {

    /** Like a TV that takes one connection at a time: counts its open connections, and holds its second connect. */
    private static final class OneConnectionTv extends StubAdapter {

        final List<StubHandle> created = new CopyOnWriteArrayList<>();
        final AtomicLong mostOpen = new AtomicLong();
        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch secondEntered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        OneConnectionTv() {
            super("tv", DeviceKind.ANDROID_TV, false, false);
        }

        @Override
        public DeviceHandle connect(Device device, Consumer<DeviceState> onChange) {
            if (calls.incrementAndGet() == 2) {
                secondEntered.countDown();
                try {
                    release.await();
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
            }
            StubHandle handle = (StubHandle) super.connect(device, onChange);
            created.add(handle);
            mostOpen.accumulateAndGet(created.stream().filter(open -> !open.closed).count(), Math::max);
            return handle;
        }
    }

    private final List<Object> published = new CopyOnWriteArrayList<>();
    private final ExecutorService background = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void stop() {
        background.shutdownNow();
    }

    private static Device device(String id, String adapterId) {
        return new Device(id, id, DeviceKind.ANDROID_TV, "10.0.0.5", Map.of(adapterId, Map.of()), Instant.EPOCH);
    }

    private DeviceConnections connections(DeviceAdapter... adapters) {
        Map<String, DeviceAdapter> byId = new LinkedHashMap<>();
        for (DeviceAdapter adapter : adapters) {
            byId.put(adapter.id(), adapter);
        }
        return new DeviceConnections(byId, published::add, (deviceId, adapterId, updates) -> { });
    }

    @Test
    void aBlockedConnectDelaysNoOtherDevice() throws Exception {
        BlockingAdapter slow = new BlockingAdapter("slow", "a");
        StubAdapter fast = new StubAdapter("fast", DeviceKind.ANDROID_TV, false, false);
        DeviceConnections connections = connections(slow, fast);
        DeviceConnections.Connecting a = connections.begin(device("a", "slow"));
        background.submit(() -> connections.complete(a));
        assertThat(slow.entered.await(5, TimeUnit.SECONDS)).isTrue();

        connections.complete(connections.begin(device("b", "fast")));
        connections.end("c");

        assertThat(connections.state("b").status()).isEqualTo(DeviceStatus.CONNECTED);
        slow.release();
        await().until(() -> connections.state("a").status() == DeviceStatus.CONNECTED);
    }

    @Test
    void aSupersededTicketClosesItsHandles() throws Exception {
        BlockingAdapter slow = new BlockingAdapter("slow", "a");
        DeviceConnections connections = connections(slow);
        DeviceConnections.Connecting first = connections.begin(device("a", "slow"));
        Future<?> completing = background.submit(() -> connections.complete(first));
        assertThat(slow.entered.await(5, TimeUnit.SECONDS)).isTrue();

        connections.end("a");
        slow.release();
        completing.get(5, TimeUnit.SECONDS);

        assertThat(connections.handles("a")).isEmpty();
        assertThat(slow.handles.get("a").closed).isTrue();
    }

    @Test
    void theOldHandlesCloseBeforeTheNewOnesConnect() {
        List<Boolean> oldClosedWhenConnecting = new CopyOnWriteArrayList<>();
        StubAdapter adapter = new StubAdapter("stub", DeviceKind.ANDROID_TV, false, false) {
            @Override
            public DeviceHandle connect(Device device, Consumer<DeviceState> onChange) {
                StubAdapter.StubHandle old = handles.get(device.id());
                oldClosedWhenConnecting.add(old == null || old.closed);
                return super.connect(device, onChange);
            }
        };
        DeviceConnections connections = connections(adapter);
        connections.complete(connections.begin(device("a", "stub")));
        StubAdapter.StubHandle old = adapter.handles.get("a");

        DeviceConnections.Connecting again = connections.begin(device("a", "stub"));
        assertThat(old.closed).isFalse();
        connections.complete(again);

        assertThat(oldClosedWhenConnecting).containsExactly(true, true);
        assertThat(adapter.handles.get("a")).isNotSameAs(old);
        assertThat(connections.handles("a")).containsValue(adapter.handles.get("a"));
    }

    @Test
    void aReconnectBegunDuringAnotherWaitsUntilItsConnectionIsClosed() throws Exception {
        OneConnectionTv tv = new OneConnectionTv();
        DeviceConnections connections = connections(tv);
        connections.complete(connections.begin(device("a", "tv")));
        DeviceConnections.Connecting first = connections.begin(device("a", "tv"));
        Future<?> firstDone = background.submit(() -> connections.complete(first));
        assertThat(tv.secondEntered.await(5, TimeUnit.SECONDS)).isTrue();

        DeviceConnections.Connecting second = connections.begin(device("a", "tv"));
        Thread secondDone = Thread.ofPlatform().start(() -> connections.complete(second));
        await().until(() -> tv.calls.get() == 3 || secondDone.getState() == Thread.State.WAITING
                || secondDone.getState() == Thread.State.BLOCKED);
        tv.release.countDown();
        firstDone.get(5, TimeUnit.SECONDS);
        secondDone.join(5_000);

        assertThat(tv.mostOpen.get()).isEqualTo(1);
        assertThat(tv.created).filteredOn(handle -> !handle.closed).singleElement()
                .satisfies(open -> assertThat(connections.handles("a")).containsValue(open));
    }

    @Test
    void aDeviceAddedAgainConnectsOnlyOnceItsRemovedHandlesAreClosed() throws Exception {
        List<Boolean> oldClosedWhenConnecting = new CopyOnWriteArrayList<>();
        StubAdapter adapter = new StubAdapter("stub", DeviceKind.ANDROID_TV, false, false) {
            @Override
            public DeviceHandle connect(Device device, Consumer<DeviceState> onChange) {
                StubAdapter.StubHandle old = handles.get(device.id());
                oldClosedWhenConnecting.add(old == null || old.closed);
                return super.connect(device, onChange);
            }
        };
        DeviceConnections connections = connections(adapter);
        connections.complete(connections.begin(device("a", "stub")));
        CountDownLatch closing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        adapter.handles.get("a").beforeClose = () -> {
            closing.countDown();
            try {
                release.await();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        };
        connections.end("a");
        Future<?> removing = background.submit(() -> connections.closeRetired("a"));
        assertThat(closing.await(5, TimeUnit.SECONDS)).isTrue();

        DeviceConnections.Connecting again = connections.begin(device("a", "stub"));
        Thread adding = Thread.ofPlatform().start(() -> connections.complete(again));
        await().until(() -> oldClosedWhenConnecting.size() == 2 || adding.getState() == Thread.State.WAITING);
        release.countDown();
        removing.get(5, TimeUnit.SECONDS);
        adding.join(5_000);

        assertThat(oldClosedWhenConnecting).containsExactly(true, true);
    }

    @Test
    void onlyTheCurrentConnectionStoresWhatItLearns() {
        List<Map<String, String>> stored = new CopyOnWriteArrayList<>();
        List<LearnedSettings> sinks = new CopyOnWriteArrayList<>();
        StubAdapter adapter = new StubAdapter("stub", DeviceKind.ANDROID_TV, false, false) {
            @Override
            public DeviceHandle connect(Device device, Consumer<DeviceState> onChange, LearnedSettings learned) {
                sinks.add(learned);
                return connect(device, onChange);
            }
        };
        DeviceConnections connections = new DeviceConnections(Map.of("stub", adapter), published::add,
                (deviceId, adapterId, updates) -> stored.add(updates));
        connections.complete(connections.begin(device("a", "stub")));
        connections.complete(connections.begin(device("a", "stub")));

        sinks.get(0).store(Map.of("mac", "AA:BB:CC:DD:EE:01"));
        sinks.get(1).store(Map.of("mac", "AA:BB:CC:DD:EE:02"));

        assertThat(stored).containsExactly(Map.of("mac", "AA:BB:CC:DD:EE:02"));
    }

    @Test
    void aTicketSupersededBeforeItCompletesConnectsNothing() {
        StubAdapter adapter = new StubAdapter("stub", DeviceKind.ANDROID_TV, false, false);
        DeviceConnections connections = connections(adapter);
        connections.complete(connections.begin(device("a", "stub")));
        StubAdapter.StubHandle old = adapter.handles.get("a");
        DeviceConnections.Connecting first = connections.begin(device("a", "stub"));
        DeviceConnections.Connecting second = connections.begin(device("a", "stub"));

        connections.complete(first);

        assertThat(old.closed).isTrue();
        assertThat(adapter.handles.get("a")).isSameAs(old);
        connections.complete(second);
        assertThat(connections.handles("a")).containsValue(adapter.handles.get("a")).doesNotContainValue(old);
    }

    @Test
    void aPreviousHandleThatFailsToCloseStillLetsTheNewOnesConnect() {
        StubAdapter adapter = new StubAdapter("stub", DeviceKind.ANDROID_TV, false, false);
        DeviceConnections connections = connections(adapter);
        connections.complete(connections.begin(device("a", "stub")));
        adapter.handles.get("a").closeFailure = new IllegalStateException("socket already gone");

        connections.complete(connections.begin(device("a", "stub")));

        assertThat(connections.state("a").status()).isEqualTo(DeviceStatus.CONNECTED);
        assertThat(connections.handles("a")).containsValue(adapter.handles.get("a"));
    }

    @Test
    void closeAllClosesTheOtherHandlesWhenOneFailsToClose() {
        StubAdapter adapter = new StubAdapter("stub", DeviceKind.ANDROID_TV, false, false);
        DeviceConnections connections = connections(adapter);
        connections.complete(connections.begin(device("a", "stub")));
        connections.complete(connections.begin(device("b", "stub")));
        adapter.handles.get("a").closeFailure = new IllegalStateException("socket already gone");

        connections.closeAll();

        assertThat(adapter.handles.get("b").closed).isTrue();
    }

    @Test
    void aReportDuringConnectCounts() {
        StubAdapter adapter = new StubAdapter("stub", DeviceKind.ANDROID_TV, false, false);
        DeviceConnections connections = connections(adapter);

        connections.complete(connections.begin(device("a", "stub")));

        assertThat(connections.state("a").status()).isEqualTo(DeviceStatus.CONNECTED);
        assertThat(published).anySatisfy(event -> assertThat(event).isInstanceOfSatisfying(
                DeviceStateChangedEvent.class, e -> assertThat(e.state().status()).isEqualTo(DeviceStatus.CONNECTED)));
    }

    @Test
    void aFailingAdapterLeavesNoHandlesAndPublishesDisconnected() {
        StubAdapter good = new StubAdapter("good", DeviceKind.ANDROID_TV, false, false);
        StubAdapter bad = new StubAdapter("bad", DeviceKind.CAST, false, false) {
            @Override
            public DeviceHandle connect(Device device, Consumer<DeviceState> onChange) {
                throw new IllegalStateException("no");
            }
        };
        DeviceConnections connections = connections(good, bad);
        Map<String, Map<String, String>> adapters = new LinkedHashMap<>();
        adapters.put("good", Map.of());
        adapters.put("bad", Map.of());
        Device both = new Device("a", "a", DeviceKind.ANDROID_TV, "10.0.0.5", adapters, Instant.EPOCH);

        connections.complete(connections.begin(both));

        assertThat(connections.handles("a")).isEmpty();
        assertThat(good.handles.get("a").closed).isTrue();
        assertThat(published.getLast()).isInstanceOfSatisfying(DeviceStateChangedEvent.class, e -> {
            assertThat(e.deviceId()).isEqualTo("a");
            assertThat(e.state().status()).isEqualTo(DeviceStatus.DISCONNECTED);
        });
    }

    @Test
    void aLateReportFromAnOldGenerationIsIgnored() {
        StubAdapter adapter = new StubAdapter("stub", DeviceKind.ANDROID_TV, false, false);
        DeviceConnections connections = connections(adapter);
        connections.complete(connections.begin(device("a", "stub")));
        StubAdapter.StubHandle old = adapter.handles.get("a");
        connections.complete(connections.begin(device("a", "stub")));
        published.clear();

        old.report(DeviceState.initial().withStatus(DeviceStatus.CONNECTING));

        assertThat(published).isEmpty();
        assertThat(connections.state("a").status()).isEqualTo(DeviceStatus.CONNECTED);
    }

    @Test
    void aTicketCompletedAfterCloseAllIsClosed() throws Exception {
        BlockingAdapter slow = new BlockingAdapter("slow", "a");
        DeviceConnections connections = connections(slow);
        DeviceConnections.Connecting ticket = connections.begin(device("a", "slow"));
        Future<?> completing = background.submit(() -> connections.complete(ticket));
        assertThat(slow.entered.await(5, TimeUnit.SECONDS)).isTrue();

        connections.closeAll();
        slow.release();
        completing.get(5, TimeUnit.SECONDS);

        assertThat(slow.handles.get("a").closed).isTrue();
        assertThat(connections.handles("a")).isEmpty();
    }
}
