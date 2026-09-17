package dev.andre.homecontrol.adapters.bluetooth.player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads {@code mpv --audio-device=help}: lines like {@code   'pulse/bluez_output.AA_BB_CC_DD_EE_FF.1' (JBL Flip 5)}. */
public final class AudioDevices {

    private static final Pattern LINE = Pattern.compile("^\\s*'([^']+)'\\s*\\((.*)\\)\\s*$");

    private AudioDevices() {
    }

    public static List<AudioDevice> parse(String helpOutput) {
        List<AudioDevice> devices = new ArrayList<>();
        if (helpOutput == null) {
            return devices;
        }
        for (String line : helpOutput.split("\\R")) {
            Matcher matcher = LINE.matcher(line);
            if (matcher.matches()) {
                devices.add(new AudioDevice(matcher.group(1), matcher.group(2)));
            }
        }
        return devices;
    }

    /** The speaker's output: its MAC appears in the sink name (PipeWire/PulseAudio with underscores, bluealsa with colons). */
    public static Optional<AudioDevice> forMac(List<AudioDevice> devices, String mac) {
        String colons = mac.toUpperCase(Locale.ROOT);
        String underscores = colons.replace(':', '_');
        return devices.stream()
                .filter(device -> {
                    String id = device.id().toUpperCase(Locale.ROOT);
                    return id.contains(underscores) || id.contains(colons);
                })
                .min(Comparator.comparingInt(AudioDevices::rank));
    }

    /** Outputs of a reachable PipeWire or PulseAudio server; empty when mpv sees no sound server. */
    public static List<AudioDevice> soundServerOutputs(List<AudioDevice> devices) {
        return devices.stream().filter(device -> device.id().startsWith("pipewire/") || device.id().startsWith("pulse/")).toList();
    }

    private static int rank(AudioDevice device) {
        if (device.id().startsWith("pipewire/")) {
            return 0;
        }
        if (device.id().startsWith("pulse/")) {
            return 1;
        }
        return device.id().startsWith("alsa/") ? 2 : 3;
    }
}
