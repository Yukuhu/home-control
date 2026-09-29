package dev.andre.homecontrol.device;

import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.CastAppQuery;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceCommands;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DeviceRegistry;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStateChangedEvent;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.UnsupportedActionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The fall-through rules of stop and query, and the per-connect state generations. */
class DevicesFallThroughTest {

    private static final CastAppQuery MDX = new CastAppQuery("233637DE", "urn:x-cast:com.google.youtube.mdx",
            Map.of("type", "getMdxSessionStatus"), "mdxSessionStatus");

    @TempDir
    Path dir;

    private final List<Object> published = new CopyOnWriteArrayList<>();
    private final StubAdapter cast = new StubAdapter("cast", DeviceKind.CAST, true, false,
            Capability.CAST_RECEIVER, Capability.VOLUME);
    private final StubAdapter upnp = new StubAdapter("upnp", DeviceKind.UPNP, true, false,
            Capability.MEDIA_RENDERER, Capability.VOLUME);
    private final StubAdapter remote = new StubAdapter("remote", DeviceKind.ANDROID_TV, false, false,
            Capability.REMOTE_KEYS);
    private DeviceRegistry registry;
    private Devices devices;

    @BeforeEach
    void setUp() {
        registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
        devices = Devices.assemble(registry, List.of(cast, upnp, remote), published::add);
    }

    @AfterEach
    void tearDown() {
        devices.close();
    }

    private void register(String... adapterIds) {
        Map<String, Map<String, String>> adapters = new LinkedHashMap<>();
        for (String adapterId : adapterIds) {
            adapters.put(adapterId, Map.of());
        }
        registry.save(new Device("tv", "TV", DeviceKind.CAST, "10.0.0.31", adapters, Instant.now()));
    }

    @Test
    void aStopNobodyCouldSendGivesTheFirstOfflineReasonOverTheLastUnsupportedOne() {
        register("cast", "upnp");
        devices.start();
        cast.handles.get("tv").failure = new DeviceOfflineException("cast is gone");
        upnp.handles.get("tv").failure = new UnsupportedActionException("upnp cannot");

        var stop = new Action.Stop();
        DeviceCommands commands = devices.commands();
        assertThatThrownBy(() -> commands.execute("tv", stop))
                .isInstanceOf(DeviceOfflineException.class)
                .hasMessage("cast is gone");
    }

    @Test
    void aStopEveryAdapterCannotDoGivesTheLastUnsupportedReason() {
        register("cast", "upnp");
        devices.start();
        cast.handles.get("tv").failure = new UnsupportedActionException("first reason");
        upnp.handles.get("tv").failure = new UnsupportedActionException("last reason");

        var stop = new Action.Stop();
        DeviceCommands commands = devices.commands();
        assertThatThrownBy(() -> commands.execute("tv", stop))
                .isInstanceOf(UnsupportedActionException.class)
                .hasMessage("last reason");
    }

    @Test
    void aStopWithoutLiveHandlesIsOffline() {
        register("cast", "upnp");
        // not started: no handles

        var stop = new Action.Stop();
        DeviceCommands commands = devices.commands();
        assertThatThrownBy(() -> commands.execute("tv", stop))
                .isInstanceOf(DeviceOfflineException.class)
                .hasMessage("TV is not connected");
    }

    @Test
    void aStopNoAdapterDeclaresIsPlainlyUnsupported() {
        register("remote");
        devices.start();

        var stop = new Action.Stop();
        DeviceCommands commands = devices.commands();
        assertThatThrownBy(() -> commands.execute("tv", stop))
                .isInstanceOf(UnsupportedActionException.class)
                .hasMessage("TV cannot stop playback");
        assertThat(remote.handles.get("tv").executed).isEmpty();
    }

    @Test
    void anOfflineOrUnsupportedCastAdapterHandsTheQuestionToTheNextOne() {
        StubAdapter second = new StubAdapter("cast2", DeviceKind.CAST, true, false, Capability.CAST_RECEIVER);
        StubAdapter third = new StubAdapter("cast3", DeviceKind.CAST, true, false, Capability.CAST_RECEIVER);
        devices.close();
        devices = Devices.assemble(registry, List.of(cast, second, third), published::add);
        register("cast", "cast2", "cast3");
        devices.start();
        cast.handles.get("tv").failure = new DeviceOfflineException("cast is gone");
        second.handles.get("tv").failure = new UnsupportedActionException("cast2 cannot");
        third.handles.get("tv").answer = Map.of("type", "mdxSessionStatus");

        assertThat(devices.commands().query("tv", MDX)).isEqualTo(Map.of("type", "mdxSessionStatus"));

        third.handles.get("tv").failure = new UnsupportedActionException("cast3 cannot");
        DeviceCommands commands = devices.commands();
        assertThatThrownBy(() -> commands.query("tv", MDX))
                .isInstanceOf(DeviceOfflineException.class)
                .hasMessage("cast is gone");

        cast.handles.get("tv").failure = new UnsupportedActionException("cast cannot");
        assertThatThrownBy(() -> commands.query("tv", MDX))
                .isInstanceOf(UnsupportedActionException.class)
                .hasMessage("cast3 cannot");
    }

    @Test
    void aHandleOfAClosedConnectReportingLateIsIgnored() {
        register("cast");
        devices.start();
        StubAdapter.StubHandle first = cast.handles.get("tv");
        devices.enrollment().adopt(registry.findById("tv").orElseThrow());
        StubAdapter.StubHandle second = cast.handles.get("tv");
        assertThat(second).isNotSameAs(first);
        published.clear();

        first.report(DeviceState.initial().withStatus(DeviceStatus.DISCONNECTED));

        assertThat(published).isEmpty();
        assertThat(devices.queries().state("tv").status()).isEqualTo(DeviceStatus.CONNECTED);

        second.report(DeviceState.initial().withStatus(DeviceStatus.DISCONNECTED));

        assertThat(published).singleElement().isInstanceOfSatisfying(DeviceStateChangedEvent.class, event -> {
            assertThat(event.deviceId()).isEqualTo("tv");
            assertThat(event.state().status()).isEqualTo(DeviceStatus.DISCONNECTED);
        });
        assertThat(devices.queries().state("tv").status()).isEqualTo(DeviceStatus.DISCONNECTED);
    }

    @Test
    void generatedIdsNeverStartOrEndWithADash() {
        assertThat(DeviceMatching.uniqueId(List.of(), "upnp", "[FE80::1]")).isEqualTo("upnp-fe80-1");
        assertThat(DeviceMatching.uniqueId(List.of(), "upnp", "10.0.0.5")).isEqualTo("upnp-10-0-0-5");
    }
}
