package dev.andre.homecontrol.adapters.bluetooth;

import dev.andre.homecontrol.adapters.bluetooth.bluez.BluetoothDeviceInfo;
import dev.andre.homecontrol.adapters.bluetooth.bluez.FakeBluezClient;
import dev.andre.homecontrol.adapters.bluetooth.player.AudioDeviceResolver;
import dev.andre.homecontrol.adapters.bluetooth.player.FakeMpv;
import dev.andre.homecontrol.adapters.bluetooth.player.InProcessMpvLauncher;
import dev.andre.homecontrol.adapters.bluetooth.player.MpvNotInstalledException;
import dev.andre.homecontrol.adapters.bluetooth.player.MpvPlayer;
import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.PlaybackState;
import dev.andre.homecontrol.core.RemoteKey;
import dev.andre.homecontrol.core.UnsupportedActionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static dev.andre.homecontrol.adapters.bluetooth.bluez.BluezFailure.UNREACHABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class BluetoothSpeakerSessionTest {

    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final Action.PlayMedia PLAY = new Action.PlayMedia(
            URI.create("http://127.0.0.1:9/music/song.mp3?ApiKey=secret-key"), "audio/mpeg", "Bunny Song", "The Rabbits");

    @TempDir
    Path runtime;

    private final FakeBluezClient bluez = new FakeBluezClient();
    private final InProcessMpvLauncher launcher = new InProcessMpvLauncher();
    private final BluetoothProperties properties = BluetoothProperties.defaults().withTimings(1, 1, 5, 5, 2);
    private final List<dev.andre.homecontrol.core.DeviceState> states = new CopyOnWriteArrayList<>();
    private Device device;
    private BluetoothSpeakerSession session;

    @BeforeEach
    void setUp() {
        launcher.options = FakeMpv.Options.defaults().withAudioDevices(
                "pulse/alsa_output.platform-bcm2835_audio.stereo-fallback=Built-in Audio",
                "pulse/bluez_output.AA_BB_CC_DD_EE_FF.1=JBL Flip 5");
        device = new Device("bluetooth-aa-bb-cc-dd-ee-ff", "JBL Flip 5", DeviceKind.BLUETOOTH, "AA:BB:CC:DD:EE:FF",
                Map.of("bluetooth", new BluetoothSettings("AA:BB:CC:DD:EE:FF", FakeBluezClient.ADAPTER, "").toMap()),
                Instant.now());
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
        launcher.close();
    }

    private BluetoothSpeakerSession start(BluetoothProperties props) {
        MpvPlayer player = new MpvPlayer(launcher, MpvPlayer.socketFor(runtime, device.id()),
                Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(1));
        AudioDeviceResolver resolver = new AudioDeviceResolver(launcher, props.audioDeviceTemplate(), Duration.ofSeconds(2));
        session = new BluetoothSpeakerSession(device, props, bluez, player, resolver, states::add);
        session.start();
        return session;
    }

    private BluetoothSpeakerSession start() {
        return start(properties);
    }

    @Test
    void playsOnTheSpeakersOwnOutput() {
        bluez.known("AA:BB:CC:DD:EE:FF", "JBL Flip 5").paired(true).connected(true).uuids(BluetoothDeviceInfo.A2DP_SINK);
        start();
        await().atMost(WAIT).untilAsserted(() -> assertThat(session.state().status()).isEqualTo(DeviceStatus.CONNECTED));

        session.execute(PLAY);

        assertThat(launcher.starts).hasSize(1);
        assertThat(launcher.starts.getFirst()).anyMatch(a -> a.equals("--audio-device=pulse/bluez_output.AA_BB_CC_DD_EE_FF.1"))
                .anyMatch(a -> a.equals("--volume=50"))
                .noneMatch(a -> a.contains("127.0.0.1"))
                .noneMatch(a -> a.contains("secret-key"));
        assertThat(launcher.latest().commands()).contains(
                List.of("loadfile", "http://127.0.0.1:9/music/song.mp3?ApiKey=secret-key", "replace"));
        await().atMost(WAIT).untilAsserted(() -> {
            assertThat(session.state().nowPlaying()).isNotNull();
            assertThat(session.state().nowPlaying().title()).isEqualTo("Bunny Song");
            assertThat(session.state().nowPlaying().state()).isEqualTo(PlaybackState.PLAYING);
            assertThat(session.state().nowPlaying().durationSeconds()).isEqualTo(187.0);
        });
    }

    @Test
    void connectsADisconnectedSpeakerBeforePlaying() {
        bluez.known("AA:BB:CC:DD:EE:FF", "JBL Flip 5").paired(true).connected(false).uuids(BluetoothDeviceInfo.A2DP_SINK);
        start(properties.withAutoConnect(false));

        session.execute(PLAY);

        assertThat(bluez.calls()).contains("connect AA:BB:CC:DD:EE:FF");
    }

    @Test
    void aSpeakerThatCannotConnectIsOffline() {
        bluez.known("AA:BB:CC:DD:EE:FF", "JBL Flip 5").paired(true).connected(false).uuids(BluetoothDeviceInfo.A2DP_SINK);
        bluez.failAlways("connect", UNREACHABLE, "br-connection-page-timeout");
        start(properties.withAutoConnect(false));

        assertThatThrownBy(() -> session.execute(PLAY))
                .isInstanceOf(DeviceOfflineException.class)
                .hasMessageStartingWith("JBL Flip 5 is not connected: The speaker did not answer");
        assertThat(launcher.starts).isEmpty();
    }

    @Test
    void anUnpairedSpeakerIsOffline() {
        start();

        assertThatThrownBy(() -> session.execute(PLAY))
                .isInstanceOf(DeviceOfflineException.class)
                .hasMessageContaining("Pair it again on the setup page");
    }

    @Test
    void refusesVideoAndNonHttpStreams() {
        bluez.known("AA:BB:CC:DD:EE:FF", "JBL Flip 5").paired(true).connected(true).uuids(BluetoothDeviceInfo.A2DP_SINK);
        start();
        await().atMost(WAIT).untilAsserted(() -> assertThat(session.state().status()).isEqualTo(DeviceStatus.CONNECTED));

        assertThatThrownBy(() -> session.execute(new Action.PlayMedia(URI.create("http://nas/f.mp4"), "video/mp4", "F", null)))
                .isInstanceOf(UnsupportedActionException.class).hasMessage("JBL Flip 5 plays audio only");
        assertThatThrownBy(() -> session.execute(new Action.PlayMedia(URI.create("file:///etc/passwd"), "audio/mpeg", "F", null)))
                .isInstanceOf(UnsupportedActionException.class).hasMessage("JBL Flip 5 plays http and https streams only");
        assertThat(launcher.starts).isEmpty();
    }

    @Test
    void pauseResumeVolumeMuteAndStop() {
        bluez.known("AA:BB:CC:DD:EE:FF", "JBL Flip 5").paired(true).connected(true).uuids(BluetoothDeviceInfo.A2DP_SINK);
        start();
        await().atMost(WAIT).untilAsserted(() -> assertThat(session.state().status()).isEqualTo(DeviceStatus.CONNECTED));
        session.execute(PLAY);
        await().atMost(WAIT).untilAsserted(() -> assertThat(session.state().nowPlaying()).isNotNull());

        session.execute(new Action.Pause());
        await().atMost(WAIT).untilAsserted(() -> {
            assertThat(session.state().nowPlaying().state()).isEqualTo(PlaybackState.PAUSED);
            assertThat(launcher.latest().paused()).isTrue();
        });

        session.execute(new Action.Resume());
        await().atMost(WAIT).untilAsserted(() -> assertThat(session.state().nowPlaying().state()).isEqualTo(PlaybackState.PLAYING));

        session.execute(new Action.SetVolume(30));
        assertThat(launcher.latest().volume()).isEqualTo(30.0);
        await().atMost(WAIT).untilAsserted(() -> assertThat(session.state().volumeLevel()).isEqualTo(30));

        session.execute(new Action.Mute(true));
        assertThat(launcher.latest().muted()).isTrue();
        await().atMost(WAIT).untilAsserted(() -> assertThat(session.state().muted()).isTrue());

        session.execute(new Action.Stop());
        await().atMost(WAIT).untilAsserted(() -> {
            assertThat(launcher.alive()).isZero();
            assertThat(session.state().nowPlaying()).isNull();
        });
        session.execute(new Action.Stop());
    }

    @Test
    void volumeIsRememberedForTheNextPlay() {
        bluez.known("AA:BB:CC:DD:EE:FF", "JBL Flip 5").paired(true).connected(true).uuids(BluetoothDeviceInfo.A2DP_SINK);
        start();
        await().atMost(WAIT).untilAsserted(() -> assertThat(session.state().status()).isEqualTo(DeviceStatus.CONNECTED));

        session.execute(new Action.SetVolume(70));
        session.execute(new Action.Mute(true));
        await().atMost(WAIT).untilAsserted(() -> assertThat(session.state().volumeLevel()).isEqualTo(70));

        session.execute(PLAY);

        assertThat(launcher.starts.getLast()).contains("--volume=70");
        assertThat(launcher.latest().commands().get(0)).isEqualTo(List.of("set_property", "mute", "true"));
    }

    @Test
    void pauseWithNothingPlayingFails() {
        bluez.known("AA:BB:CC:DD:EE:FF", "JBL Flip 5").paired(true).connected(true).uuids(BluetoothDeviceInfo.A2DP_SINK);
        start();
        await().atMost(WAIT).untilAsserted(() -> assertThat(session.state().status()).isEqualTo(DeviceStatus.CONNECTED));

        assertThatThrownBy(() -> session.execute(new Action.Pause()))
                .isInstanceOf(ActionFailedException.class).hasMessage("Nothing is playing on JBL Flip 5");
    }

    @Test
    void aNewPlayReplacesThePlayer() {
        bluez.known("AA:BB:CC:DD:EE:FF", "JBL Flip 5").paired(true).connected(true).uuids(BluetoothDeviceInfo.A2DP_SINK);
        start();
        await().atMost(WAIT).untilAsserted(() -> assertThat(session.state().status()).isEqualTo(DeviceStatus.CONNECTED));

        session.execute(PLAY);
        session.execute(new Action.PlayMedia(URI.create("http://127.0.0.1:9/music/other.mp3"), "audio/mpeg", "B", null));

        assertThat(launcher.alive()).isEqualTo(1);
    }

    @Test
    void aStreamThatFailsIsReported() {
        bluez.known("AA:BB:CC:DD:EE:FF", "JBL Flip 5").paired(true).connected(true).uuids(BluetoothDeviceInfo.A2DP_SINK);
        launcher.options = launcher.options.failingFor("broken");
        start();
        await().atMost(WAIT).untilAsserted(() -> assertThat(session.state().status()).isEqualTo(DeviceStatus.CONNECTED));

        assertThatThrownBy(() -> session.execute(new Action.PlayMedia(
                URI.create("http://127.0.0.1:9/broken.mp3?ApiKey=secret-key"), "audio/mpeg", "X", null)))
                .isInstanceOf(ActionFailedException.class)
                .hasMessage("JBL Flip 5 could not play the stream: the stream could not be loaded (loading failed)");
        await().atMost(WAIT).untilAsserted(() -> assertThat(launcher.alive()).isZero());
    }

    @Test
    void mpvMissingIsExplained() {
        bluez.known("AA:BB:CC:DD:EE:FF", "JBL Flip 5").paired(true).connected(true).uuids(BluetoothDeviceInfo.A2DP_SINK);
        launcher.startFailure = new MpvNotInstalledException("mpv", new IOException("error=2"));
        start();
        await().atMost(WAIT).untilAsserted(() -> assertThat(session.state().status()).isEqualTo(DeviceStatus.CONNECTED));

        assertThatThrownBy(() -> session.execute(PLAY))
                .isInstanceOf(ActionFailedException.class)
                .hasMessage(BluetoothSpeakerSession.MPV_MISSING)
                .hasMessageContaining("latest-bluetooth").hasMessageContaining("WITH_MPV=true");
    }

    @Test
    void noAudioOutputIsExplained() {
        bluez.known("AA:BB:CC:DD:EE:FF", "JBL Flip 5").paired(true).connected(true).uuids(BluetoothDeviceInfo.A2DP_SINK);
        launcher.options = FakeMpv.Options.defaults().withAudioDevices(
                "pulse/alsa_output.platform-bcm2835_audio.stereo-fallback=Built-in Audio");
        start();
        await().atMost(WAIT).untilAsserted(() -> assertThat(session.state().status()).isEqualTo(DeviceStatus.CONNECTED));

        assertThatThrownBy(() -> session.execute(PLAY))
                .isInstanceOf(ActionFailedException.class)
                .hasMessageStartingWith("JBL Flip 5: No audio output for AA:BB:CC:DD:EE:FF was found");
    }

    @Test
    void aManualAudioDeviceWins() {
        Device withAudio = new Device(device.id(), device.name(), device.kind(), device.host(),
                Map.of("bluetooth", new BluetoothSettings("AA:BB:CC:DD:EE:FF", FakeBluezClient.ADAPTER,
                        "alsa/bluealsa:DEV=AA:BB:CC:DD:EE:FF,PROFILE=a2dp").toMap()), device.lastSeen());
        device = withAudio;
        bluez.known("AA:BB:CC:DD:EE:FF", "JBL Flip 5").paired(true).connected(true).uuids(BluetoothDeviceInfo.A2DP_SINK);
        start();
        await().atMost(WAIT).untilAsserted(() -> assertThat(session.state().status()).isEqualTo(DeviceStatus.CONNECTED));

        session.execute(PLAY);

        assertThat(launcher.starts.getFirst()).contains("--audio-device=alsa/bluealsa:DEV=AA:BB:CC:DD:EE:FF,PROFILE=a2dp");
        assertThat(launcher.runs).isEmpty();
    }

    @Test
    void stopsPlaybackWhenTheSpeakerDisconnects() {
        bluez.known("AA:BB:CC:DD:EE:FF", "JBL Flip 5").paired(true).connected(true).uuids(BluetoothDeviceInfo.A2DP_SINK);
        start();
        await().atMost(WAIT).untilAsserted(() -> assertThat(session.state().status()).isEqualTo(DeviceStatus.CONNECTED));
        session.execute(PLAY);
        await().atMost(WAIT).untilAsserted(() -> assertThat(session.state().nowPlaying()).isNotNull());

        bluez.device("AA:BB:CC:DD:EE:FF").connected(false);

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            assertThat(launcher.alive()).isZero();
            assertThat(session.state().nowPlaying()).isNull();
        });
    }

    @Test
    void aTrackThatEndsClearsNowPlaying() {
        bluez.known("AA:BB:CC:DD:EE:FF", "JBL Flip 5").paired(true).connected(true).uuids(BluetoothDeviceInfo.A2DP_SINK);
        start();
        await().atMost(WAIT).untilAsserted(() -> assertThat(session.state().status()).isEqualTo(DeviceStatus.CONNECTED));
        session.execute(PLAY);
        await().atMost(WAIT).untilAsserted(() -> assertThat(session.state().nowPlaying()).isNotNull());

        launcher.latest().finishTrack();

        await().atMost(WAIT).untilAsserted(() -> assertThat(session.state().nowPlaying()).isNull());
    }

    @Test
    void titlesFallBackToMetadataNeverToTheUrl() {
        bluez.known("AA:BB:CC:DD:EE:FF", "JBL Flip 5").paired(true).connected(true).uuids(BluetoothDeviceInfo.A2DP_SINK);
        launcher.options = launcher.options.withMetadataTitle("Radio Bunny");
        start();
        await().atMost(WAIT).untilAsserted(() -> assertThat(session.state().status()).isEqualTo(DeviceStatus.CONNECTED));

        session.execute(new Action.PlayMedia(URI.create("http://127.0.0.1:9/stream?ApiKey=secret-key"), "audio/mpeg", null, null));

        await().atMost(WAIT).untilAsserted(() ->
                assertThat(session.state().nowPlaying().title()).isEqualTo("Radio Bunny"));

        session.execute(new Action.Stop());
        launcher.options = launcher.options.withMetadataTitle(null);
        session.execute(new Action.PlayMedia(URI.create("http://127.0.0.1:9/stream2?ApiKey=secret-key"), "audio/mpeg", null, null));
        await().atMost(WAIT).untilAsserted(() ->
                assertThat(session.state().nowPlaying().title()).isEqualTo("Unknown title"));
    }

    @Test
    void remoteKeysAreUnsupported() {
        bluez.known("AA:BB:CC:DD:EE:FF", "JBL Flip 5").paired(true).connected(true).uuids(BluetoothDeviceInfo.A2DP_SINK);
        start();
        await().atMost(WAIT).untilAsserted(() -> assertThat(session.state().status()).isEqualTo(DeviceStatus.CONNECTED));

        assertThatThrownBy(() -> session.execute(new Action.PressKey(RemoteKey.HOME))).isInstanceOf(UnsupportedActionException.class);
        assertThatThrownBy(() -> session.execute(new Action.OpenAppLink(URI.create("https://youtube.com/watch?v=x"))))
                .isInstanceOf(UnsupportedActionException.class);
    }

    @Test
    void closeStopsThePlayer() {
        bluez.known("AA:BB:CC:DD:EE:FF", "JBL Flip 5").paired(true).connected(true).uuids(BluetoothDeviceInfo.A2DP_SINK);
        start();
        await().atMost(WAIT).untilAsserted(() -> assertThat(session.state().status()).isEqualTo(DeviceStatus.CONNECTED));
        session.execute(PLAY);
        await().atMost(WAIT).untilAsserted(() -> assertThat(session.state().nowPlaying()).isNotNull());

        session.close();

        await().atMost(WAIT).untilAsserted(() -> assertThat(launcher.alive()).isZero());
    }
}
