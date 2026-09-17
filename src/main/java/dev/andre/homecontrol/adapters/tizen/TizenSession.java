package dev.andre.homecontrol.adapters.tizen;

import dev.andre.homecontrol.adapters.net.WakeOnLan;
import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DeviceRegistry;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.KeyPress;
import dev.andre.homecontrol.core.LearnedSettings;
import dev.andre.homecontrol.core.RemoteKey;
import dev.andre.homecontrol.core.UnsupportedActionException;
import dev.andre.homecontrol.core.WakeOnLanAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * One Samsung Tizen TV. Samsung pushes no state, so a poll (every {@code pollIntervalSeconds})
 * reads power and MAC from the REST API, (re)opens the remote channel with the stored token when
 * the TV is on, and derives the current app from the visibility of the known service apps.
 * An explicit refusal ({@code ms.channel.unauthorized}) makes the session UNPAIRED and stops it.
 * Silence after opening the channel is transient (a slow-booting TV): the token is kept and the
 * next handshake waits with a doubling backoff, so a stale token cannot put the Allow prompt on
 * screen every few seconds.
 *
 * <p>Settings are read from the registry (the MAC may be typed in meanwhile) but written only
 * through {@link LearnedSettings}, i.e. by the device manager under its lock.
 *
 * <p>Tizen never publishes CONNECTING: a poll every few seconds against a switched-off TV would
 * otherwise emit two SSE events per interval.
 */
public class TizenSession implements DeviceHandle {

    private static final Logger log = LoggerFactory.getLogger(TizenSession.class);
    private static final Duration MAX_HANDSHAKE_BACKOFF = Duration.ofMinutes(5);

    private final Device device;
    private final TizenProperties properties;
    private final HttpClient http;
    private final TizenRest rest;
    private final DialClient dial;
    private final DeviceRegistry registry;
    private final LearnedSettings learned;
    private final WakeOnLan wakeOnLan;
    private final Consumer<DeviceState> onChange;
    private final Runnable onClose;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean nextPlayPauseIsPlay = new AtomicBoolean();

    private volatile TizenRemoteConnection connection;
    private volatile DeviceState state = DeviceState.initial();
    private volatile boolean closed;
    private volatile boolean stopped;
    private Duration handshakeBackoff;  // scheduler thread only; null while no handshake went unanswered
    private long connectNotBefore;      // scheduler thread only; System.nanoTime()

    TizenSession(Device device, TizenProperties properties, HttpClient http, DeviceRegistry registry,
                 LearnedSettings learned, WakeOnLan wakeOnLan, Consumer<DeviceState> onChange, Runnable onClose) {
        this.device = device;
        this.properties = properties;
        this.http = http;
        this.rest = new TizenRest(http, properties);
        this.dial = new DialClient(http, properties);
        this.registry = registry;
        this.learned = learned;
        this.wakeOnLan = wakeOnLan;
        this.onChange = onChange;
        this.onClose = onClose;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("tizen-" + device.id()).factory());
    }

    void start() {
        try {
            scheduler.scheduleWithFixedDelay(this::poll, 0, properties.pollIntervalSeconds(), TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            // Closed before it started.
        }
    }

    String host() {
        return device.host();
    }

    /** SSDP heard the TV: poll now instead of at the next interval. */
    void pollNow() {
        onScheduler(this::poll);
    }

    @Override
    public DeviceState state() {
        return state;
    }

    @Override
    public void execute(Action action) {
        switch (action) {
            case Action.PressKey press -> pressKey(press.key(), press.press());
            case Action.OpenAppLink open -> openAppLink(open.uri());
            case Action.SelectInput ignored -> throw new UnsupportedActionException(
                    device.name() + " does not list its inputs; use the Source button of the TV remote");
            case Action.SetVolume ignored -> throw volumeKeysOnly();
            case Action.Mute ignored -> throw volumeKeysOnly();
            case Action.Stop ignored -> sendKey("KEY_STOP");
            case Action.CastLoad ignored -> throw new UnsupportedActionException(device.name() + " is not a Cast receiver");
            case Action.CastMessage ignored -> throw new UnsupportedActionException(device.name() + " is not a Cast receiver");
            case Action.PlayMedia ignored -> throw new UnsupportedActionException(device.name() + " cannot play a direct stream");
            case Action.Pause ignored -> throw new UnsupportedActionException(device.name() + " cannot pause a direct stream");
            case Action.Resume ignored -> throw new UnsupportedActionException(device.name() + " cannot resume a direct stream");
        }
    }

    private UnsupportedActionException volumeKeysOnly() {
        return new UnsupportedActionException(device.name() + " only takes volume up, down and mute keys");
    }

    private void pressKey(RemoteKey key, KeyPress press) {
        if (press != KeyPress.SHORT) {
            // Held keys (navigation only) map onto the remote channel's own Press and Release.
            String code = TizenKeys.code(key).orElseThrow(() ->
                    new UnsupportedActionException(device.name() + " cannot hold " + key));
            TizenRemoteConnection current = requireConnected();
            try {
                current.key(code, press == KeyPress.START_LONG ? "Press" : "Release");
            } catch (IOException e) {
                throw new DeviceOfflineException(device.name() + " dropped the connection");
            }
            return;
        }
        switch (key) {
            case POWER -> togglePower();
            case PLAY_PAUSE -> sendKey(nextPlayPauseIsPlay.getAndSet(!nextPlayPauseIsPlay.get()) ? "KEY_PLAY" : "KEY_PAUSE");
            default -> sendKey(TizenKeys.code(key).orElseThrow(() ->
                    new UnsupportedActionException(device.name() + " has no " + key + " key")));
        }
    }

    private void sendKey(String code) {
        TizenRemoteConnection current = requireConnected();
        try {
            current.key(code);
        } catch (IOException e) {
            throw new DeviceOfflineException(device.name() + " dropped the connection");
        }
    }

    private void openAppLink(URI uri) {
        TizenRemoteConnection current = requireConnected();
        switch (TizenLaunches.forUri(uri, current.installedApps())) {
            case TizenLaunch.Dial launch -> {
                try {
                    dial.launch(device.host(), launch.app(), launch.body());
                } catch (DialException e) {
                    throw new ActionFailedException(device.name() + ": " + e.getMessage());
                } catch (IOException e) {
                    throw new DeviceOfflineException(device.name() + " did not answer the DIAL request");
                }
            }
            case TizenLaunch.App app -> {
                try {
                    current.launchApp(app.appId(), app.actionType());
                } catch (IOException e) {
                    throw new DeviceOfflineException(device.name() + " dropped the connection");
                }
            }
            case TizenLaunch.Unsupported unsupported -> throw new UnsupportedActionException(
                    device.name() + ": " + unsupported.reason());
        }
        pollNow(); // show the app that just came to the front without waiting for the next interval
    }

    private void togglePower() {
        if (connection != null && state.powerOn()) {
            sendKey("KEY_POWER");
            update(s -> s.withPower(false));
            return;
        }
        String mac = current().adapterSettings(TizenSettings.ADAPTER_ID).get(WakeOnLanAdapter.MAC_ADDRESS);
        if (mac == null || mac.isBlank()) {
            throw new DeviceOfflineException(device.name() + " is off and no MAC address is known for Wake-on-LAN;"
                    + " switch it on once by hand or enter its MAC address on the setup page");
        }
        try {
            wakeOnLan.wake(mac);
        } catch (IOException | IllegalArgumentException e) {
            throw new DeviceOfflineException("Could not send the Wake-on-LAN packet: " + e.getMessage());
        }
        try {
            scheduler.schedule(this::poll, properties.wakeGraceSeconds(), TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            // Closed meanwhile.
        }
    }

    private void poll() {
        if (closed || stopped) {
            return;
        }
        try {
            Optional<TizenDeviceInfo> info = rest.deviceInfo(device.host());
            info.ifPresent(this::learnMacAddress);
            if (info.isPresent() && !info.get().on()) {
                dropConnection();
                update(s -> s.withStatus(DeviceStatus.DISCONNECTED).withPower(false).withCurrentApp(null));
                return;
            }
            if (connection == null && (handshakeBackingOff() || !connect())) {
                return;
            }
            String app = visibleKnownApp();
            update(s -> s.withStatus(DeviceStatus.CONNECTED).withPower(true).withCurrentApp(app));
        } catch (RuntimeException e) {
            // Never let an exception cancel the fixed-delay schedule.
            log.warn("Polling {} failed", device.name(), e);
        }
    }

    private boolean handshakeBackingOff() {
        return handshakeBackoff != null && System.nanoTime() - connectNotBefore < 0;
    }

    private boolean connect() {
        TizenSettings settings = TizenSettings.of(current());
        if (!settings.paired()) {
            stopped = true;
            update(ignored -> DeviceState.unpaired());
            return false;
        }
        AtomicReference<TizenRemoteConnection> attempt = new AtomicReference<>();
        TizenRemoteConnection opened = null;
        try {
            opened = TizenRemoteConnection.open(http, device.host(), properties, settings.token(),
                    reason -> onScheduler(() -> lost(attempt.get(), reason)));
            attempt.set(opened);
            TizenRemoteConnection.Authorization answer =
                    opened.awaitAuthorization(Duration.ofSeconds(properties.requestTimeoutSeconds()));
            if (answer == TizenRemoteConnection.Authorization.CONNECTED) {
                handshakeBackoff = null;
                connectNotBefore = 0;
                connection = opened;
                if (closed) {
                    // close() ran while waiting for the TV and saw no connection to close.
                    connection = null;
                    opened.close();
                    return false;
                }
                opened.token().filter(token -> !token.equals(settings.token()))
                        .ifPresent(token -> learned.store(Map.of(TizenSettings.TOKEN, token)));
                opened.requestInstalledApps();
                return true;
            }
            opened.close();
            if (answer == TizenRemoteConnection.Authorization.NO_ANSWER) {
                // A slow-booting TV, or a forgotten token putting the Allow prompt on screen: never unpair
                // for silence. Keep the token and back off so a real prompt does not reappear every poll.
                Duration delay = nextHandshakeBackoff();
                log.info("{} did not answer the connection within {} seconds; retrying in {} seconds",
                        device.name(), properties.requestTimeoutSeconds(), delay.toSeconds());
                update(s -> s.withStatus(DeviceStatus.DISCONNECTED).withCurrentApp(null));
                return false;
            }
            stopped = true;
            log.warn("{} refused the stored pairing; pair it again on the setup page", device.name());
            update(ignored -> DeviceState.unpaired());
            return false;
        } catch (IOException e) {
            if (opened != null) {
                opened.close();
            }
            connection = null;
            log.debug("{} is not reachable: {}", device.name(), e.getMessage());
            update(s -> s.withStatus(DeviceStatus.DISCONNECTED).withPower(false).withCurrentApp(null));
            return false;
        }
    }

    /** Doubles from two poll intervals up to {@link #MAX_HANDSHAKE_BACKOFF}; the next connect waits that long. */
    private Duration nextHandshakeBackoff() {
        Duration first = Duration.ofSeconds(2L * properties.pollIntervalSeconds());
        handshakeBackoff = handshakeBackoff == null ? first : handshakeBackoff.multipliedBy(2);
        if (handshakeBackoff.compareTo(MAX_HANDSHAKE_BACKOFF) > 0) {
            handshakeBackoff = MAX_HANDSHAKE_BACKOFF;
        }
        connectNotBefore = System.nanoTime() + handshakeBackoff.toNanos();
        return handshakeBackoff;
    }

    private String visibleKnownApp() {
        TizenRemoteConnection current = connection;
        if (current == null) {
            return null;
        }
        for (TizenLaunch.App app : TizenLaunches.knownApps(current.installedApps())) {
            if (rest.appVisible(device.host(), app.appId()).orElse(false)) {
                return app.name();
            }
        }
        return null;
    }

    private void learnMacAddress(TizenDeviceInfo info) {
        info.macAddress().ifPresent(mac -> {
            TizenSettings settings = TizenSettings.of(current());
            if (!settings.macAddressManual() && !mac.equals(settings.macAddress())) {
                learned.store(Map.of(WakeOnLanAdapter.MAC_ADDRESS, mac));
            }
        });
    }

    private void lost(TizenRemoteConnection which, String reason) {
        if (which == null || which != connection) {
            return;
        }
        dropConnection();
        log.info("Lost the connection to {} ({})", device.name(), reason);
        update(s -> s.withStatus(DeviceStatus.DISCONNECTED).withPower(false).withCurrentApp(null));
    }

    private void dropConnection() {
        TizenRemoteConnection current = connection;
        connection = null;
        if (current != null) {
            current.close();
        }
    }

    private TizenRemoteConnection requireConnected() {
        TizenRemoteConnection current = connection;
        if (current != null) {
            return current;
        }
        if (state.status() == DeviceStatus.UNPAIRED) {
            throw new DeviceOfflineException(device.name() + " must be paired again before it can be controlled");
        }
        throw new DeviceOfflineException(device.name() + " is not connected");
    }

    /** The registry's copy: settings (MAC, token) may have changed since this handle was created. */
    private Device current() {
        return registry.findById(device.id()).orElse(device);
    }

    /** Publishes only visible changes. */
    private synchronized void update(UnaryOperator<DeviceState> change) {
        if (closed) {
            return;
        }
        DeviceState next = change.apply(state);
        if (next.sameIgnoringTime(state)) {
            return;
        }
        state = next;
        onChange.accept(next);
    }

    private void onScheduler(Runnable task) {
        if (closed) {
            return;
        }
        try {
            scheduler.execute(task);
        } catch (RejectedExecutionException e) {
            // Closed meanwhile.
        }
    }

    @Override
    public void close() {
        closed = true;
        scheduler.shutdownNow();
        dropConnection();
        onClose.run();
    }
}
