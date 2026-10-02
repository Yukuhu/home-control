package dev.andre.homecontrol.device;

import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceCommands;
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

class DevicesExecuteTest {

    @TempDir
    Path dir;

    /** A TV adapter that also takes absolute volume, as webOS does; asked before the receiver. */
    private final StubAdapter remote = new StubAdapter("remote", DeviceKind.ANDROID_TV, false, true,
            Capability.REMOTE_KEYS, Capability.APP_LINK, Capability.VOLUME);
    private final StubAdapter cast = new StubAdapter("cast", DeviceKind.CAST, true, false,
            Capability.CAST_RECEIVER, Capability.VOLUME);
    private DeviceRegistry registry;
    private Devices devices;

    @BeforeEach
    void aMergedShield() {
        registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
        Map<String, Map<String, String>> adapters = new LinkedHashMap<>();
        adapters.put("remote", Map.of("port", "6466"));
        adapters.put("cast", Map.of("port", "8009"));
        registry.save(new Device("shield", "Shield", DeviceKind.ANDROID_TV, "10.0.0.5", adapters, Instant.now()));
        devices = Devices.assemble(registry, List.of(remote, cast), event -> { });
        devices.start();
    }

    @AfterEach
    void tearDown() {
        devices.close();
    }

    @Test
    void theFirstAdapterThatCanDoItIsTheOnlyOneAsked() {
        devices.commands().execute("shield", new Action.SetVolume(10));

        assertThat(remote.handles.get("shield").executed).containsExactly(new Action.SetVolume(10));
        assertThat(cast.handles.get("shield").executed).isEmpty();
    }

    @Test
    void anActionTheFirstAdapterCannotDoFallsThroughToTheNext() {
        remote.handles.get("shield").failure = new UnsupportedActionException("no absolute volume");

        devices.commands().execute("shield", new Action.SetVolume(40));

        assertThat(cast.handles.get("shield").executed).containsExactly(new Action.SetVolume(40));
    }

    @Test
    void anOfflineFirstAdapterFallsThroughToo() {
        remote.handles.get("shield").failure = new DeviceOfflineException("must be paired again");

        devices.commands().execute("shield", new Action.Mute(true));

        assertThat(cast.handles.get("shield").executed).containsExactly(new Action.Mute(true));
    }

    @Test
    void whenNoAdapterCouldSendTheOfflineReasonWins() {
        remote.handles.get("shield").failure = new DeviceOfflineException("Shield must be paired again");
        cast.handles.get("shield").failure = new UnsupportedActionException("not this one");

        var setVolume = new Action.SetVolume(5);
        DeviceCommands commands = devices.commands();
        assertThatThrownBy(() -> commands.execute("shield", setVolume))
                .isInstanceOf(DeviceOfflineException.class)
                .hasMessageContaining("paired again");
    }

    @Test
    void whenEveryAdapterRefusesTheLastReasonIsGiven() {
        remote.handles.get("shield").failure = new UnsupportedActionException("first reason");
        cast.handles.get("shield").failure = new UnsupportedActionException("last reason");

        var setVolume = new Action.SetVolume(5);
        DeviceCommands commands = devices.commands();
        assertThatThrownBy(() -> commands.execute("shield", setVolume))
                .isInstanceOf(UnsupportedActionException.class)
                .hasMessage("last reason");
    }

    @Test
    void aRefusalByTheDeviceIsFinal() {
        remote.handles.get("shield").failure = new ActionFailedException("Shield refused");

        var setVolume = new Action.SetVolume(5);
        DeviceCommands commands = devices.commands();
        assertThatThrownBy(() -> commands.execute("shield", setVolume))
                .isInstanceOf(ActionFailedException.class);
        assertThat(cast.handles.get("shield").executed).isEmpty();
    }

    @Test
    void onlyAdaptersDeclaringTheCapabilityAreAsked() {
        devices.commands().execute("shield", new Action.Stop());

        assertThat(remote.handles.get("shield").executed).isEmpty();
        assertThat(cast.handles.get("shield").executed).containsExactly(new Action.Stop());
    }

    @Test
    void declaringAdaptersWithoutLiveHandlesMeanOfflineNotUnsupported() {
        StubAdapter broken = new StubAdapter("remote", DeviceKind.ANDROID_TV, false, true,
                Capability.REMOTE_KEYS, Capability.VOLUME) {
            @Override
            public DeviceHandle connect(Device device, Consumer<DeviceState> onChange) {
                throw new IllegalStateException("no connection");
            }
        };
        devices.close();
        // A failed connect leaves the whole device without handles.
        devices = Devices.assemble(registry, List.of(broken, cast), event -> { });
        devices.start();

        var setVolume = new Action.SetVolume(5);
        DeviceCommands commands = devices.commands();
        assertThatThrownBy(() -> commands.execute("shield", setVolume))
                .isInstanceOf(DeviceOfflineException.class)
                .hasMessage("Shield is not connected");
        var openLink = new Action.OpenAppLink(URI.create("https://a.example"));
        assertThatThrownBy(() -> commands.execute("shield", openLink))
                .isInstanceOf(UnsupportedActionException.class);
    }

    @Test
    void stopReachesEveryAdapterThatCanStop() {
        registry.save(new Device("tv", "TV", DeviceKind.CAST, "10.0.0.31",
                orderedAdapters("cast", "upnp"), Instant.now()));
        StubAdapter upnp = new StubAdapter("upnp", DeviceKind.UPNP, true, false, Capability.MEDIA_RENDERER, Capability.VOLUME);
        StubAdapter tvCast = new StubAdapter("cast", DeviceKind.CAST, true, false, Capability.CAST_RECEIVER, Capability.VOLUME);
        Devices both = Devices.assemble(registry, List.of(tvCast, upnp), event -> { });
        both.start();
        try {
            both.commands().execute("tv", new Action.Stop());
            assertThat(tvCast.handles.get("tv").executed).containsExactly(new Action.Stop());
            assertThat(upnp.handles.get("tv").executed).containsExactly(new Action.Stop());

            // One adapter's refusal does not keep the other from stopping; the stop counts as done.
            tvCast.handles.get("tv").failure = new ActionFailedException("nothing is casting");
            both.commands().execute("tv", new Action.Stop());
            assertThat(upnp.handles.get("tv").executed).hasSize(2);

            // Only when nobody could stop is the failure reported.
            upnp.handles.get("tv").failure = new DeviceOfflineException("gone");
            var stop = new Action.Stop();
            DeviceCommands commands = both.commands();
            assertThatThrownBy(() -> commands.execute("tv", stop)).isInstanceOf(ActionFailedException.class);
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
        Devices speakers = Devices.assemble(registry, List.of(bluetooth), event -> { });
        speakers.start();
        try {
            speakers.commands().execute("bluetooth-aa-bb-cc-dd-ee-ff", new Action.PlayMedia(URI.create("http://nas/a.mp3"), "audio/mpeg", "A", null));
            speakers.commands().execute("bluetooth-aa-bb-cc-dd-ee-ff", new Action.Pause());
            speakers.commands().execute("bluetooth-aa-bb-cc-dd-ee-ff", new Action.Resume());
            speakers.commands().execute("bluetooth-aa-bb-cc-dd-ee-ff", new Action.Stop());
            speakers.commands().execute("bluetooth-aa-bb-cc-dd-ee-ff", new Action.SetVolume(20));
            speakers.commands().execute("bluetooth-aa-bb-cc-dd-ee-ff", new Action.Mute(true));

            assertThat(bluetooth.handles.get("bluetooth-aa-bb-cc-dd-ee-ff").executed).containsExactly(
                    new Action.PlayMedia(URI.create("http://nas/a.mp3"), "audio/mpeg", "A", null),
                    new Action.Pause(), new Action.Resume(), new Action.Stop(),
                    new Action.SetVolume(20), new Action.Mute(true));

            var pressHome = new Action.PressKey(RemoteKey.HOME);
            DeviceCommands commands = speakers.commands();
            assertThatThrownBy(() -> commands.execute("bluetooth-aa-bb-cc-dd-ee-ff", pressHome))
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
        Devices speakers = Devices.assemble(registry, List.of(upnp), event -> { });
        speakers.start();
        try {
            speakers.commands().execute("speaker", new Action.Stop());

            assertThat(upnp.handles.get("speaker").executed).containsExactly(new Action.Stop());
            var pressHome = new Action.PressKey(RemoteKey.HOME);
            DeviceCommands commands = speakers.commands();
            assertThatThrownBy(() -> commands.execute("speaker", pressHome))
                    .isInstanceOf(UnsupportedActionException.class);
        } finally {
            speakers.close();
        }
    }

    @Test
    void aSelectInputIsRefusedWithoutReachingAnAdapterThatCannotSwitchInputs() {
        registry.save(new Device("tv", "TV", DeviceKind.ANDROID_TV, "10.0.0.41", orderedAdapters("remote"),
                Instant.now()));
        StubAdapter remote = new StubAdapter("remote", DeviceKind.ANDROID_TV, false, true, Capability.REMOTE_KEYS,
                Capability.APP_LINK, Capability.ANDROID_APPS);
        try (Devices tv = Devices.assemble(registry, List.of(remote), event -> { })) {
            tv.start();
            DeviceCommands commands = tv.commands();
            var hdmi = new Action.SelectInput("HDMI_1");

            assertThatThrownBy(() -> commands.execute("tv", hdmi))
                    .isInstanceOf(UnsupportedActionException.class).hasMessage("TV cannot switch inputs");
            assertThat(remote.handles.get("tv").executed).isEmpty();
        }
    }

    @Test
    void joiningAGroupNeverReachesARendererThatCannotGroup() {
        registry.save(new Device("renderer", "Renderer", DeviceKind.UPNP, "10.0.0.42", orderedAdapters("upnp"),
                Instant.now()));
        StubAdapter upnp = new StubAdapter("upnp", DeviceKind.UPNP, true, false, Capability.MEDIA_RENDERER,
                Capability.VOLUME);
        try (Devices renderer = Devices.assemble(registry, List.of(upnp), event -> { })) {
            renderer.start();
            DeviceCommands commands = renderer.commands();
            var join = new Action.JoinGroup("RINCON_1");

            assertThatThrownBy(() -> commands.execute("renderer", join))
                    .isInstanceOf(UnsupportedActionException.class).hasMessage("Renderer cannot be grouped");
            assertThat(upnp.handles.get("renderer").executed).isEmpty();
        }
    }

    @Test
    void volumeOnAShieldWithCastReachesCastOnly() {
        registry.save(new Device("living", "Living", DeviceKind.ANDROID_TV, "10.0.0.43",
                orderedAdapters("remote", "cast"), Instant.now()));
        StubAdapter shield = new StubAdapter("remote", DeviceKind.ANDROID_TV, false, true, Capability.REMOTE_KEYS,
                Capability.APP_LINK, Capability.ANDROID_APPS);
        StubAdapter shieldCast = new StubAdapter("cast", DeviceKind.CAST, true, false, Capability.CAST_RECEIVER,
                Capability.VOLUME);
        try (Devices living = Devices.assemble(registry, List.of(shield, shieldCast), event -> { })) {
            living.start();

            living.commands().execute("living", new Action.SetVolume(30));

            assertThat(shieldCast.handles.get("living").executed).containsExactly(new Action.SetVolume(30));
            assertThat(shield.handles.get("living").executed).isEmpty();
        }
    }
}
