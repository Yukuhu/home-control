package dev.andre.homecontrol.adapters.bluetooth.player;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/** Which mpv --audio-device plays on a given speaker. Resolved per play: the sink exists only while the speaker is connected. */
public class AudioDeviceResolver {

    private final MpvLauncher launcher;
    private final String template;
    private final Duration timeout;

    public AudioDeviceResolver(MpvLauncher launcher, String template, Duration timeout) {
        this.launcher = launcher;
        this.template = template == null ? "" : template.strip();
        this.timeout = timeout;
    }

    public String resolve(String mac, String manualDevice) throws AudioDeviceNotFoundException, IOException {
        if (manualDevice != null && !manualDevice.isBlank()) {
            return manualDevice.strip();
        }
        if (!template.isEmpty()) {
            return template.replace("{mac_}", mac.replace(':', '_')).replace("{mac}", mac);
        }
        List<AudioDevice> devices = AudioDevices.parse(launcher.run(List.of("--no-config", "--audio-device=help"), timeout));
        return AudioDevices.forMac(devices, mac).map(AudioDevice::id).orElseThrow(() -> new AudioDeviceNotFoundException(
                "No audio output for " + mac + " was found. Make sure the speaker is connected and the host's "
                        + "PipeWire or PulseAudio lists it, or set its audio output on the setup page."));
    }
}
