package dev.andre.homecontrol.adapters.bluetooth.player;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MpvCommandLineTest {

    @Test
    void buildsTheNormativeArguments() {
        assertThat(MpvCommandLine.arguments(Path.of("/tmp/hc/mpv-1.sock"), "pulse/bluez_output.AA_BB_CC_DD_EE_FF.1", 35))
                .containsExactly("--no-config", "--idle=once", "--no-video", "--input-terminal=no", "--msg-level=all=error",
                        "--ytdl=no", "--load-scripts=no", "--input-default-bindings=no", "--audio-client-name=home-control",
                        "--volume-max=100", "--volume=35", "--network-timeout=15",
                        "--audio-device=pulse/bluez_output.AA_BB_CC_DD_EE_FF.1", "--input-ipc-server=/tmp/hc/mpv-1.sock");
    }

    @Test
    void clampsTheVolume() {
        assertThat(MpvCommandLine.arguments(Path.of("/tmp/x.sock"), "pulse/x", -5)).contains("--volume=0");
        assertThat(MpvCommandLine.arguments(Path.of("/tmp/x.sock"), "pulse/x", 150)).contains("--volume=100");
    }

    @Test
    void refusesUnsafeAudioDevices() {
        assertThatThrownBy(() -> MpvCommandLine.arguments(Path.of("/tmp/x.sock"), "pulse/x --script=/tmp/evil.lua", 50))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MpvCommandLine.arguments(Path.of("/tmp/x.sock"), "a\nb", 50))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MpvCommandLine.arguments(Path.of("/tmp/x.sock"), "", 50))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MpvCommandLine.arguments(Path.of("/tmp/x.sock"), null, 50))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(MpvCommandLine.validAudioDevice("alsa/bluealsa:DEV=AA:BB:CC:DD:EE:FF,PROFILE=a2dp")).isTrue();
    }
}
