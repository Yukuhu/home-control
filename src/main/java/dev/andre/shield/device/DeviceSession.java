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

    private final Device device;
    private final ClientCertificate credential;
    private final ShieldProperties properties;
    private final Consumer<DeviceState> onChange;
    private final ScheduledExecutorService scheduler;

    private volatile RemoteConnection connection;
    private volatile DeviceState state = DeviceState.initial();
    private volatile Duration backoff;
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

    public void launchAppLink(String uri) {
        RemoteConnection current = requireConnected();
        try {
            current.launchAppLink(uri);
        } catch (IOException e) {
            throw new DeviceOfflineException("The device dropped the connection while launching " + uri);
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
            update(state.withStatus(DeviceStatus.CONNECTED));
        } catch (RemoteConnection.UnpairedException e) {
            log.warn("Device {} rejected our certificate; it must be paired again", device.id());
            update(state.withStatus(DeviceStatus.UNPAIRED));
        } catch (IOException e) {
            log.debug("Could not reach {}: {}", device.host(), e.getMessage());
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

    private void scheduleReconnect() {
        if (closed) {
            return;
        }
        Duration delay = backoff;
        backoff = Duration.ofSeconds(Math.min(
                backoff.toSeconds() * 2, properties.reconnectMaxDelaySeconds()));
        scheduler.schedule(this::connect, delay.toSeconds(), TimeUnit.SECONDS);
    }

    @Override
    public void onPower(boolean on) {
        update(state.withPower(on));
    }

    @Override
    public void onCurrentApp(String appPackage) {
        update(state.withCurrentApp(appPackage));
    }

    @Override
    public void onVolume(int level, int max, boolean isMuted) {
        update(state.withVolume(level, max, isMuted));
    }

    @Override
    public void onDisconnected(DisconnectCause cause) {
        if (closed) {
            return;
        }
        connection = null;
        if (cause == DisconnectCause.UNPAIRED) {
            log.warn("Device {} rejected our certificate; not retrying", device.id());
            update(state.withStatus(DeviceStatus.UNPAIRED));
            return;
        }
        log.info("Lost the connection to {} ({}); reconnecting", device.id(), cause);
        update(state.withStatus(DeviceStatus.DISCONNECTED));
        scheduleReconnect();
    }

    private void update(DeviceState updated) {
        state = updated;
        onChange.accept(updated);
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
