package dev.andre.homecontrol.adapters.bluetooth;

import dev.andre.homecontrol.adapters.bluetooth.bluez.BluetoothDeviceInfo;
import dev.andre.homecontrol.adapters.bluetooth.bluez.FakeBluezClient;
import dev.andre.homecontrol.adapters.bluetooth.player.AudioDeviceResolver;
import dev.andre.homecontrol.adapters.bluetooth.player.FakeMpv;
import dev.andre.homecontrol.adapters.bluetooth.player.InProcessMpvLauncher;
import dev.andre.homecontrol.adapters.bluetooth.player.MpvNotInstalledException;
import dev.andre.homecontrol.adapters.bluetooth.player.MpvPlayer;
import dev.andre.homecontrol.adapters.support.SessionLoop;
import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.PlaybackState;
import dev.andre.homecontrol.core.RemoteKey;
import dev.andre.homecontrol.core.UnsupportedActionException;
import dev.andre.homecontrol.testsupport.RecordingStateListener;
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
import java.util.function.Consumer;

import static dev.andre.homecontrol.adapters.bluetooth.bluez.BluezFailure.BLUEZ_NOT_RUNNING;
import static dev.andre.homecontrol.adapters.bluetooth.bluez.BluezFailure.UNREACHABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class BluetoothSpeakerSessionTest {

    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final BluetoothTimings TIMINGS = new BluetoothTimings(Duration.ofMillis(100), Duration.ofMillis(100));
    private static final Action.PlayMedia PLAY = new Action.PlayMedia(
            URI.create("http://127.0.0.1:9/music/song.mp3?ApiKey=secret-key"), "audio/mpeg", "Bunny Song", "The Rabbits");

    @TempDir
    Path runtime;

    private final FakeBluezClient bluez = new FakeBluezClient();
    private final InProcessMpvLauncher launcher = new InProcessMpvLauncher();
    private final BluetoothProperties properties = BluetoothProperties.defaults()
            .withTimings(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(5),
                    Duration.ofSeconds(2));
    private final RecordingStateListener states = new RecordingStateListener();
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
        return start(props, states);
    }

    private BluetoothSpeakerSession start(BluetoothProperties props,
                                          Consumer<dev.andre.homecontrol.core.DeviceState> listener) {
        // Mirrors BluetoothSpeakerAdapter.connect()'s wiring, so a test can widen a timeout via withTimings(...).
        MpvPlayer player = new MpvPlayer(launcher, MpvPlayer.socketFor(runtime, device.id()),
                props.playerStartTimeout(), props.loadTimeout(),
                props.commandTimeout());
        AudioDeviceResolver resolver = new AudioDeviceResolver(launcher, props.audioDeviceTemplate(),
                props.playerStartTimeout());
        session = new BluetoothSpeakerSession(device, props, TIMINGS, bluez, player, resolver, listener);
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

        var video = new Action.PlayMedia(URI.create("http://nas/f.mp4"), "video/mp4", "F", null);
        assertThatThrownBy(() -> session.execute(video))
                .isInstanceOf(UnsupportedActionException.class).hasMessage("JBL Flip 5 plays audio only");
        var localFile = new Action.PlayMedia(URI.create("file:///etc/passwd"), "audio/mpeg", "F", null);
        assertThatThrownBy(() -> session.execute(localFile))
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
        // A status poll may read the new player first; what matters is that nothing changes it before the mute.
        assertThat(launcher.latest().commands()).filteredOn(command -> !command.getFirst().equals("get_property"))
                .first().isEqualTo(List.of("set_property", "mute", "true"));
    }

    @Test
    void pauseWithNothingPlayingFails() {
        bluez.known("AA:BB:CC:DD:EE:FF", "JBL Flip 5").paired(true).connected(true).uuids(BluetoothDeviceInfo.A2DP_SINK);
        start();
        await().atMost(WAIT).untilAsserted(() -> assertThat(session.state().status()).isEqualTo(DeviceStatus.CONNECTED));

        var pause = new Action.Pause();
        assertThatThrownBy(() -> session.execute(pause))
                .isInstanceOf(ActionFailedException.class).hasMessage("Nothing is playing on JBL Flip 5");
    }

    @Test
    void aNewPlayReplacesThePlayer() {
        bluez.known("AA:BB:CC:DD:EE:FF", "JBL Flip 5").paired(true).connected(true).uuids(BluetoothDeviceInfo.A2DP_SINK);
        start();
        // It timed out once under load without saying where; the player's own waits name themselves in their
        // messages, so this one does too.
        await("the speaker to connect").atMost(WAIT)
                .untilAsserted(() -> assertThat(session.state().status()).isEqualTo(DeviceStatus.CONNECTED));

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

        var brokenStream = new Action.PlayMedia(
                URI.create("http://127.0.0.1:9/broken.mp3?ApiKey=secret-key"), "audio/mpeg", "X", null);
        assertThatThrownBy(() -> session.execute(brokenStream))
                .isInstanceOf(ActionFailedException.class)
                .hasMessage("JBL Flip 5 could not play the stream: the stream could not be loaded (loading failed)");
        await().atMost(WAIT).untilAsserted(() -> assertThat(launcher.alive()).isZero());
    }

    @Test
    void aFailedStreamKeepsItsReasonWhenMpvIsSeenExitingFirst() {
        bluez.known("AA:BB:CC:DD:EE:FF", "JBL Flip 5").paired(true).connected(true).uuids(BluetoothDeviceInfo.A2DP_SINK);
        launcher.options = launcher.options.failingFor("broken");
        launcher.exitsBeforeItsLastEvents = true;
        start();
        await().atMost(WAIT).untilAsserted(() -> assertThat(session.state().status()).isEqualTo(DeviceStatus.CONNECTED));

        var brokenStream = new Action.PlayMedia(
                URI.create("http://127.0.0.1:9/broken.mp3?ApiKey=secret-key"), "audio/mpeg", "X", null);
        assertThatThrownBy(() -> session.execute(brokenStream))
                .isInstanceOf(ActionFailedException.class)
                .hasMessage("JBL Flip 5 could not play the stream: the stream could not be loaded (loading failed)");
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
    void everythingButPlaybackAndVolumeIsUnsupported() {
        bluez.known("AA:BB:CC:DD:EE:FF", "JBL Flip 5").paired(true).connected(true).uuids(BluetoothDeviceInfo.A2DP_SINK);
        start();
        await().atMost(WAIT).untilAsserted(() -> assertThat(session.state().status()).isEqualTo(DeviceStatus.CONNECTED));

        List<Action> refused = List.of(new Action.PressKey(RemoteKey.HOME),
                new Action.OpenAppLink(URI.create("https://youtube.com/watch?v=x")), new Action.SelectInput("HDMI_1"),
                new Action.JoinGroup("RINCON_1"), new Action.LeaveGroup(), new Action.CastLoad("CC1AD845", Map.of()),
                new Action.CastMessage("APP", "urn:x-cast:app", Map.of()));
        for (Action action : refused) {
            assertThatThrownBy(() -> session.execute(action))
                    .isInstanceOf(UnsupportedActionException.class)
                    .hasMessage("JBL Flip 5 is a Bluetooth speaker and cannot handle " + action.getClass().getSimpleName());
        }
    }

    @Test
    void aPlayerThatCrashesClearsNowPlaying() {
        bluez.known("AA:BB:CC:DD:EE:FF", "JBL Flip 5").paired(true).connected(true).uuids(BluetoothDeviceInfo.A2DP_SINK);
        start();
        await().atMost(WAIT).untilAsserted(() -> assertThat(session.state().status()).isEqualTo(DeviceStatus.CONNECTED));
        session.execute(PLAY);
        await().atMost(WAIT).untilAsserted(() -> assertThat(session.state().nowPlaying()).isNotNull());

        // The fake vanishes without an end-file, like a killed mpv.
        launcher.latest().close();

        await().atMost(WAIT).untilAsserted(() -> {
            assertThat(session.state().nowPlaying()).isNull();
            assertThat(session.state().status()).isEqualTo(DeviceStatus.CONNECTED);
        });

        session.execute(PLAY);
        await().atMost(WAIT).untilAsserted(() -> assertThat(session.state().nowPlaying()).isNotNull());
        assertThat(launcher.alive()).isEqualTo(1);
    }

    @Test
    void aSlowBluezDoesNotPileUpPolls() {
        bluez.known("AA:BB:CC:DD:EE:FF", "JBL Flip 5").paired(true).connected(true).uuids(BluetoothDeviceInfo.A2DP_SINK);
        bluez.delay("device", Duration.ofMillis(240));
        start(properties);

        // A 240 ms read against a 100 ms poll interval: a session that waits for each poll to finish before
        // scheduling the next reads BlueZ 3 times in the first second; one that piles polls up instead (racing the
        // next poll against the current read) reads 5+ times, so 4 tells the two apart with margin either way.
        await().pollDelay(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(bluez.reads()).isLessThanOrEqualTo(4));
    }

    @Test
    void concurrentPlaysLeaveOnePlayer() throws Exception {
        bluez.known("AA:BB:CC:DD:EE:FF", "JBL Flip 5").paired(true).connected(true).uuids(BluetoothDeviceInfo.A2DP_SINK);
        // A generous command timeout: five concurrent full play cycles (each stopping the previous
        // mpv and starting a new one) create real scheduling pressure: a tight IPC timeout under that
        // load throws IOException out of MpvPlayer.status(), which (correctly) stops a healthy player.
        start(properties.withTimings(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(5),
                Duration.ofSeconds(5), Duration.ofSeconds(5)));
        await().atMost(WAIT).untilAsserted(() -> assertThat(session.state().status()).isEqualTo(DeviceStatus.CONNECTED));

        List<Throwable> failures = new CopyOnWriteArrayList<>();
        List<Thread> threads = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Thread thread = Thread.ofVirtual().unstarted(() -> {
                try {
                    session.execute(PLAY);
                } catch (Throwable t) {
                    failures.add(t);
                }
            });
            threads.add(thread);
        }
        threads.forEach(Thread::start);
        for (Thread thread : threads) {
            thread.join();
        }

        assertThat(failures).isEmpty();
        await().atMost(WAIT).untilAsserted(() -> assertThat(launcher.alive()).isEqualTo(1));
    }

    @Test
    void aBluezOutageDuringPlaybackStopsTheMusic() {
        bluez.known("AA:BB:CC:DD:EE:FF", "JBL Flip 5").paired(true).connected(true).uuids(BluetoothDeviceInfo.A2DP_SINK);
        start();
        await().atMost(WAIT).untilAsserted(() -> assertThat(session.state().status()).isEqualTo(DeviceStatus.CONNECTED));
        session.execute(PLAY);
        await().atMost(WAIT).untilAsserted(() -> assertThat(session.state().nowPlaying()).isNotNull());

        bluez.unavailable(BLUEZ_NOT_RUNNING);

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            assertThat(launcher.alive()).isZero();
            assertThat(session.state().status()).isEqualTo(DeviceStatus.DISCONNECTED);
        });
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

    @Test
    void aFailingStateListenerDoesNotStopPolling() {
        bluez.known("AA:BB:CC:DD:EE:FF", "JBL Flip 5").paired(true).connected(true).uuids(BluetoothDeviceInfo.A2DP_SINK);

        // The real listener publishes a Spring event synchronously, so any subscriber's failure lands here.
        start(properties, state -> {
            states.accept(state);
            throw new IllegalStateException("a subscriber failed");
        });

        await().atMost(WAIT).untilAsserted(() -> assertThat(session.state().status()).isEqualTo(DeviceStatus.CONNECTED));
    }

    @Test
    void aCommandThatRacesCloseStillAnswers() {
        bluez.known("AA:BB:CC:DD:EE:FF", "JBL Flip 5").paired(true).connected(true).uuids(BluetoothDeviceInfo.A2DP_SINK);
        SessionLoop loop = new SessionLoop("bluetooth-race");
        MpvPlayer player = new MpvPlayer(launcher, MpvPlayer.socketFor(runtime, device.id()),
                properties.playerStartTimeout(), properties.loadTimeout(), properties.commandTimeout());
        AudioDeviceResolver resolver = new AudioDeviceResolver(launcher, properties.audioDeviceTemplate(),
                properties.playerStartTimeout());
        session = new BluetoothSpeakerSession(device, properties, TIMINGS, bluez, player, resolver, states, loop);
        session.start();
        await().atMost(WAIT).untilAsserted(() -> assertThat(session.state().status()).isEqualTo(DeviceStatus.CONNECTED));

        // What close() does first: a command already past its closed check still asks for a poll afterwards.
        loop.close();

        assertThatCode(() -> session.execute(new Action.SetVolume(30))).doesNotThrowAnyException();
    }
}
