package dev.andre.homecontrol.adapters.bluetooth.player;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/** One running mpv process. */
public interface MpvProcess {

    long pid();

    boolean alive();

    CompletableFuture<Integer> onExit();

    /** The last stderr lines, redacted, joined with {@code " | "}. */
    String recentErrors();

    /** SIGTERM now, SIGKILL when still alive after {@code grace}; returns when the process is gone or after two more seconds. */
    void terminate(Duration grace);
}
