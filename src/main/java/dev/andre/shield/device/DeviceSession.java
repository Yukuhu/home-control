package dev.andre.shield.device;

import dev.andre.shield.ShieldProperties;
import dev.andre.shield.protocol.ClientCertificate;
import dev.andre.shield.protocol.DisconnectCause;
import dev.andre.shield.protocol.RemoteConnection;
import dev.andre.shield.protocol.RemoteKey;
import dev.andre.shield.protocol.RemoteListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * One device's live connection: connects, keeps the last known {@link DeviceState},
 * and reconnects with exponential backoff — except when the device has rejected the
 * pairing, where retrying is pointless (spec §8).
 */
public class DeviceSession implements RemoteListener, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DeviceSession.class);

    /**
     * A single UNPAIRED verdict is ambiguous: it fires both for a genuinely de-paired
     * device and for a connection that drops before the app-level handshake finishes
     * (indistinguishable from here — see {@code RemoteConnection.classify}). Requiring
     * this many in a row before latching keeps an ordinary reboot from being mistaken
     * for a de-pairing: on the default 1s-doubling ramp the fifth verdict lands about
     * half a minute after the first, past the window in which a rebooting device is
     * accepting TLS connections but tearing them down again. Latching earlier is the
     * damaging mistake, because UNPAIRED never schedules another attempt — while a real
     * re-pair only has to be noticed eventually, and a fingerprint MISMATCH, which is
     * not ambiguous at all, still latches on the first occurrence.
     */
    private static final int UNPAIRED_CONFIRMATION_THRESHOLD = 5;

    private final Device device;
    private final ClientCertificate credential;
    private final ShieldProperties properties;
    private final Consumer<DeviceState> onChange;
    private final ScheduledExecutorService scheduler;

    private volatile RemoteConnection connection;
    private volatile DeviceState state = DeviceState.initial();
    private volatile Duration backoff;
    private volatile int consecutiveUnpaired;
    private volatile boolean closed;

    public DeviceSession(Device device, ClientCertificate credential,
                         ShieldProperties properties, Consumer<DeviceState> onChange) {
        this.device = device;
        this.credential = credential;
        this.properties = properties;
        this.onChange = onChange;
        this.backoff = Duration.ofSeconds(properties.reconnectInitialDelaySeconds());
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "shield-session-" + device.id());
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        scheduler.execute(this::connect);
    }

    public Device device() {
        return device;
    }

    public DeviceState state() {
        return state;
    }

    public void sendKey(RemoteKey key) {
        RemoteConnection current = requireConnected();
        try {
            current.sendKey(key);
        } catch (IOException e) {
            throw new DeviceOfflineException("The device dropped the connection while sending " + key);
        }
    }

    private RemoteConnection requireConnected() {
        RemoteConnection current = connection;
        if (current == null || state.status() != DeviceStatus.CONNECTED) {
            throw new DeviceOfflineException("The device is not connected");
        }
        return current;
    }

    private void connect() {
        if (closed) {
            return;
        }
        update(state.withStatus(DeviceStatus.CONNECTING));
        try {
            RemoteConnection opened = RemoteConnection.connect(device.host(), device.port(),
                    credential, properties.staleTimeoutSeconds() * 1000, this);

            if (!presentsThePinnedCertificate(opened)) {
                log.warn("Device {} presented an unexpected certificate; refusing it", device.id());
                opened.close();
                update(state.withStatus(DeviceStatus.UNPAIRED));
                return;
            }

            connection = opened;
            backoff = Duration.ofSeconds(properties.reconnectInitialDelaySeconds());
            forgetAmbiguousVerdicts();
            update(state.withStatus(DeviceStatus.CONNECTED));
        } catch (RemoteConnection.UnpairedException e) {
            handleAmbiguousUnpaired();
        } catch (IOException e) {
            log.debug("Could not reach {}: {}", device.host(), e.getMessage());
            forgetAmbiguousVerdicts();
            update(state.withStatus(DeviceStatus.DISCONNECTED));
            scheduleReconnect();
        }
    }

    /** A device recorded without a fingerprint (paired before pinning) is accepted once. */
    private boolean presentsThePinnedCertificate(RemoteConnection opened) {
        String pinned = device.certificateFingerprint();
        return pinned == null
                || pinned.equals(ClientCertificate.fingerprintOf(opened.serverCertificate()));
    }

    /**
     * Handles an ambiguous UNPAIRED verdict — from either {@link RemoteConnection.UnpairedException}
     * or {@link DisconnectCause#UNPAIRED} — by retrying like an ordinary drop until it has
     * happened {@value #UNPAIRED_CONFIRMATION_THRESHOLD} times in a row, spanning a plausible
     * device reboot, then latching.
     * A certificate fingerprint MISMATCH is not ambiguous and does not go through here —
     * it latches immediately, on the first occurrence (see {@code presentsThePinnedCertificate}).
     */
    private void handleAmbiguousUnpaired() {
        consecutiveUnpaired++;
        if (consecutiveUnpaired < UNPAIRED_CONFIRMATION_THRESHOLD) {
            log.info("Device {} looked unpaired ({}/{}); retrying before giving up",
                    device.id(), consecutiveUnpaired, UNPAIRED_CONFIRMATION_THRESHOLD);
            update(state.withStatus(DeviceStatus.DISCONNECTED));
            scheduleReconnect();
        } else {
            log.warn("Device {} rejected our certificate {} times in a row; it must be paired again",
                    device.id(), consecutiveUnpaired);
            update(state.withStatus(DeviceStatus.UNPAIRED));
        }
    }

    /**
     * Clears the ambiguous-verdict count after any outcome that was NOT ambiguous — a
     * successful connection, or a network-class failure (spec §8 class 1), or a drop the
     * device explained some other way. The latch is a rule about CONSECUTIVE verdicts: a
     * device that could not be reached at all is positive evidence that the verdicts before
     * it were not a rejected certificate, so counting them together would let a merely flaky
     * device accumulate a latch over days and tell the user to re-pair when nothing is wrong.
     * A fingerprint MISMATCH does not come through here at all; it still latches at once.
     */
    private void forgetAmbiguousVerdicts() {
        consecutiveUnpaired = 0;
    }

    private void scheduleReconnect() {
        if (closed) {
            return;
        }
        Duration delay = backoff;
        backoff = Duration.ofSeconds(Math.min(
                backoff.toSeconds() * 2, properties.reconnectMaxDelaySeconds()));
        scheduler.schedule(this::connect, delay.toSeconds(), TimeUnit.SECONDS);
    }

    /*
     * Every RemoteListener callback below arrives on the protocol reader thread.
     * RemoteConnection's constructor starts that thread before its connect() factory
     * even returns, so any of these can fire while connect() — running on this
     * session's own scheduler thread — is still executing its success path for the
     * very same connection. Each of these does a read-modify-write on `state` (and
     * onDisconnected additionally writes connection/backoff/consecutiveUnpaired); left
     * unserialized, connect()'s own writes could interleave with one of these and get
     * silently clobbered, or clobber one of these in turn. So none of them touch that
     * state directly — each hands its work off to runOnScheduler(), the same
     * single-threaded scheduler connect() already runs on, making every mutation of
     * connection/state/backoff/consecutiveUnpaired happen on exactly one thread.
     */

    @Override
    public void onPower(boolean on) {
        runOnScheduler(() -> update(state.withPower(on)));
    }

    @Override
    public void onCurrentApp(String appPackage) {
        runOnScheduler(() -> update(state.withCurrentApp(appPackage)));
    }

    @Override
    public void onVolume(int level, int max, boolean isMuted) {
        runOnScheduler(() -> update(state.withVolume(level, max, isMuted)));
    }

    @Override
    public void onDisconnected(DisconnectCause cause) {
        runOnScheduler(() -> handleDisconnect(cause));
    }

    private void handleDisconnect(DisconnectCause cause) {
        connection = null;
        if (cause == DisconnectCause.UNPAIRED) {
            handleAmbiguousUnpaired();
            return;
        }
        log.info("Lost the connection to {} ({}); reconnecting", device.id(), cause);
        forgetAmbiguousVerdicts();
        update(state.withStatus(DeviceStatus.DISCONNECTED));
        scheduleReconnect();
    }

    /**
     * Hands one {@link RemoteListener} callback's work off to the scheduler, guarded against
     * a session that is already closing — checked once here before scheduling, and again
     * (inside the scheduled task itself) in case {@link #close()} shuts the scheduler down
     * between that check and the task actually running. A single guard, used by every
     * callback above, so the guard cannot drift out of sync between them.
     */
    private void runOnScheduler(Runnable task) {
        if (closed) {
            return;
        }
        try {
            scheduler.execute(() -> {
                if (!closed) {
                    task.run();
                }
            });
        } catch (RejectedExecutionException e) {
            // close() shut the scheduler down between the check above and this handoff;
            // the session is going away, so there is nothing left to update.
        }
    }

    private void update(DeviceState updated) {
        state = updated;
        try {
            onChange.accept(updated);
        } catch (Throwable t) {
            // The listener publishes a Spring event, delivered synchronously on this thread
            // to subscribers this class knows nothing about. Whatever they do, the transition
            // has already happened and the caller must get to its scheduleReconnect() — a
            // listener that throws must never be able to wedge the session.
            log.warn("A device state listener failed for {}", device.id(), t);
        }
    }

    @Override
    public void close() {
        closed = true;
        scheduler.shutdownNow();
        RemoteConnection current = connection;
        if (current != null) {
            current.close();
        }
    }
}
