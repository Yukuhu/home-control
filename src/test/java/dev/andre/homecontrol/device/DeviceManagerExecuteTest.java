package dev.andre.homecontrol.device;

import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DeviceRegistry;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.UnsupportedActionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeviceManagerExecuteTest {

    @TempDir
    Path dir;

    private final StubAdapter androidtv = new StubAdapter("androidtv", DeviceKind.ANDROID_TV, false, true,
            Capability.REMOTE_KEYS, Capability.APP_LINK, Capability.VOLUME);
    private final StubAdapter cast = new StubAdapter("cast", DeviceKind.CAST, true, false,
            Capability.CAST_RECEIVER, Capability.VOLUME);
    private DeviceRegistry registry;
    private DeviceManager manager;

    @BeforeEach
    void aMergedShield() {
        registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
        Map<String, Map<String, String>> adapters = new LinkedHashMap<>();
        adapters.put("androidtv", Map.of("port", "6466"));
        adapters.put("cast", Map.of("port", "8009"));
        registry.save(new Device("shield", "Shield", DeviceKind.ANDROID_TV, "10.0.0.5", adapters, Instant.now()));
        manager = new DeviceManager(registry, List.of(androidtv, cast), event -> { });
        manager.start();
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    @Test
    void theFirstAdapterThatCanDoItIsTheOnlyOneAsked() {
        manager.execute("shield", new Action.SetVolume(10));

        assertThat(androidtv.handles.get("shield").executed).containsExactly(new Action.SetVolume(10));
        assertThat(cast.handles.get("shield").executed).isEmpty();
    }

    @Test
    void anActionTheFirstAdapterCannotDoFallsThroughToTheNext() {
        androidtv.handles.get("shield").failure = new UnsupportedActionException("no absolute volume");

        manager.execute("shield", new Action.SetVolume(40));

        assertThat(cast.handles.get("shield").executed).containsExactly(new Action.SetVolume(40));
    }

    @Test
    void anOfflineFirstAdapterFallsThroughToo() {
        androidtv.handles.get("shield").failure = new DeviceOfflineException("must be paired again");

        manager.execute("shield", new Action.Mute(true));

        assertThat(cast.handles.get("shield").executed).containsExactly(new Action.Mute(true));
    }

    @Test
    void whenNoAdapterCouldSendTheOfflineReasonWins() {
        androidtv.handles.get("shield").failure = new DeviceOfflineException("Shield must be paired again");
        cast.handles.get("shield").failure = new UnsupportedActionException("not this one");

        assertThatThrownBy(() -> manager.execute("shield", new Action.SetVolume(5)))
                .isInstanceOf(DeviceOfflineException.class)
                .hasMessageContaining("paired again");
    }

    @Test
    void whenEveryAdapterRefusesTheLastReasonIsGiven() {
        androidtv.handles.get("shield").failure = new UnsupportedActionException("first reason");
        cast.handles.get("shield").failure = new UnsupportedActionException("last reason");

        assertThatThrownBy(() -> manager.execute("shield", new Action.SetVolume(5)))
                .isInstanceOf(UnsupportedActionException.class)
                .hasMessage("last reason");
    }

    @Test
    void aRefusalByTheDeviceIsFinal() {
        androidtv.handles.get("shield").failure = new ActionFailedException("Shield refused");

        assertThatThrownBy(() -> manager.execute("shield", new Action.SetVolume(5)))
                .isInstanceOf(ActionFailedException.class);
        assertThat(cast.handles.get("shield").executed).isEmpty();
    }

    @Test
    void onlyAdaptersDeclaringTheCapabilityAreAsked() {
        manager.execute("shield", new Action.Stop());

        assertThat(androidtv.handles.get("shield").executed).isEmpty();
        assertThat(cast.handles.get("shield").executed).containsExactly(new Action.Stop());
    }

    @Test
    void declaringAdaptersWithoutLiveHandlesMeanOfflineNotUnsupported() {
        StubAdapter broken = new StubAdapter("androidtv", DeviceKind.ANDROID_TV, false, true,
                Capability.REMOTE_KEYS, Capability.VOLUME) {
            @Override
            public DeviceHandle connect(Device device, Consumer<DeviceState> onChange) {
                throw new IllegalStateException("no connection");
            }
        };
        manager.close();
        // A failed connect leaves the whole device without handles.
        manager = new DeviceManager(registry, List.of(broken, cast), event -> { });
        manager.start();

        assertThatThrownBy(() -> manager.execute("shield", new Action.SetVolume(5)))
                .isInstanceOf(DeviceOfflineException.class)
                .hasMessage("Shield is not connected");
        assertThatThrownBy(() -> manager.execute("shield", new Action.OpenAppLink(URI.create("https://a.example"))))
                .isInstanceOf(UnsupportedActionException.class);
    }
}
