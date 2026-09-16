package dev.andre.homecontrol.adapters.cast;

import dev.andre.homecontrol.adapters.cast.protocol.CastConnection;
import dev.andre.homecontrol.adapters.cast.protocol.CastDisconnectCause;
import dev.andre.homecontrol.adapters.cast.protocol.CastIncoming;
import dev.andre.homecontrol.adapters.cast.protocol.CastPayloads;
import dev.andre.homecontrol.adapters.cast.protocol.CastTimeoutException;
import dev.andre.homecontrol.adapters.cast.protocol.ReceiverStatus;
import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.UnsupportedActionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static dev.andre.homecontrol.adapters.cast.protocol.CastNamespaces.PLATFORM_RECEIVER_ID;
import static dev.andre.homecontrol.adapters.cast.protocol.CastNamespaces.RECEIVER;

/**
 * One Cast receiver's live connection, shaped like the Android TV session: every mutation of
 * connection and state happens on one scheduler thread; reconnects back off exponentially.
 * Cast has no pairing, so there is no UNPAIRED state.
 */
public class CastSession implements DeviceHandle {

    private static final Logger log = LoggerFactory.getLogger(CastSession.class);

    private final Device device;
    private final CastSettings settings;
    private final CastProperties properties;
    private final Consumer<DeviceState> onChange;
    private final ScheduledExecutorService scheduler;

    private volatile CastConnection connection;
    /** Incremented per connection attempt; callbacks from older connections are ignored. */
    private volatile long generation;
    private volatile DeviceState state = DeviceState.initial();
    private volatile ReceiverStatus receiver;
    private volatile Duration backoff;
    private volatile boolean closed;

    public CastSession(Device device, CastProperties properties, Consumer<DeviceState> onChange) {
        this.device = device;
        this.settings = CastSettings.of(device);
        this.properties = properties;
        this.onChange = onChange;
        this.backoff = Duration.ofSeconds(properties.reconnectInitialDelaySeconds());
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "cast-session-" + device.id());
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        scheduler.execute(this::connect);
    }

    @Override
    public DeviceState state() {
        return state;
    }

    @Override
    public void execute(Action action) {
        switch (action) {
            case Action.PressKey ignored -> throw new UnsupportedActionException(
                    device.name() + " is a Cast receiver and has no remote keys");
            case Action.OpenAppLink ignored -> throw new UnsupportedActionException(
                    device.name() + " is a Cast receiver and cannot open app links");
            case Action.SetVolume set -> receiverCommand(CastPayloads.setVolumeLevel(set.level() / 100.0), "set the volume");
            case Action.Mute mute -> receiverCommand(CastPayloads.setMuted(mute.muted()), mute.muted() ? "mute" : "unmute");
            case Action.Stop ignored -> stopForegroundApp();
        }
    }

    /**
     * Receiver-namespace commands are answered with a RECEIVER_STATUS; anything else is a refusal.
     * Runs on the caller's thread: the reply arrives on the reader thread, and the scheduler
     * thread keeps handling state updates meanwhile.
     */
    private void receiverCommand(ObjectNode payload, String what) {
        CastConnection current = requireConnected();
        CastIncoming reply = call(() -> current.request(RECEIVER, PLATFORM_RECEIVER_ID, payload, commandTimeout()), what);
        if (!"RECEIVER_STATUS".equals(reply.type())) {
            throw new ActionFailedException(device.name() + " refused to " + what + " (" + reply.describeFailure() + ")");
        }
    }

    private void stopForegroundApp() {
        CastConnection current = requireConnected();
        ReceiverStatus status = receiver;
        Optional<ReceiverStatus.ReceiverApp> app = status == null ? Optional.empty() : status.foregroundApp();
        if (app.isEmpty()) {
            return; // nothing is casting, so it is already stopped
        }
        receiverCommand(CastPayloads.stop(app.get().sessionId()), "stop " + app.get().displayName());
    }

    private CastConnection requireConnected() {
        CastConnection current = connection;
        if (closed || current == null || state.status() != DeviceStatus.CONNECTED) {
            throw new DeviceOfflineException(device.name() + " is not connected");
        }
        return current;
    }

    @FunctionalInterface
    private interface CastCall<T> {
        T run() throws IOException;
    }

    /** Maps protocol failures onto the core vocabulary: no answer → failed; lost channel → offline. */
    private <T> T call(CastCall<T> call, String what) {
        try {
            return call.run();
        } catch (CastTimeoutException e) {
            throw new ActionFailedException(device.name() + " did not answer in time when asked to " + what);
        } catch (IOException e) {
            throw new DeviceOfflineException(device.name() + " dropped the connection while trying to " + what);
        }
    }

    private Duration commandTimeout() {
        return Duration.ofSeconds(properties.commandTimeoutSeconds());
    }

    private void connect() {
        if (closed) {
            return;
        }
        long attempt = ++generation;
        update(state.withStatus(DeviceStatus.CONNECTING));
        CastConnection opened = null;
        try {
            opened = CastConnection.open(settings.host(), settings.port(),
                    Duration.ofSeconds(properties.heartbeatIntervalSeconds()),
                    Duration.ofSeconds(properties.staleTimeoutSeconds()),
                    new Link(attempt));
            opened.send(RECEIVER, PLATFORM_RECEIVER_ID, CastPayloads.getStatus()); // the reply arrives via Link
            connection = opened;
            if (closed) {
                // close() ran while open() was blocking this thread and read connection before
                // it was set, so nobody else will ever close this one. Both fields are volatile
                // and close() sets closed before reading connection: one side always sees the other.
                opened.close();
                connection = null;
                return;
            }
            backoff = Duration.ofSeconds(properties.reconnectInitialDelaySeconds());
            update(state.withStatus(DeviceStatus.CONNECTED));
        } catch (IOException e) {
            if (opened != null) {
                opened.close();
            }
            generation++; // anything the failed attempt still reports is stale
            log.debug("Could not reach Cast receiver {} at {}:{}: {}", device.id(), settings.host(), settings.port(), e.getMessage());
            update(state.withStatus(DeviceStatus.DISCONNECTED));
            scheduleReconnect();
        }
    }

    private void handle(CastIncoming message) {
        if (RECEIVER.equals(message.namespace()) && "RECEIVER_STATUS".equals(message.type())) {
            ReceiverStatus status = ReceiverStatus.parse(message.payload().path("status"));
            receiver = status;
            update(state.withPower(!status.standBy())
                    .withCurrentApp(status.foregroundApp().map(ReceiverStatus.ReceiverApp::displayName).orElse(null))
                    .withVolume(status.volumePercent(), 100, status.muted()));
        }
    }

    private void handleDisconnect(CastDisconnectCause cause) {
        connection = null;
        receiver = null;
        log.info("Lost the Cast connection to {} ({}); reconnecting", device.id(), cause);
        update(state.withStatus(DeviceStatus.DISCONNECTED));
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (closed) {
            return;
        }
        Duration delay = backoff;
        backoff = Duration.ofSeconds(Math.min(backoff.toSeconds() * 2, properties.reconnectMaxDelaySeconds()));
        try {
            scheduler.schedule(this::connect, delay.toSeconds(), TimeUnit.SECONDS);
        } catch (RejectedExecutionException ignored) {
            // Closing.
        }
    }

    /** Hands a reader-thread callback to the scheduler, dropping it if its connection is outdated. */
    private void runOnScheduler(long attempt, Runnable task) {
        if (closed) {
            return;
        }
        try {
            scheduler.execute(() -> {
                if (!closed && attempt == generation) {
                    task.run();
                }
            });
        } catch (RejectedExecutionException ignored) {
            // close() shut the scheduler down in between.
        }
    }

    private void update(DeviceState updated) {
        if (closed) {
            // connect() can still be mid-flight on the scheduler thread when close() runs;
            // a closed session must not publish state for a handle nobody holds anymore.
            return;
        }
        state = updated;
        try {
            onChange.accept(updated);
        } catch (Throwable t) {
            log.warn("A device state listener failed for {}", device.id(), t);
        }
    }

    @Override
    public void close() {
        closed = true;
        scheduler.shutdownNow();
        CastConnection current = connection;
        if (current != null) {
            current.close();
        }
    }

    private final class Link implements CastConnection.Listener {

        private final long attempt;

        Link(long attempt) {
            this.attempt = attempt;
        }

        @Override
        public void onMessage(CastIncoming message) {
            runOnScheduler(attempt, () -> handle(message));
        }

        @Override
        public void onDisconnected(CastDisconnectCause cause) {
            runOnScheduler(attempt, () -> handleDisconnect(cause));
        }
    }
}
