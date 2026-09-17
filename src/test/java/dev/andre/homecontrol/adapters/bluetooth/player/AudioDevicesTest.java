package dev.andre.homecontrol.adapters.bluetooth.player;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AudioDevicesTest {

    private static final String PIPEWIRE_HOST = """
            List of detected audio devices:
              'auto' (Autoselect device)
              'pipewire' (Default (pipewire))
              'pipewire/alsa_output.platform-bcm2835_audio.stereo-fallback' (Built-in Audio Stereo)
              'pipewire/bluez_output.AA_BB_CC_DD_EE_FF.1' (JBL Flip 5)
              'pulse/alsa_output.platform-bcm2835_audio.stereo-fallback' (Built-in Audio Stereo)
              'pulse/bluez_output.AA_BB_CC_DD_EE_FF.1' (JBL Flip 5)
              'alsa' (Default (alsa))
            """;

    private static final String NO_SERVER = """
            List of detected audio devices:
              'auto' (Autoselect device)
              'alsa' (Default (alsa))
              'alsa/hw:0,0' (Onboard - Headphones)
            """;

    @Test
    void parsesEveryDevice() {
        List<AudioDevice> devices = AudioDevices.parse(PIPEWIRE_HOST);
        assertThat(devices).hasSize(7);
        assertThat(devices.get(0)).isEqualTo(new AudioDevice("auto", "Autoselect device"));
        assertThat(devices.get(1)).isEqualTo(new AudioDevice("pipewire", "Default (pipewire)"));
        assertThat(AudioDevices.parse("")).isEmpty();
        assertThat(AudioDevices.parse(null)).isEmpty();
    }

    @Test
    void findsTheSpeakerByMacPreferringPipeWire() {
        assertThat(AudioDevices.forMac(AudioDevices.parse(PIPEWIRE_HOST), "aa:bb:cc:dd:ee:ff").map(AudioDevice::id))
                .contains("pipewire/bluez_output.AA_BB_CC_DD_EE_FF.1");

        String withoutPipewireLines = PIPEWIRE_HOST.lines()
                .filter(line -> !line.contains("'pipewire/"))
                .reduce("", (a, b) -> a + b + "\n");
        assertThat(AudioDevices.forMac(AudioDevices.parse(withoutPipewireLines), "aa:bb:cc:dd:ee:ff").map(AudioDevice::id))
                .contains("pulse/bluez_output.AA_BB_CC_DD_EE_FF.1");

        String pulseSink = "List of detected audio devices:\n  'pulse/bluez_sink.AA_BB_CC_DD_EE_FF.a2dp_sink' (JBL Flip 5)\n";
        assertThat(AudioDevices.forMac(AudioDevices.parse(pulseSink), "aa:bb:cc:dd:ee:ff").map(AudioDevice::id))
                .contains("pulse/bluez_sink.AA_BB_CC_DD_EE_FF.a2dp_sink");

        String alsaId = "List of detected audio devices:\n  'alsa/bluealsa:DEV=AA:BB:CC:DD:EE:FF,PROFILE=a2dp' (JBL Flip 5)\n";
        assertThat(AudioDevices.forMac(AudioDevices.parse(alsaId), "aa:bb:cc:dd:ee:ff").map(AudioDevice::id))
                .contains("alsa/bluealsa:DEV=AA:BB:CC:DD:EE:FF,PROFILE=a2dp");

        assertThat(AudioDevices.forMac(AudioDevices.parse(PIPEWIRE_HOST), "11:22:33:44:55:66")).isEmpty();
    }

    @Test
    void soundServerOutputs() {
        assertThat(AudioDevices.soundServerOutputs(AudioDevices.parse(PIPEWIRE_HOST))).hasSize(4)
                .allMatch(device -> device.id().contains("/") && (device.id().startsWith("pipewire/") || device.id().startsWith("pulse/")));
        assertThat(AudioDevices.soundServerOutputs(AudioDevices.parse(NO_SERVER))).isEmpty();
    }
}
