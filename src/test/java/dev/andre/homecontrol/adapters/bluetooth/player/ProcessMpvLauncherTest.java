package dev.andre.homecontrol.adapters.bluetooth.player;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class ProcessMpvLauncherTest {

    @TempDir
    Path dir;

    private final List<ProcessMpvLauncher> launchers = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        launchers.forEach(ProcessMpvLauncher::close);
    }

    private ProcessMpvLauncher launcher(Map<String, String> env) throws IOException {
        Path mpv = FakeMpvScript.create(dir.resolve("bin"), env);
        ProcessMpvLauncher launcher = new ProcessMpvLauncher(mpv.toString());
        launchers.add(launcher);
        return launcher;
    }

    private static MpvIpc.EventListener noopListener() {
        return new MpvIpc.EventListener() {
            @Override
            public void onEvent(JsonNode event) {
            }

            @Override
            public void onClosed() {
            }
        };
    }

    @Test
    void startsControlsAndQuits() throws Exception {
        Path log = dir.resolve("log.jsonl");
        ProcessMpvLauncher launcher = launcher(Map.of("FAKE_MPV_LOG", log.toString()));
        Path socket = dir.resolve("mpv-1.sock");

        MpvProcess process = launcher.start(MpvCommandLine.arguments(socket, "pulse/x", 30));
        MpvIpc ipc = MpvIpc.connect(socket, Duration.ofSeconds(15), process::alive, noopListener());
        try {
            assertThat(ipc.command(Duration.ofSeconds(2), "get_property", "volume").asDouble(-1)).isEqualTo(30.0);
            try {
                ipc.command(Duration.ofSeconds(2), "quit");
            } catch (IOException _) {
                // the socket may close before the reply arrives
            }
            assertThat(process.onExit().get(10, TimeUnit.SECONDS)).isZero();
        } finally {
            ipc.close();
        }

        List<JsonNode> lines = FakeMpvScript.log(log);
        JsonNode start = lines.stream().filter(line -> "start".equals(line.path("type").asString(""))).findFirst().orElseThrow();
        assertThat(start.path("pid").asLong(-1)).isEqualTo(process.pid());
        List<String> args = new java.util.ArrayList<>();
        start.path("args").forEach(a -> args.add(a.asString("")));
        assertThat(args).containsExactlyElementsOf(MpvCommandLine.arguments(socket, "pulse/x", 30));
    }

    @Test
    void terminateKillsAProcessThatIgnoresTerm() throws Exception {
        ProcessMpvLauncher launcher = launcher(Map.of("FAKE_MPV_IGNORE_TERM", "1"));
        Path socket = dir.resolve("mpv-2.sock");

        MpvProcess process = launcher.start(MpvCommandLine.arguments(socket, "pulse/x", 30));
        await().atMost(Duration.ofSeconds(15)).until(() -> Files.exists(socket));

        process.terminate(Duration.ofMillis(500));

        assertThat(process.alive()).isFalse();
    }

    @Test
    void aMissingBinaryIsNotInstalled() {
        ProcessMpvLauncher launcher = new ProcessMpvLauncher(dir.resolve("nope/mpv").toString());
        launchers.add(launcher);

        assertThatThrownBy(() -> launcher.start(List.of()))
                .isInstanceOf(MpvNotInstalledException.class)
                .hasMessageContaining("mpv was not found at");
        assertThatThrownBy(() -> launcher.run(List.of("--version"), Duration.ofSeconds(1)))
                .isInstanceOf(MpvNotInstalledException.class)
                .hasMessageContaining("mpv was not found at");
    }

    @Test
    void stderrIsKeptRedacted() throws Exception {
        ProcessMpvLauncher launcher = launcher(Map.of("FAKE_MPV_EXIT_AT_START", "2:Failed to open http://h/a.mp3?ApiKey=secret"));
        Path socket = dir.resolve("mpv-3.sock");

        MpvProcess process = launcher.start(MpvCommandLine.arguments(socket, "pulse/x", 30));

        assertThat(process.onExit().get(15, TimeUnit.SECONDS)).isEqualTo(2);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(process.recentErrors()).contains("Failed to open http://h/a.mp3?…");
            assertThat(process.recentErrors()).doesNotContain("secret");
        });
    }

    @Test
    void runCapturesOutput() throws Exception {
        ProcessMpvLauncher launcher = launcher(Map.of());
        assertThat(launcher.run(List.of("--no-config", "--version"), Duration.ofSeconds(15))).startsWith("mpv v0.41.0-fake");

        ProcessMpvLauncher devicesLauncher = launcher(Map.of("FAKE_MPV_DEVICES", "pulse/bluez_output.AA_BB_CC_DD_EE_FF.1=JBL Flip 5"));
        assertThat(devicesLauncher.run(List.of("--no-config", "--audio-device=help"), Duration.ofSeconds(15)))
                .contains("'pulse/bluez_output.AA_BB_CC_DD_EE_FF.1' (JBL Flip 5)");
    }

    @Test
    void runTimesOut() throws Exception {
        Path bin = dir.resolve("bin2");
        Files.createDirectories(bin);
        Path script = bin.resolve("mpv");
        Files.writeString(script, "#!/bin/sh\nexec sleep 30\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        ProcessMpvLauncher launcher = new ProcessMpvLauncher(script.toString());
        launchers.add(launcher);

        assertThatThrownBy(() -> launcher.run(List.of(), Duration.ofMillis(500)))
                .isInstanceOf(IOException.class).hasMessage("mpv did not finish within 500 ms");
    }

    @Test
    void closeTerminatesEveryProcess() throws Exception {
        ProcessMpvLauncher launcher = launcher(Map.of());
        MpvProcess a = launcher.start(MpvCommandLine.arguments(dir.resolve("mpv-a.sock"), "pulse/x", 30));
        MpvProcess b = launcher.start(MpvCommandLine.arguments(dir.resolve("mpv-b.sock"), "pulse/x", 30));
        await().atMost(Duration.ofSeconds(15)).until(() -> Files.exists(dir.resolve("mpv-a.sock")) && Files.exists(dir.resolve("mpv-b.sock")));

        launcher.close();

        assertThat(a.alive()).isFalse();
        assertThat(b.alive()).isFalse();
    }
}
