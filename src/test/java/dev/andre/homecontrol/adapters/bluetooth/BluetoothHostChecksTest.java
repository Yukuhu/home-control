package dev.andre.homecontrol.adapters.bluetooth;

import dev.andre.homecontrol.adapters.bluetooth.bluez.FakeBluezClient;
import dev.andre.homecontrol.adapters.bluetooth.player.FakeMpv;
import dev.andre.homecontrol.adapters.bluetooth.player.InProcessMpvLauncher;
import dev.andre.homecontrol.adapters.bluetooth.player.MpvNotInstalledException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static dev.andre.homecontrol.adapters.bluetooth.bluez.BluezFailure.BLUEZ_NOT_RUNNING;
import static org.assertj.core.api.Assertions.assertThat;

class BluetoothHostChecksTest {

    private final FakeBluezClient bluez = new FakeBluezClient();
    private final InProcessMpvLauncher launcher = new InProcessMpvLauncher();
    private final Map<String, String> env = new java.util.HashMap<>();
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-16T10:00:00Z"));
    private final Clock clock = new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };

    @TempDir
    Path temp;
    private Path socket;
    private BluetoothProperties properties;

    @BeforeEach
    void setUp() throws Exception {
        socket = temp.resolve("bus.sock");
        Files.createFile(socket);
        properties = BluetoothProperties.defaults().withDbusAddress("unix:path=" + socket);
        launcher.options = FakeMpv.Options.defaults().withAudioDevices(
                "pulse/alsa_output.platform-bcm2835_audio.stereo-fallback=Built-in Audio",
                "pulse/bluez_output.AA_BB_CC_DD_EE_FF.1=JBL Flip 5");
    }

    @AfterEach
    void tearDown() {
        launcher.close();
    }

    private BluetoothHostChecks checks() {
        return new BluetoothHostChecks(properties, bluez, launcher, clock, env::get);
    }

    private Map<String, HostCheck> byId(List<HostCheck> checks) {
        return checks.stream().collect(java.util.stream.Collectors.toMap(HostCheck::id, c -> c));
    }

    @Test
    void allGood() {
        List<HostCheck> results = checks().results();
        assertThat(results).extracting(HostCheck::id).containsExactly("dbus-socket", "bluez", "adapter", "mpv", "audio-output");
        assertThat(results).allMatch(HostCheck::ok);
        Map<String, HostCheck> byId = byId(results);
        assertThat(byId.get("dbus-socket").detail()).contains("Found " + socket);
        assertThat(byId.get("bluez").detail()).isEqualTo("BlueZ answered on the system bus");
        assertThat(byId.get("adapter").detail()).isEqualTo("hci0 (00:1A:7D:DA:71:13)");
        assertThat(byId.get("mpv").detail()).isEqualTo("mpv v0.41.0-fake");
        assertThat(byId.get("audio-output").detail()).isEqualTo("PipeWire or PulseAudio reachable (2 outputs)");
    }

    @Test
    void aMissingSocketBlocksTheRest() throws Exception {
        Files.delete(socket);
        Map<String, HostCheck> byId = byId(checks().results());
        assertThat(byId.get("dbus-socket").ok()).isFalse();
        assertThat(byId.get("dbus-socket").detail()).contains("Mount /run/dbus");
        assertThat(byId.get("bluez").ok()).isFalse();
        assertThat(byId.get("bluez").detail()).isEqualTo("Needs the D-Bus socket first");
        assertThat(byId.get("adapter").ok()).isFalse();
        assertThat(byId.get("adapter").detail()).isEqualTo("Needs BlueZ first");
        assertThat(bluez.reads()).isEqualTo(0);
    }

    @Test
    void bluezNotRunning() {
        bluez.unavailable(BLUEZ_NOT_RUNNING);
        Map<String, HostCheck> byId = byId(checks().results());
        assertThat(byId.get("bluez").ok()).isFalse();
        assertThat(byId.get("bluez").detail()).contains("systemctl enable --now bluetooth");
        assertThat(byId.get("adapter").detail()).isEqualTo("Needs BlueZ first");
    }

    @Test
    void noAdapter() {
        bluez.noAdapters();
        Map<String, HostCheck> byId = byId(checks().results());
        assertThat(byId.get("adapter").ok()).isFalse();
        assertThat(byId.get("adapter").detail()).contains("rfkill unblock bluetooth");
    }

    @Test
    void aConfiguredAdapterIsMissing() {
        properties = properties.withAdapter("hci3");
        Map<String, HostCheck> byId = byId(checks().results());
        assertThat(byId.get("adapter").detail()).isEqualTo("Adapter hci3 not found; the host has hci0 (00:1A:7D:DA:71:13)");
    }

    @Test
    void aPoweredOffAdapter() {
        bluez.adapterPowered(false);
        Map<String, HostCheck> byId = byId(checks().results());
        assertThat(byId.get("adapter").ok()).isFalse();
        assertThat(byId.get("adapter").detail()).contains("powered off").contains("rfkill unblock bluetooth");
    }

    @Test
    void otherTransportsSkipTheFileCheck() {
        properties = properties.withDbusAddress("tcp:host=127.0.0.1,port=1");
        Map<String, HostCheck> byId = byId(checks().results());
        assertThat(byId.get("dbus-socket").ok()).isTrue();
        assertThat(byId.get("dbus-socket").detail()).isEqualTo("Using tcp:host=127.0.0.1,port=1");
    }

    @Test
    void mpvMissing() throws IOException {
        launcher.startFailure = new MpvNotInstalledException("mpv", new IOException("error=2"));
        Map<String, HostCheck> byId = byId(checks().results());
        assertThat(byId.get("mpv").ok()).isFalse();
        assertThat(byId.get("mpv").detail()).isEqualTo(BluetoothSpeakerSession.MPV_MISSING);
        assertThat(byId.get("audio-output").ok()).isFalse();
        assertThat(byId.get("audio-output").detail()).isEqualTo("Needs mpv first");
    }

    @Test
    void noSoundServer() {
        launcher.options = FakeMpv.Options.defaults();
        Map<String, HostCheck> byId = byId(checks().results());
        assertThat(byId.get("audio-output").ok()).isFalse();
        assertThat(byId.get("audio-output").detail()).contains("No PipeWire or PulseAudio server is reachable")
                .contains("PULSE_SERVER=unix:/run/pulse/native");

        env.put("PULSE_SERVER", "unix:/run/pulse/native");
        Map<String, HostCheck> withEnv = byId(checks().results());
        assertThat(withEnv.get("audio-output").detail())
                .contains("PULSE_SERVER is unix:/run/pulse/native, but nothing answers there.");
    }

    @Test
    void aTemplateSkipsTheServerCheck() {
        properties = properties.withAudioDeviceTemplate("alsa/bluealsa:DEV={mac},PROFILE=a2dp");
        Map<String, HostCheck> byId = byId(checks().results());
        assertThat(byId.get("audio-output").ok()).isTrue();
        assertThat(byId.get("audio-output").detail()).isEqualTo("Using the template alsa/bluealsa:DEV={mac},PROFILE=a2dp");
        assertThat(launcher.runs).containsExactly(List.of("--no-config", "--version"));
    }

    @Test
    void resultsAreCachedUntilInvalidated() {
        BluetoothHostChecks checks = checks();
        checks.results();
        checks.results();
        assertThat(bluez.reads()).isEqualTo(1);
        assertThat(launcher.runs).hasSize(2);

        now.set(now.get().plusSeconds(29));
        checks.results();
        assertThat(bluez.reads()).isEqualTo(1);
        assertThat(launcher.runs).hasSize(2);

        checks.invalidate();
        checks.results();
        assertThat(bluez.reads()).isEqualTo(2);
        assertThat(launcher.runs).hasSize(4);

        now.set(now.get().plusSeconds(31));
        checks.results();
        assertThat(bluez.reads()).isEqualTo(3);
        assertThat(launcher.runs).hasSize(6);
    }
}
