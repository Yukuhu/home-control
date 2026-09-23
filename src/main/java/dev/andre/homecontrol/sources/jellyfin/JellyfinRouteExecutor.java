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
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

public class JellyfinRouteExecutor implements RouteExecutor {

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
                case Route.JellyfinApp app -> sessions.playNow(prepare(device), app.itemId(), app.startPositionTicks());
                case Route.JellyfinSession session -> {
                    String id = device.hasAdapter("androidtv") ? prepare(device) : session.sessionId();
                    sessions.playNow(id, session.itemId(), session.startPositionTicks());
                }
                default -> throw new IllegalArgumentException("Not a Jellyfin route");
            }
        } catch (JellyfinException | IllegalArgumentException e) {
            throw new ActionFailedException("Jellyfin could not start playback on " + device.name() + " (" + e.getMessage() + ")");
        }
    }

    private String prepare(Device device) {
        long deadline = System.nanoTime() + startupTimeout.toNanos();
        DeviceState state = awaitState(device, deadline, DeviceState::connected,
                device.name() + " did not connect; check that the Shield is reachable and paired", true);
        if (!state.powerOn()) {
            // WAKEUP is idempotent, unlike POWER, even when the reported state is stale.
            devices.execute(device.id(), new Action.PressKey(RemoteKey.WAKEUP));
            state = awaitState(device, deadline, s -> s.connected() && s.powerOn(),
                    device.name() + " did not wake up in time", false);
        }
        if (!PACKAGE.equals(state.currentApp())) {
            devices.execute(device.id(), new Action.OpenAppLink(APP_LINK));
        }
        String notReady = "Jellyfin did not become ready on " + device.name()
                + "; check that the app is installed, sign in on the TV, and link it under Setup → Jellyfin apps if needed";
        while (true) {
            awaitState(device, deadline, s -> s.connected() && s.powerOn() && PACKAGE.equals(s.currentApp()),
                    notReady, false);
            Optional<JellyfinSession> session = findSession(device, deadline, notReady);
            checkInterrupted();
            // Refresh the session after startup; the id from a preview can already be obsolete.
            if (System.nanoTime() >= deadline) {
                throw new ActionFailedException(notReady);
            }
            if (session.isPresent()) {
                return session.get().id();
            }
            pause(deadline);
        }
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
        } catch (TimeoutException e) {
            throw new ActionFailedException(timeoutMessage);
        } catch (InterruptedException e) {
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

    private DeviceState awaitState(Device device, long deadline, Predicate<DeviceState> ready,
                                   String timeoutMessage, boolean connecting) {
        while (true) {
            checkInterrupted();
            DeviceState state = devices.state(device.id());
            if (state.status() == DeviceStatus.UNPAIRED) {
                throw new DeviceOfflineException(device.name() + " must be paired again before Jellyfin can start");
            }
            if (System.nanoTime() >= deadline) {
                if (connecting) {
                    throw new DeviceOfflineException(timeoutMessage);
                }
                throw new ActionFailedException(timeoutMessage);
            }
            if (ready.test(state)) {
                return state;
            }
            pause(deadline);
        }
    }

    private static void pause(long deadline) {
        try {
            long remaining = deadline - System.nanoTime();
            if (remaining > 0) {
                Thread.sleep(Duration.ofNanos(Math.min(remaining, Duration.ofMillis(250).toNanos())));
            }
        } catch (InterruptedException e) {
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
