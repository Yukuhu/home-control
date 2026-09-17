package dev.andre.homecontrol.device;

import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.CastAppQuery;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceNotFoundException;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DeviceRegistry;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeviceManagerQueryTest {

    private static final CastAppQuery MDX = new CastAppQuery("233637DE", "urn:x-cast:com.google.youtube.mdx",
            Map.of("type", "getMdxSessionStatus"), "mdxSessionStatus");

    @TempDir
    Path dir;

    private final StubAdapter androidtv = new StubAdapter("androidtv", DeviceKind.ANDROID_TV, false, true,
            Capability.REMOTE_KEYS, Capability.APP_LINK, Capability.VOLUME);
    private final StubAdapter cast = new StubAdapter("cast", DeviceKind.CAST, true, false,
            Capability.CAST_RECEIVER, Capability.VOLUME);
    private DeviceRegistry registry;
    private DeviceManager manager;

    @BeforeEach
    void setUp() {
        registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.close();
        }
    }

    private void register(String... adapterIds) {
        Map<String, Map<String, String>> adapters = new LinkedHashMap<>();
        for (String adapterId : adapterIds) {
            adapters.put(adapterId, Map.of());
        }
        registry.save(new Device("shield", "Shield", DeviceKind.ANDROID_TV, "10.0.0.5", adapters, Instant.now()));
        manager = new DeviceManager(registry, List.of(androidtv, cast), event -> { });
    }

    @Test
    void asksTheFirstCastAdapter() {
        register("androidtv", "cast");
        manager.start();
        androidtv.handles.get("shield").answer = Map.of("type", "wrong");
        cast.handles.get("shield").answer = Map.of("type", "mdxSessionStatus");

        assertThat(manager.query("shield", MDX)).isEqualTo(Map.of("type", "mdxSessionStatus"));
        assertThat(cast.handles.get("shield").queried).containsExactly(MDX);
        assertThat(androidtv.handles.get("shield").queried).isEmpty();
    }

    @Test
    void offlineCastSideIsOffline() {
        register("androidtv", "cast");
        // not started: no handles

        assertThatThrownBy(() -> manager.query("shield", MDX))
                .isInstanceOf(DeviceOfflineException.class)
                .hasMessage("Shield is not connected");
    }

    @Test
    void noCastAdapterIsUnsupported() {
        register("androidtv");
        manager.start();

        assertThatThrownBy(() -> manager.query("shield", MDX))
                .isInstanceOf(UnsupportedActionException.class)
                .hasMessage("Shield is not a Cast receiver");
        assertThat(androidtv.handles.get("shield").queried).isEmpty();
    }

    @Test
    void failuresPropagate() {
        register("androidtv", "cast");
        manager.start();
        ActionFailedException refused = new ActionFailedException("Shield refused the request (nope)");
        cast.handles.get("shield").failure = refused;

        assertThatThrownBy(() -> manager.query("shield", MDX)).isSameAs(refused);
    }

    @Test
    void unknownDevice() {
        register("cast");

        assertThatThrownBy(() -> manager.query("ghost", MDX)).isInstanceOf(DeviceNotFoundException.class);
    }
}
