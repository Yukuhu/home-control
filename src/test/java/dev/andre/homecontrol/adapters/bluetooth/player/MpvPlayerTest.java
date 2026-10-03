package dev.andre.homecontrol.adapters.bluetooth.player;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class MpvPlayerTest {

    @TempDir
    Path dir;

    private final InProcessMpvLauncher launcher = new InProcessMpvLauncher();
    private MpvPlayer player;

    @BeforeEach
    void setUp() {
        player = new MpvPlayer(launcher, MpvPlayer.socketFor(dir, "bluetooth-aa-bb-cc-dd-ee-ff"),
                Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(1));
    }

    @AfterEach
    void tearDown() {
        player.close();
        launcher.close();
    }

    @Test
    void socketPathsAreShortAndStable() {
        Path a = MpvPlayer.socketFor(Path.of("/tmp/x"), "bluetooth-aa-bb-cc-dd-ee-ff");
        Path b = MpvPlayer.socketFor(Path.of("/tmp/x"), "bluetooth-aa-bb-cc-dd-ee-ff");
        Path c = MpvPlayer.socketFor(Path.of("/tmp/x"), "bluetooth-11-22-33-44-55-66");
        assertThat(a.toString()).matches("/tmp/x/mpv-[0-9a-f]{12}\\.sock");
        assertThat(a).isEqualTo(b).isNotEqualTo(c);
    }

    @Test
    void refusesToPlayWhenTheRuntimeDirIsNotPrivate() throws Exception {
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxrwxrwx"));

        assertThatThrownBy(() -> player.play(URI.create("http://nas/a.mp3"), "pulse/x", 40, false))
                .isInstanceOf(dev.andre.homecontrol.core.ActionFailedException.class)
                .hasMessageContaining(dir.toString());
        assertThat(launcher.starts).isEmpty();
    }

    @Test
    void aClosedPlayerStartsNoMpv() {
        player.close();

        assertThatThrownBy(() -> player.play(URI.create("http://nas/a.mp3"), "pulse/x", 40, false))
                .isInstanceOf(IOException.class).hasMessage("the player is closed");
        assertThat(launcher.starts).isEmpty();
    }

    @Test
    void playsAfterTheFileLoaded() throws Exception {
        player.play(URI.create("http://nas/a.mp3?ApiKey=secret"), "pulse/bluez_output.AA_BB_CC_DD_EE_FF.1", 40, false);

        assertThat(player.active()).isTrue();
        assertThat(launcher.starts).hasSize(1);
        assertThat(launcher.starts.getFirst()).isEqualTo(MpvCommandLine.arguments(
                MpvPlayer.socketFor(dir, "bluetooth-aa-bb-cc-dd-ee-ff"), "pulse/bluez_output.AA_BB_CC_DD_EE_FF.1", 40));
        assertThat(launcher.starts.getFirst()).noneMatch(argument -> argument.contains("nas"));
        assertThat(launcher.latest().commands()).contains(java.util.List.of("loadfile", "http://nas/a.mp3?ApiKey=secret", "replace"));
        assertThat(Files.getPosixFilePermissions(dir)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
    }

    @Test
    void mutesBeforeLoadingWhenAsked() throws Exception {
        player.play(URI.create("http://nas/a.mp3"), "pulse/x", 40, true);

        assertThat(launcher.latest().commands().get(0)).isEqualTo(java.util.List.of("set_property", "mute", "true"));
    }

    @Test
    void reportsStatus() throws Exception {
        player.play(URI.create("http://nas/a.mp3"), "pulse/x", 40, false);

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(player.status()).isPresent());
        PlayerStatus status = player.status().orElseThrow();
        assertThat(status.paused()).isFalse();
        assertThat(status.buffering()).isFalse();
        assertThat(status.positionSeconds()).isGreaterThanOrEqualTo(0);
        assertThat(status.durationSeconds()).isEqualTo(187.0);
        assertThat(status.volume()).isEqualTo(40);
        assertThat(status.muted()).isFalse();
        assertThat(status.metadataTitle()).isNull();

        player.stop();
        launcher.options = FakeMpv.Options.defaults().withMetadataTitle("Meta Song").withDuration(0);
        player.play(URI.create("http://nas/b.mp3"), "pulse/x", 40, false);
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(player.status()).isPresent());
        PlayerStatus second = player.status().orElseThrow();
        assertThat(second.metadataTitle()).isEqualTo("Meta Song");
        assertThat(second.durationSeconds()).isNull();
    }

    @Test
    void toleratesOneFailedStatusPollBeforeStopping() throws Exception {
        MpvPlayer quick = new MpvPlayer(launcher, MpvPlayer.socketFor(dir, "quick"), Duration.ofSeconds(2),
                Duration.ofSeconds(2), Duration.ofMillis(200));
        quick.play(URI.create("http://nas/a.mp3"), "pulse/x", 40, false);
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(quick.status()).isPresent());

        launcher.latest().dropNextGetProperty(1);
        assertThat(quick.status()).isEmpty();
        assertThat(quick.active()).isTrue();

        assertThat(quick.status()).isPresent();
        assertThat(quick.active()).isTrue();
        quick.close();
    }

    @Test
    void aSecondConsecutiveFailedStatusPollStopsThePlayer() throws Exception {
        MpvPlayer quick = new MpvPlayer(launcher, MpvPlayer.socketFor(dir, "quick2"), Duration.ofSeconds(2),
                Duration.ofSeconds(2), Duration.ofMillis(200));
        quick.play(URI.create("http://nas/a.mp3"), "pulse/x", 40, false);
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(quick.status()).isPresent());

        launcher.latest().dropNextGetProperty(2);
        assertThat(quick.status()).isEmpty();
        assertThat(quick.active()).isTrue();
        assertThat(quick.status()).isEmpty();
        assertThat(quick.active()).isFalse();
        quick.close();
    }

    @Test
    void pausesResumesAndSetsVolume() throws Exception {
        player.play(URI.create("http://nas/a.mp3"), "pulse/x", 40, false);

        player.pause(true);
        assertThat(launcher.latest().paused()).isTrue();
        assertThat(player.status().orElseThrow().paused()).isTrue();

        player.pause(false);
        assertThat(launcher.latest().paused()).isFalse();

        player.volume(25);
        assertThat(launcher.latest().volume()).isEqualTo(25.0);

        player.mute(true);
        assertThat(launcher.latest().muted()).isTrue();
    }

    @Test
    void aLoadFailureStopsThePlayer() {
        launcher.options = FakeMpv.Options.defaults().failingFor("broken");

        assertThatThrownBy(() -> player.play(URI.create("http://nas/broken.mp3?ApiKey=secret"), "pulse/x", 40, false))
                .isInstanceOf(MpvException.class).hasMessage("the stream could not be loaded (loading failed)");
        assertThat(player.active()).isFalse();
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(launcher.alive()).isZero());
    }

    @Test
    void anAcknowledgedLoadThatNeverFinishesStopsThePlayer() {
        launcher.beforeServing = FakeMpv::holdFileLoaded;
        player = new MpvPlayer(launcher, MpvPlayer.socketFor(dir, "load-timeout"), Duration.ofSeconds(2),
                Duration.ofMillis(200), Duration.ofSeconds(1));

        assertThatThrownBy(() -> player.play(URI.create("http://nas/a.mp3"), "pulse/x", 40, false))
                .isInstanceOf(IOException.class).hasMessageContaining("the stream did not start");

        assertStoppedAfterFailedPlay();
    }

    @Test
    void aRefusedLoadCommandStopsThePlayer() {
        launcher.beforeServing = fake -> fake.rejectLoadCommands("invalid parameter");

        assertThatThrownBy(() -> player.play(URI.create("http://nas/a.mp3"), "pulse/x", 40, false))
                .isInstanceOf(MpvException.class).hasMessageContaining("mpv refused loadfile");

        assertStoppedAfterFailedPlay();
    }

    @Test
    void anUnansweredLoadCommandStopsThePlayer() {
        launcher.beforeServing = FakeMpv::dropLoadReply;
        player = new MpvPlayer(launcher, MpvPlayer.socketFor(dir, "command-timeout"), Duration.ofSeconds(2),
                Duration.ofSeconds(2), Duration.ofMillis(200));

        assertThatThrownBy(() -> player.play(URI.create("http://nas/a.mp3"), "pulse/x", 40, false))
                .isInstanceOf(IOException.class).hasMessageContaining("mpv did not answer loadfile");

        assertStoppedAfterFailedPlay();
    }

    @Test
    void interruptingAnUnfinishedLoadStopsThePlayerAndPreservesTheInterrupt() throws Exception {
        launcher.beforeServing = FakeMpv::holdFileLoaded;
        player = new MpvPlayer(launcher, MpvPlayer.socketFor(dir, "interrupted-load"), Duration.ofSeconds(2),
                Duration.ofSeconds(30), Duration.ofSeconds(1));
        record InterruptedPlay(Exception failure, boolean interrupted) { }
        CompletableFuture<InterruptedPlay> result = new CompletableFuture<>();
        Thread playback = Thread.ofVirtual().start(() -> {
            try {
                player.play(URI.create("http://nas/a.mp3"), "pulse/x", 40, false);
                result.complete(new InterruptedPlay(null, Thread.currentThread().isInterrupted()));
            } catch (Exception failure) {
                result.complete(new InterruptedPlay(failure, Thread.currentThread().isInterrupted()));
            }
        });
        try {
            // Interrupt the file-loaded wait, after the loadfile command has been acknowledged.
            await().atMost(Duration.ofSeconds(3)).until(() -> playback.getState() == Thread.State.TIMED_WAITING
                    && Arrays.stream(playback.getStackTrace()).anyMatch(frame -> frame.getClassName().equals(MpvPlayer.class.getName()))
                    && Arrays.stream(playback.getStackTrace()).noneMatch(frame -> frame.getClassName().equals(MpvIpc.class.getName())));

            playback.interrupt();
            InterruptedPlay interrupted = result.get(3, TimeUnit.SECONDS);

            assertThat(interrupted.failure()).isInstanceOf(InterruptedIOException.class)
                    .hasMessage("interrupted while starting playback");
            assertThat(interrupted.interrupted()).isTrue();
            assertStoppedAfterFailedPlay();
        } finally {
            playback.interrupt();
            playback.join(Duration.ofSeconds(3));
        }
    }

    @Test
    void radioMetadataFallsBackToTheIcyTitleWhenTheTitleIsBlank() throws Exception {
        launcher.beforeServing = fake -> fake.metadata(Map.of("title", "   ", "ICY-TITLE", "  Radio Song  "));

        player.play(URI.create("http://nas/radio"), "pulse/x", 40, false);

        assertThat(player.status().orElseThrow().metadataTitle()).isEqualTo("Radio Song");
    }

    @Test
    void theTrackTitleTakesPrecedenceOverTheIcyTitle() throws Exception {
        launcher.beforeServing = fake -> fake.metadata(Map.of("TITLE", "  Track Song  ", "icy-title", "Radio Song"));

        player.play(URI.create("http://nas/radio"), "pulse/x", 40, false);

        assertThat(player.status().orElseThrow().metadataTitle()).isEqualTo("Track Song");
    }

    @Test
    void startupFailureIncludesMpvDiagnosticsWithoutStreamCredentials() throws Exception {
        Path binary = FakeMpvScript.create(dir.resolve("bin"), Map.of("FAKE_MPV_EXIT_AT_START",
                "2:Failed to open http://user:password@nas/a.mp3?ApiKey=private-token"));
        try (ProcessMpvLauncher processLauncher = new ProcessMpvLauncher(binary.toString());
             MpvPlayer failing = new MpvPlayer(processLauncher, MpvPlayer.socketFor(dir, "diagnostics"),
                     Duration.ofSeconds(15), Duration.ofSeconds(2), Duration.ofSeconds(1))) {

            assertThatThrownBy(() -> failing.play(URI.create("http://nas/a.mp3?ApiKey=private-token"), "pulse/x", 40, false))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("mpv exited before opening its control socket")
                    .hasMessageContaining("Failed to open http://…@nas/a.mp3?…")
                    .hasMessageNotContaining("private-token")
                    .hasMessageNotContaining("password");
            assertThat(failing.active()).isFalse();
            assertThat(failing.status()).isEmpty();
        }
    }

    private void assertStoppedAfterFailedPlay() {
        assertThat(player.active()).isFalse();
        assertThat(player.status()).isEmpty();
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            assertThat(launcher.latest().hasQuit()).isTrue();
            assertThat(launcher.alive()).isZero();
        });
        assertThatThrownBy(() -> player.pause(true)).isInstanceOf(IOException.class).hasMessage("nothing is playing");
    }

    @Test
    void aSlowStartTimesOut() {
        launcher.startDelay = Duration.ofSeconds(5);
        MpvPlayer slow = new MpvPlayer(launcher, MpvPlayer.socketFor(dir, "slow"), Duration.ofMillis(500),
                Duration.ofSeconds(2), Duration.ofSeconds(1));

        assertThatThrownBy(() -> slow.play(URI.create("http://nas/a.mp3"), "pulse/x", 40, false))
                .isInstanceOf(java.io.IOException.class).hasMessageContaining("did not open its control socket");
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(launcher.alive()).isZero());
        slow.close();
    }

    @Test
    void aNewPlayReplacesTheOldProcess() throws Exception {
        player.play(URI.create("http://nas/a.mp3"), "pulse/x", 40, false);
        FakeMpv first = launcher.latest();
        player.play(URI.create("http://nas/b.mp3"), "pulse/x", 40, false);

        assertThat(launcher.starts).hasSize(2);
        assertThat(launcher.alive()).isEqualTo(1);
        assertThat(first.hasQuit()).isTrue();
    }

    @Test
    void stopEndsEverything() throws Exception {
        player.play(URI.create("http://nas/a.mp3"), "pulse/x", 40, false);
        Path socket = MpvPlayer.socketFor(dir, "bluetooth-aa-bb-cc-dd-ee-ff");

        player.stop();

        assertThat(player.active()).isFalse();
        assertThat(player.status()).isEmpty();
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(launcher.alive()).isZero());
        assertThat(Files.exists(socket)).isFalse();

        player.stop();

        assertThatThrownBy(() -> player.pause(true)).isInstanceOf(java.io.IOException.class).hasMessage("nothing is playing");
    }

    @Test
    void aTrackThatEndsLeavesNoPlayer() throws Exception {
        player.play(URI.create("http://nas/a.mp3"), "pulse/x", 40, false);

        launcher.latest().finishTrack();

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            assertThat(player.status()).isEmpty();
            assertThat(player.active()).isFalse();
        });
    }
}
