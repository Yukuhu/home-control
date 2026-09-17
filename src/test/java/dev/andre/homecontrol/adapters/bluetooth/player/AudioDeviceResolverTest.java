package dev.andre.homecontrol.adapters.bluetooth.player;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AudioDeviceResolverTest {

    private final InProcessMpvLauncher launcher = new InProcessMpvLauncher();

    @Test
    void aManualDeviceWins() throws Exception {
        AudioDeviceResolver resolver = new AudioDeviceResolver(launcher, "", Duration.ofSeconds(1));
        assertThat(resolver.resolve("AA:BB:CC:DD:EE:FF", "alsa/hw:1,0")).isEqualTo("alsa/hw:1,0");
        assertThat(launcher.runs).isEmpty();
    }

    @Test
    void aTemplateComesNext() throws Exception {
        AudioDeviceResolver resolver = new AudioDeviceResolver(launcher, "alsa/bluealsa:DEV={mac},PROFILE=a2dp", Duration.ofSeconds(1));
        assertThat(resolver.resolve("AA:BB:CC:DD:EE:FF", null)).isEqualTo("alsa/bluealsa:DEV=AA:BB:CC:DD:EE:FF,PROFILE=a2dp");

        AudioDeviceResolver underscoreResolver = new AudioDeviceResolver(launcher, "pulse/bluez_sink.{mac_}.a2dp_sink", Duration.ofSeconds(1));
        assertThat(underscoreResolver.resolve("AA:BB:CC:DD:EE:FF", "")).isEqualTo("pulse/bluez_sink.AA_BB_CC_DD_EE_FF.a2dp_sink");
        assertThat(launcher.runs).isEmpty();
    }

    @Test
    void otherwiseMpvIsAsked() throws Exception {
        launcher.options = FakeMpv.Options.defaults().withAudioDevices(
                "pulse/alsa_output.hdmi=HDMI", "pulse/bluez_output.AA_BB_CC_DD_EE_FF.1=JBL Flip 5");
        AudioDeviceResolver resolver = new AudioDeviceResolver(launcher, "", Duration.ofSeconds(1));

        assertThat(resolver.resolve("AA:BB:CC:DD:EE:FF", "")).isEqualTo("pulse/bluez_output.AA_BB_CC_DD_EE_FF.1");
        assertThat(launcher.runs).containsExactly(java.util.List.of("--no-config", "--audio-device=help"));
    }

    @Test
    void nothingFoundIsExplained() {
        launcher.options = FakeMpv.Options.defaults().withAudioDevices("pulse/alsa_output.hdmi=HDMI");
        AudioDeviceResolver resolver = new AudioDeviceResolver(launcher, "", Duration.ofSeconds(1));

        assertThatThrownBy(() -> resolver.resolve("AA:BB:CC:DD:EE:FF", null))
                .isInstanceOf(AudioDeviceNotFoundException.class)
                .hasMessageContaining("AA:BB:CC:DD:EE:FF").hasMessageContaining("PipeWire or PulseAudio")
                .hasMessageContaining("setup page");
    }

    @Test
    void mpvMissingPropagates() {
        launcher.startFailure = new MpvNotInstalledException("mpv", new IOException("error=2"));
        AudioDeviceResolver resolver = new AudioDeviceResolver(launcher, "", Duration.ofSeconds(1));

        assertThatThrownBy(() -> resolver.resolve("AA:BB:CC:DD:EE:FF", null)).isInstanceOf(MpvNotInstalledException.class);
    }
}
