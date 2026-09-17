package dev.andre.homecontrol.adapters.bluetooth.player;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/** Starts mpv processes. {@code arguments} exclude the executable. */
public interface MpvLauncher extends AutoCloseable {

    MpvProcess start(List<String> arguments) throws IOException;

    /** Runs mpv to completion and returns stdout and stderr (redacted); throws {@link MpvNotInstalledException}
     *  when the executable cannot be started. */
    String run(List<String> arguments, Duration timeout) throws IOException;

    @Override
    void close();
}
