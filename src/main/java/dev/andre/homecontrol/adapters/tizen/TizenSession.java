package dev.andre.homecontrol.adapters.tizen;

import dev.andre.homecontrol.adapters.net.WakeOnLan;
import dev.andre.homecontrol.adapters.support.Backoff;
import dev.andre.homecontrol.adapters.support.ConnectionSlot;
import dev.andre.homecontrol.adapters.support.DeviceCalls;
import dev.andre.homecontrol.adapters.support.LearnedMac;
import dev.andre.homecontrol.adapters.support.PlayPauseToggle;
import dev.andre.homecontrol.adapters.support.SessionLoop;
import dev.andre.homecontrol.adapters.support.StatePublisher;
import dev.andre.homecontrol.adapters.support.WakeOnLanPower;
import dev.andre.homecontrol.adapters.tizen.protocol.DialClient;
import dev.andre.homecontrol.adapters.tizen.protocol.TizenDeviceInfo;
import dev.andre.homecontrol.adapters.tizen.protocol.TizenRemoteConnection;
import dev.andre.homecontrol.adapters.tizen.protocol.TizenRest;
import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DeviceRegistry;
import dev.andre.homecontrol.core.DeviceSecrets;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.KeyPress;
import dev.andre.homecontrol.core.LearnedSettings;
import dev.andre.homecontrol.core.MacAddress;
import dev.andre.homecontrol.core.RemoteKey;
import dev.andre.homecontrol.core.UnsupportedActionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * One Samsung Tizen TV. Samsung pushes no state, so a poll (every {@link TizenTimings#pollInterval()})
 * reads power and MAC from the REST API, (re)opens the remote channel with the stored token when
 * the TV is on, and derives the current app from the visibility of the known service apps.
 * An explicit refusal ({@code ms.channel.unauthorized}) makes the session UNPAIRED and stops it.
 * Silence after opening the channel is transient (a slow-booting TV): the token is kept and the
 * next handshake waits with a doubling backoff, so a stale token cannot put the Allow prompt on
 * screen every few seconds.
 *
 * <p>Settings are read from the registry (the MAC may be typed in meanwhile) but written only
 * through {@link LearnedSettings}, i.e. by the device package under its registry lock.
 *
 * <p>Tizen never publishes CONNECTING: a poll every few seconds against a switched-off TV would
 * otherwise emit two SSE events per interval.
 */
public class TizenSession implements DeviceHandle {

    private static final Logger log = LoggerFactory.getLogger(TizenSession.class);

    private final Device device;
    private final TizenProperties properties;
    private final TizenTimings timings;
    private final HttpClient http;
    private final TizenRest rest;
    private final DialClient dial;
    private final DeviceRegistry registry;
    private final LearnedSettings learned;
    private final DeviceSecrets secrets;
    private final Runnable onClose;
    private final StatePublisher publisher;
    private final SessionLoop loop;
    private final ConnectionSlot<TizenRemoteConnection> connection;
    private final WakeOnLanPower power;
    private final LearnedMac learnedMac;
    private final PlayPauseToggle playPause = new PlayPauseToggle();
    /** How long the next handshake waits after one went unanswered: from two poll intervals up to the cap. */
    private final Backoff handshakeBackoff;

    private volatile boolean stopped;
    private volatile boolean closed;
    private boolean handshakeWaiting; // loop thread only
    private long connectNotBefore;    // loop thread only; System.nanoTime(), meaningful while handshakeWaiting

    // Package-private, built only by TizenAdapter: ten distinct collaborator types, nothing to group.
    @SuppressWarnings("java:S107")
    TizenSession(Device device, TizenProperties properties, TizenTimings timings, HttpClient http, DeviceRegistry registry,
                 LearnedSettings learned, DeviceSecrets secrets, WakeOnLan wakeOnLan, Consumer<DeviceState> onChange,
                 Runnable onClose) {
        this.device = device;
        this.properties = properties;
        this.timings = timings;
        this.http = http;
        this.rest = new TizenRest(http, properties.protocol());
        this.dial = new DialClient(http, properties.protocol());
        this.registry = registry;
        this.learned = learned;
        this.secrets = secrets;
        this.onClose = onClose;
        this.publisher = new StatePublisher(device.id(), DeviceState.initial(), onChange);
        this.loop = new SessionLoop("tizen-" + device.id());
        this.connection = new ConnectionSlot<>("tizen-" + device.id());
        this.power = new WakeOnLanPower(device.name(), this::current, TizenSettings.ADAPTER_ID, wakeOnLan);
        this.learnedMac = new LearnedMac(this::current, TizenSettings.ADAPTER_ID, learned);
        this.handshakeBackoff = new Backoff(timings.pollInterval().multipliedBy(2), timings.handshakeBackoffCap());
    }

    void start() {
        loop.execute(this::poll);
        loop.every(this::poll, timings.pollInterval());
    }

    String host() {
        return device.host();
    }

    /** SSDP heard the TV: poll now instead of at the next interval. */
    void pollNow() {
        loop.execute(this::poll);
    }

    @Override
    public DeviceState state() {
        return publisher.current();
    }

    @Override
    public void execute(Action action) {
        switch (action) {
            case Action.PressKey(var key, var press) -> pressKey(key, press);
            case Action.OpenAppLink(var uri, _) -> openAppLink(uri);
            case Action.SelectInput _ -> throw new UnsupportedActionException(
                    device.name() + " does not list its inputs; use the Source button of the TV remote");
            case Action.SetVolume _ -> throw volumeKeysOnly();
            case Action.Mute _ -> throw volumeKeysOnly();
            case Action.Stop _ -> sendKey("KEY_STOP", "stop");
            case Action.CastLoad _ -> throw new UnsupportedActionException(device.name() + " is not a Cast receiver");
            case Action.CastMessage _ -> throw new UnsupportedActionException(device.name() + " is not a Cast receiver");
            case Action.PlayMedia _ -> throw new UnsupportedActionException(device.name() + " cannot play a direct stream");
            case Action.Pause _ -> throw new UnsupportedActionException(device.name() + " cannot pause a direct stream");
            case Action.Resume _ -> throw new UnsupportedActionException(device.name() + " cannot resume a direct stream");
            case Action.JoinGroup _ -> throw new UnsupportedActionException(device.name() + " cannot be grouped");
            case Action.LeaveGroup _ -> throw new UnsupportedActionException(device.name() + " cannot be grouped");
        }
    }

    private UnsupportedActionException volumeKeysOnly() {
        return new UnsupportedActionException(device.name() + " only takes volume up, down and mute keys");
    }

    private void pressKey(RemoteKey key, KeyPress press) {
        if (press != KeyPress.SHORT) {
            // Held keys (navigation only) map onto the remote channel's own Press and Release.
            String code = TizenKeys.code(key).orElseThrow(() ->
                    new UnsupportedActionException(device.name() + " cannot hold " + key.label()));
            TizenRemoteConnection current = requireConnected();
            boolean starts = press == KeyPress.START_LONG;
            DeviceCalls.run(device.name(), (starts ? "hold " : "release ") + key.label(),
                    () -> current.key(code, starts ? "Press" : "Release"));
            return;
        }
        switch (key) {
            case POWER -> togglePower();
            case PLAY_PAUSE -> {
                boolean play = playPause.playNext();
                sendKey(play ? "KEY_PLAY" : "KEY_PAUSE", play ? "play" : "pause");
            }
            default -> sendKey(TizenKeys.code(key).orElseThrow(() ->
                    new UnsupportedActionException(device.name() + " has no " + key.label() + " key")),
                    "press " + key.label());
        }
    }

    /** Sends {@code code}; {@code what} says what that does, for "Samsung TV did not answer … to press home". */
    private void sendKey(String code, String what) {
        TizenRemoteConnection current = requireConnected();
        DeviceCalls.run(device.name(), what, () -> current.key(code));
    }

    private void openAppLink(URI uri) {
        TizenRemoteConnection current = requireConnected();
        switch (TizenLaunches.forUri(uri, current.installedApps())) {
            case TizenLaunch.Dial(var app, var body) ->
                    DeviceCalls.run(device.name(), "start " + app, () -> dial.launch(device.host(), app, body));
            case TizenLaunch.App(var appId, var name, var actionType) ->
                    DeviceCalls.run(device.name(), "open " + name, () -> current.launchApp(appId, actionType));
            case TizenLaunch.Unsupported(var reason) -> throw new UnsupportedActionException(
                    device.name() + ": " + reason);
        }
        pollNow(); // show the app that just came to the front without waiting for the next interval
    }

    private void togglePower() {
        // A handle closed by a removal, a re-pair or shutdown has no connection either, but must not wake the TV.
        if (closed) {
            throw DeviceCalls.notConnected(device.name());
        }
        if (connection.current().isPresent() && publisher.current().powerOn()) {
            sendKey("KEY_POWER", "switch off");
            publisher.update(state -> state.withPower(false));
            return;
        }
        power.wake();
        loop.schedule(this::poll, timings.wakeGrace());
    }

    /** Runs on the loop; a poll that throws is logged there and the next one still comes. */
    private void poll() {
        if (stopped) {
            return;
        }
        Optional<TizenDeviceInfo> info = rest.deviceInfo(device.host());
        info.flatMap(TizenSession::reportedMac).ifPresent(learnedMac::offer);
        if (info.isPresent() && !info.get().on()) {
            connection.current().ifPresent(connection::takeIf);
            publisher.update(state -> state.withStatus(DeviceStatus.DISCONNECTED).withPower(false).withCurrentApp(null));
            return;
        }
        if (connection.current().isEmpty() && (handshakeBackingOff() || !connect())) {
            return;
        }
        String app = visibleKnownApp();
        publisher.update(state -> state.withStatus(DeviceStatus.CONNECTED).withPower(true).withCurrentApp(app));
    }

    /** Named {@code wifiMac}, but it is the MAC of the active interface, wired or not; a garbled one is ignored. */
    static Optional<String> reportedMac(TizenDeviceInfo info) {
        try {
            return info.wifiMac().isEmpty() ? Optional.empty() : Optional.of(MacAddress.normalize(info.wifiMac()));
        } catch (IllegalArgumentException _) {
            return Optional.empty();
        }
    }

    private boolean handshakeBackingOff() {
        return handshakeWaiting && System.nanoTime() - connectNotBefore < 0;
    }

    private boolean connect() {
        TizenSettings settings = TizenSettings.of(current(), secrets);
        if (!settings.paired()) {
            stopped = true;
            publisher.publish(DeviceState.unpaired());
            return false;
        }
        AtomicReference<TizenRemoteConnection> attempt = new AtomicReference<>();
        TizenRemoteConnection opened = null;
        boolean held = false;
        try {
            opened = TizenRemoteConnection.open(http, device.host(), properties.protocol(), settings.token(),
                    reason -> loop.execute(() -> lost(attempt.get(), reason)));
            attempt.set(opened);
            TizenRemoteConnection.Authorization answer = opened.awaitAuthorization(timings.requestTimeout());
            if (answer == TizenRemoteConnection.Authorization.CONNECTED) {
                handshakeWaiting = false;
                handshakeBackoff.reset();
                held = connection.set(opened);
                if (!held) {
                    return false; // closed while waiting for the TV; the slot closed the channel
                }
                opened.token().filter(token -> !token.equals(settings.token())).ifPresent(this::storeToken);
                opened.requestInstalledApps();
                return true;
            }
            opened.close();
            if (answer == TizenRemoteConnection.Authorization.NO_ANSWER) {
                // A slow-booting TV, or a forgotten token putting the Allow prompt on screen: never unpair
                // for silence. Keep the token and back off so a real prompt does not reappear every poll.
                Duration delay = handshakeBackoff.next();
                handshakeWaiting = true;
                connectNotBefore = System.nanoTime() + delay.toNanos();
                log.info("{} did not answer the connection within {} ms; retrying in {} ms",
                        device.name(), timings.requestTimeout().toMillis(), delay.toMillis());
                publisher.update(state -> state.withStatus(DeviceStatus.DISCONNECTED).withCurrentApp(null));
                return false;
            }
            stopped = true;
            log.warn("{} refused the stored pairing; pair it again on the setup page", device.name());
            publisher.publish(DeviceState.unpaired());
            return false;
        } catch (IOException e) {
            if (held) {
                connection.takeIf(opened); // the slot closes it, unless it was already given up
            } else if (opened != null) {
                opened.close();
            }
            log.debug("{} is not reachable: {}", device.name(), e.getMessage());
            publisher.update(state -> state.withStatus(DeviceStatus.DISCONNECTED).withPower(false).withCurrentApp(null));
            return false;
        }
    }

    private String visibleKnownApp() {
        Optional<TizenRemoteConnection> current = connection.current();
        if (current.isEmpty()) {
            return null;
        }
        for (TizenLaunch.App app : TizenLaunches.knownApps(current.get().installedApps())) {
            if (rest.appVisible(device.host(), app.appId()).orElse(false)) {
                return app.name();
            }
        }
        return null;
    }

    /** A token the TV issued: stored as a device secret, under a new reference if the device has none yet. */
    private void storeToken(String token) {
        String keyRef = TizenSettings.of(current(), secrets).keyRef();
        if (keyRef == null) {
            keyRef = DeviceSecrets.newReference();
            TizenSettings.keys(secrets).store(keyRef, token);
            learned.store(Map.of(TizenSettings.KEY_REF, keyRef));
        } else {
            TizenSettings.keys(secrets).store(keyRef, token);
        }
    }

    /** Runs on the loop. */
    private void lost(TizenRemoteConnection which, String reason) {
        if (which == null || !connection.takeIf(which)) {
            return;
        }
        log.info("Lost the connection to {} ({})", device.name(), reason);
        publisher.update(state -> state.withStatus(DeviceStatus.DISCONNECTED).withPower(false).withCurrentApp(null));
    }

    private TizenRemoteConnection requireConnected() {
        Optional<TizenRemoteConnection> current = connection.current();
        if (current.isPresent()) {
            return current.get();
        }
        if (publisher.current().status() == DeviceStatus.UNPAIRED) {
            throw new DeviceOfflineException(device.name() + " must be paired again before it can be controlled");
        }
        throw DeviceCalls.notConnected(device.name());
    }

    /** The registry's copy: settings (MAC, token) may have changed since this handle was created. */
    private Device current() {
        return registry.findById(device.id()).orElse(device);
    }

    @Override
    public void close() {
        closed = true;
        publisher.close();
        loop.close();
        connection.close();
        onClose.run();
    }
}
