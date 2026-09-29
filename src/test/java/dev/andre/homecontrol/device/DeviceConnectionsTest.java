package dev.andre.homecontrol.device;

import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceAdapter;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStateChangedEvent;
import dev.andre.homecontrol.core.DeviceStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class DeviceConnectionsTest {

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
        StubAdapter adapter = new StubAdapter("stub", DeviceKind.ANDROID_TV, false, false);
        DeviceConnections connections = connections(adapter);
        connections.complete(connections.begin(device("a", "stub")));
        StubAdapter.StubHandle old = adapter.handles.get("a");

        DeviceConnections.Connecting again = connections.begin(device("a", "stub"));
        assertThat(old.closed).isFalse();
        connections.complete(again);

        assertThat(old.closed).isTrue();
        assertThat(adapter.handles.get("a")).isNotSameAs(old);
        assertThat(connections.handles("a")).containsValue(adapter.handles.get("a"));
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
