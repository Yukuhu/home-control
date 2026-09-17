package dev.andre.homecontrol.adapters.bluetooth.player;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/** The mpv arguments. The stream URL is never an argument: it goes over IPC, because argv is visible in ps. */
public final class MpvCommandLine {

    private static final Pattern AUDIO_DEVICE = Pattern.compile("[A-Za-z0-9_.:/=,@+-]{1,200}");

    private MpvCommandLine() {
    }

    public static List<String> arguments(Path socket, String audioDevice, int volume) {
        if (!validAudioDevice(audioDevice)) {
            throw new IllegalArgumentException("Not a usable audio device id");
        }
        return List.of(
                "--no-config",
                "--idle=once",
                "--no-video",
                "--input-terminal=no",
                "--msg-level=all=error",
                "--ytdl=no",
                "--load-scripts=no",
                "--input-default-bindings=no",
                "--audio-client-name=home-control",
                "--volume-max=100",
                "--volume=" + Math.clamp(volume, 0, 100),
                "--network-timeout=15",
                "--audio-device=" + audioDevice,
                "--input-ipc-server=" + socket);
    }

    public static boolean validAudioDevice(String audioDevice) {
        return audioDevice != null && AUDIO_DEVICE.matcher(audioDevice).matches();
    }
}
