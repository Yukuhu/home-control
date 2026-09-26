package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.RemoteKey;
import dev.andre.homecontrol.core.playback.Route;
import dev.andre.homecontrol.core.playback.RouteExecutor;
import dev.andre.homecontrol.device.DeviceManager;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JellyfinRouteExecutor implements RouteExecutor {

    private static final Logger log = LoggerFactory.getLogger(JellyfinRouteExecutor.class);
    private static final long RETRY_NANOS = Duration.ofSeconds(2).toNanos();
    private static final String REMOTE_CONNECTION = "remote connection";
    private static final String PACKAGE = "org.jellyfin.androidtv";
    // Remote v2 package launch, also used by androidtvremote2's send_launch_app_command.
    private static final URI APP_LINK = URI.create("market://launch?id=" + PACKAGE);
    private final JellyfinSessions sessions;
    private final DeviceManager devices;
    private final Duration startupTimeout;

    public JellyfinRouteExecutor(JellyfinSessions sessions, DeviceManager devices, Duration startupTimeout) {
        if (startupTimeout.isNegative() || startupTimeout.isZero()) {
            throw new IllegalArgumentException("Jellyfin startup timeout must be positive");
        }
        this.sessions = sessions;
        this.devices = devices;
        this.startupTimeout = startupTimeout;
    }

    @Override
    public boolean executes(Route route) {
        return route instanceof Route.JellyfinSession || route instanceof Route.JellyfinApp;
    }

    @Override
    public void execute(Route route, Device device) {
        try {
            switch (route) {
                case Route.JellyfinApp(var itemId, var startPositionTicks) ->
                        sessions.playNow(prepare(device), itemId, startPositionTicks);
                case Route.JellyfinSession(var sessionId, var itemId, var startPositionTicks, _) -> {
                    String id = device.hasAdapter("androidtv") ? prepare(device) : sessionId;
                    sessions.playNow(id, itemId, startPositionTicks);
                }
                default -> throw new IllegalArgumentException("Not a Jellyfin route");
            }
        } catch (JellyfinException | IllegalArgumentException e) {
            throw new ActionFailedException("Jellyfin could not start playback on " + device.name() + " (" + e.getMessage() + ")");
        }
    }

    private String prepare(Device device) {
        long deadline = System.nanoTime() + startupTimeout.toNanos();
        StartupProgress progress = new StartupProgress();
        log.info("Preparing Jellyfin on {} (startup timeout {}s)", device.id(), startupTimeout.toSeconds());
        while (true) {
            checkInterrupted();
            DeviceState state = devices.state(device.id());
            logStateChange(device, state, progress);
            if (state.status() == DeviceStatus.UNPAIRED) {
                throw new DeviceOfflineException(device.name() + " must be paired again before Jellyfin can start");
            }
            if (System.nanoTime() >= deadline) {
                throw startupTimeout(device, state, progress);
            }
            Optional<String> sessionId = advanceStartup(device, state, deadline, progress);
            if (sessionId.isPresent()) return sessionId.get();
            pause(deadline);
        }
    }

    private static final class StartupProgress {
        private long nextCommand;
        private boolean connectedOnce;
        private String stage = REMOTE_CONNECTION;
        private DeviceState previous;
    }

    private static void logStateChange(Device device, DeviceState state, StartupProgress progress) {
        DeviceState previous = progress.previous;
        if (previous == null || previous.status() != state.status()
                || previous.powerOn() != state.powerOn()
                || !Objects.equals(previous.currentApp(), state.currentApp())) {
            log.info("Jellyfin startup on {}: status={}, power={}, app={}",
                    device.id(), state.status(), state.powerOn(), state.currentApp());
            progress.previous = state;
        }
    }

    private static RuntimeException startupTimeout(Device device, DeviceState state, StartupProgress progress) {
        log.warn("Jellyfin startup timed out on {} while waiting for {} (status={}, power={}, app={})",
                device.id(), progress.stage, state.status(), state.powerOn(), state.currentApp());
        if (!progress.connectedOnce) {
            return new DeviceOfflineException(device.name()
                    + " did not connect; check that the Shield is reachable and paired");
        }
        if (!state.powerOn()) return new ActionFailedException(device.name() + " did not wake up in time");
        return new ActionFailedException(notReady(device) + " (waiting for " + progress.stage + ")");
    }

    private Optional<String> advanceStartup(Device device, DeviceState state, long deadline, StartupProgress progress) {
        if (!state.connected()) {
            resetConnection(progress);
            return Optional.empty();
        }
        progress.connectedOnce = true;
        try {
            if (!state.powerOn()) {
                wake(device, progress);
            } else if (!PACKAGE.equals(state.currentApp())) {
                launchApp(device, progress);
            } else {
                return readySession(device, deadline, progress);
            }
        } catch (DeviceOfflineException _) {
            // Only wake/launch are retried. PlayNow remains outside this loop and is sent once.
            log.info("Remote connection unavailable during Jellyfin startup on {}; retrying", device.id());
            resetConnection(progress);
        }
        return Optional.empty();
    }

    private static void resetConnection(StartupProgress progress) {
        // A successful socket write is not a launch acknowledgement. Retry after reconnect.
        progress.stage = REMOTE_CONNECTION;
        progress.nextCommand = 0;
    }

    private void wake(Device device, StartupProgress progress) {
        progress.stage = "wake confirmation";
        if (System.nanoTime() >= progress.nextCommand) {
            log.info("Sending WAKEUP for Jellyfin on {}", device.id());
            devices.execute(device.id(), new Action.PressKey(RemoteKey.WAKEUP));
            progress.nextCommand = System.nanoTime() + RETRY_NANOS;
        }
    }

    private void launchApp(Device device, StartupProgress progress) {
        // A wake confirmation permits the first launch immediately.
        if (progress.stage.equals("wake confirmation")) progress.nextCommand = 0;
        progress.stage = "Jellyfin foreground app";
        if (System.nanoTime() >= progress.nextCommand) {
            log.info("Sending Jellyfin app launch on {} via {}", device.id(), APP_LINK);
            devices.execute(device.id(), new Action.OpenAppLink(APP_LINK));
            progress.nextCommand = System.nanoTime() + RETRY_NANOS;
        }
    }

    private Optional<String> readySession(Device device, long deadline, StartupProgress progress) {
        progress.stage = "controllable Jellyfin session";
        Optional<JellyfinSession> session = findSession(device, deadline, notReady(device));
        checkInterrupted();
        if (System.nanoTime() >= deadline) throw new ActionFailedException(notReady(device));
        if (session.isPresent()) {
            log.info("Jellyfin is ready on {}; sending playback to the refreshed session", device.id());
        }
        return session.map(JellyfinSession::id);
    }

    private static String notReady(Device device) {
        return "Jellyfin did not become ready on " + device.name()
                + "; check that the app is installed, sign in on the TV, and link it under Setup → Jellyfin apps if needed";
    }

    /** A stalled HTTP body or DNS lookup must not hold the Play request past its startup budget. */
    private Optional<JellyfinSession> findSession(Device device, long deadline, String timeoutMessage) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new ActionFailedException(timeoutMessage);
        }
        FutureTask<Optional<JellyfinSession>> probe = new FutureTask<>(() -> sessions.sessionFor(device));
        Thread.ofVirtual().name("jellyfin-session-probe").start(probe);
        try {
            return probe.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException _) {
            throw new ActionFailedException(timeoutMessage);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            throw new ActionFailedException("Jellyfin startup was interrupted");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw new ActionFailedException("Could not check the Jellyfin app on " + device.name());
        } finally {
            // The worker only queries readiness; even a late result can never send PlayNow.
            probe.cancel(true);
        }
    }

    private static void pause(long deadline) {
        try {
            long remaining = deadline - System.nanoTime();
            if (remaining > 0) {
                Thread.sleep(Duration.ofNanos(Math.min(remaining, Duration.ofMillis(250).toNanos())));
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            throw new ActionFailedException("Jellyfin startup was interrupted");
        }
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new ActionFailedException("Jellyfin startup was interrupted");
        }
    }
}
