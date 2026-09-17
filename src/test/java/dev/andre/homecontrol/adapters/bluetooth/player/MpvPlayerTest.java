package dev.andre.homecontrol.adapters.bluetooth.player;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;

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
        assertThat(a).isEqualTo(b);
        assertThat(a).isNotEqualTo(c);
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
