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
import dev.andre.homecontrol.core.RemoteKey;
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

        var failingAction89 = new Action.SetVolume(5);
        assertThatThrownBy(() -> manager.execute("shield", failingAction89))
                .isInstanceOf(DeviceOfflineException.class)
                .hasMessageContaining("paired again");
    }

    @Test
    void whenEveryAdapterRefusesTheLastReasonIsGiven() {
        androidtv.handles.get("shield").failure = new UnsupportedActionException("first reason");
        cast.handles.get("shield").failure = new UnsupportedActionException("last reason");

        var failingAction99 = new Action.SetVolume(5);
        assertThatThrownBy(() -> manager.execute("shield", failingAction99))
                .isInstanceOf(UnsupportedActionException.class)
                .hasMessage("last reason");
    }

    @Test
    void aRefusalByTheDeviceIsFinal() {
        androidtv.handles.get("shield").failure = new ActionFailedException("Shield refused");

        var failingAction108 = new Action.SetVolume(5);
        assertThatThrownBy(() -> manager.execute("shield", failingAction108))
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

        var failingAction135 = new Action.SetVolume(5);
        assertThatThrownBy(() -> manager.execute("shield", failingAction135))
                .isInstanceOf(DeviceOfflineException.class)
                .hasMessage("Shield is not connected");
        var failingAction138 = new Action.OpenAppLink(URI.create("https://a.example"));
        assertThatThrownBy(() -> manager.execute("shield", failingAction138))
                .isInstanceOf(UnsupportedActionException.class);
    }

    @Test
    void stopReachesEveryAdapterThatCanStop() {
        registry.save(new Device("tv", "TV", DeviceKind.CAST, "10.0.0.31",
                orderedAdapters("cast", "upnp"), Instant.now()));
        StubAdapter upnp = new StubAdapter("upnp", DeviceKind.UPNP, true, false, Capability.MEDIA_RENDERER, Capability.VOLUME);
        StubAdapter tvCast = new StubAdapter("cast", DeviceKind.CAST, true, false, Capability.CAST_RECEIVER, Capability.VOLUME);
        DeviceManager both = new DeviceManager(registry, List.of(tvCast, upnp), event -> { });
        both.start();
        try {
            both.execute("tv", new Action.Stop());
            assertThat(tvCast.handles.get("tv").executed).containsExactly(new Action.Stop());
            assertThat(upnp.handles.get("tv").executed).containsExactly(new Action.Stop());

            // One adapter's refusal does not keep the other from stopping; the stop counts as done.
            tvCast.handles.get("tv").failure = new ActionFailedException("nothing is casting");
            both.execute("tv", new Action.Stop());
            assertThat(upnp.handles.get("tv").executed).hasSize(2);

            // Only when nobody could stop is the failure reported.
            upnp.handles.get("tv").failure = new DeviceOfflineException("gone");
            var failingAction162 = new Action.Stop();
            assertThatThrownBy(() -> both.execute("tv", failingAction162)).isInstanceOf(ActionFailedException.class);
        } finally {
            both.close();
        }
    }

    private static Map<String, Map<String, String>> orderedAdapters(String... ids) {
        Map<String, Map<String, String>> adapters = new LinkedHashMap<>();
        for (String id : ids) {
            adapters.put(id, Map.of());
        }
        return adapters;
    }

    @Test
    void playbackReachesALocalAudioSink() {
        registry.save(new Device("bluetooth-aa-bb-cc-dd-ee-ff", "JBL Flip 5", DeviceKind.BLUETOOTH, "AA:BB:CC:DD:EE:FF",
                Map.of("bluetooth", Map.of()), Instant.now()));
        StubAdapter bluetooth = new StubAdapter("bluetooth", DeviceKind.BLUETOOTH, false, false,
                Capability.LOCAL_AUDIO_SINK, Capability.VOLUME);
        DeviceManager speakers = new DeviceManager(registry, List.of(bluetooth), event -> { });
        speakers.start();
        try {
            speakers.execute("bluetooth-aa-bb-cc-dd-ee-ff", new Action.PlayMedia(URI.create("http://nas/a.mp3"), "audio/mpeg", "A", null));
            speakers.execute("bluetooth-aa-bb-cc-dd-ee-ff", new Action.Pause());
            speakers.execute("bluetooth-aa-bb-cc-dd-ee-ff", new Action.Resume());
            speakers.execute("bluetooth-aa-bb-cc-dd-ee-ff", new Action.Stop());
            speakers.execute("bluetooth-aa-bb-cc-dd-ee-ff", new Action.SetVolume(20));
            speakers.execute("bluetooth-aa-bb-cc-dd-ee-ff", new Action.Mute(true));

            assertThat(bluetooth.handles.get("bluetooth-aa-bb-cc-dd-ee-ff").executed).containsExactly(
                    new Action.PlayMedia(URI.create("http://nas/a.mp3"), "audio/mpeg", "A", null),
                    new Action.Pause(), new Action.Resume(), new Action.Stop(),
                    new Action.SetVolume(20), new Action.Mute(true));

            var failingAction197 = new Action.PressKey(RemoteKey.HOME);
            assertThatThrownBy(() -> speakers.execute("bluetooth-aa-bb-cc-dd-ee-ff", failingAction197))
                    .isInstanceOf(UnsupportedActionException.class);
        } finally {
            speakers.close();
        }
    }

    @Test
    void stopReachesAMediaRendererWithoutCast() {
        registry.save(new Device("speaker", "Speaker", DeviceKind.UPNP, "10.0.0.30",
                Map.of("upnp", Map.of()), Instant.now()));
        StubAdapter upnp = new StubAdapter("upnp", DeviceKind.UPNP, true, false,
                Capability.MEDIA_RENDERER, Capability.VOLUME);
        DeviceManager speakers = new DeviceManager(registry, List.of(upnp), event -> { });
        speakers.start();
        try {
            speakers.execute("speaker", new Action.Stop());

            assertThat(upnp.handles.get("speaker").executed).containsExactly(new Action.Stop());
            var failingAction216 = new Action.PressKey(RemoteKey.HOME);
            assertThatThrownBy(() -> speakers.execute("speaker", failingAction216))
                    .isInstanceOf(UnsupportedActionException.class);
        } finally {
            speakers.close();
        }
    }
}
