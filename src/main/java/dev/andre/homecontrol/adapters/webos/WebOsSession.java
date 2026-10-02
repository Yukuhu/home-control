package dev.andre.homecontrol.adapters.webos;

import dev.andre.homecontrol.adapters.net.DeviceTimeoutException;
import dev.andre.homecontrol.adapters.net.WakeOnLan;
import dev.andre.homecontrol.adapters.support.Backoff;
import dev.andre.homecontrol.adapters.support.ConnectionSlot;
import dev.andre.homecontrol.adapters.support.DeviceCalls;
import dev.andre.homecontrol.adapters.support.LearnedMac;
import dev.andre.homecontrol.adapters.support.PlayPauseToggle;
import dev.andre.homecontrol.adapters.support.Reconnector;
import dev.andre.homecontrol.adapters.support.SessionLoop;
import dev.andre.homecontrol.adapters.support.StatePublisher;
import dev.andre.homecontrol.adapters.support.WakeOnLanPower;
import dev.andre.homecontrol.adapters.webos.protocol.SsapConnection;
import dev.andre.homecontrol.adapters.webos.protocol.SsapException;
import dev.andre.homecontrol.adapters.webos.protocol.SsapMessages;
import dev.andre.homecontrol.adapters.webos.protocol.SsapPairingException;
import dev.andre.homecontrol.adapters.webos.protocol.SsapUris;
import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DeviceRegistry;
import dev.andre.homecontrol.core.DeviceSecrets;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.InputListing;
import dev.andre.homecontrol.core.KeyPress;
import dev.andre.homecontrol.core.LearnedSettings;
import dev.andre.homecontrol.core.RemoteKey;
import dev.andre.homecontrol.core.TvInput;
import dev.andre.homecontrol.core.UnsupportedActionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * One LG webOS TV. Connects with the stored client key, mirrors foreground app, volume and power
 * into {@link DeviceState}, and reconnects with backoff until closed — unless the TV no longer
 * accepts the key: then it is UNPAIRED and only re-pairing helps (connecting again would re-prompt).
 *
 * <p>Settings are read from the registry (the MAC may be typed in while connected) but written only
 * through {@link LearnedSettings}, i.e. by the device package under its registry lock. A client key the TV hands out goes to
 * the device secrets, under the reference the settings already name.
 *
 * <p>Threading: connect, loss and the liveness check run on the session's loop; commands run on the
 * caller's thread against the current connection and never wait for a reconnect; subscription
 * callbacks only update state.
 */
public class WebOsSession implements DeviceHandle, InputListing {

    private static final Logger log = LoggerFactory.getLogger(WebOsSession.class);
    private static final Set<String> STANDBY_STATES = Set.of("Suspend", "Active Standby", "Power Off");

    private final Device device;
    private final WebOsProperties properties;
    private final WebOsTimings timings;
    private final HttpClient http;
    private final DeviceRegistry registry;
    private final DeviceSecrets secrets;
    private final Runnable onClose;
    private final StatePublisher publisher;
    private final SessionLoop loop;
    private final Reconnector reconnector;
    private final ConnectionSlot<SsapConnection> connection;
    private final WakeOnLanPower power;
    private final LearnedMac learnedMac;
    private final PlayPauseToggle playPause = new PlayPauseToggle();

    // Immutable list replaced wholesale on the loop; request threads only read it.
    @SuppressWarnings("java:S3077")
    private volatile List<TvInput> inputs = List.of();
    private volatile boolean closed;

    // Package-private, built only by WebOsAdapter: ten distinct collaborator types, nothing to group.
    @SuppressWarnings("java:S107")
    WebOsSession(Device device, WebOsProperties properties, WebOsTimings timings, HttpClient http, DeviceRegistry registry,
                 LearnedSettings learned, DeviceSecrets secrets, WakeOnLan wakeOnLan, Consumer<DeviceState> onChange,
                 Runnable onClose) {
        this.device = device;
        this.properties = properties;
        this.timings = timings;
        this.http = http;
        this.registry = registry;
        this.secrets = secrets;
        this.onClose = onClose;
        this.publisher = new StatePublisher(device.id(), DeviceState.initial(), onChange);
        this.loop = new SessionLoop("webos-" + device.id());
        this.reconnector = new Reconnector(loop, new Backoff(timings.reconnectInitialDelay(),
                timings.reconnectMaxDelay()), this::connect);
        this.connection = new ConnectionSlot<>("webos-" + device.id());
        this.power = new WakeOnLanPower(device.name(), this::current, WebOsSettings.ADAPTER_ID, wakeOnLan);
        this.learnedMac = new LearnedMac(this::current, WebOsSettings.ADAPTER_ID, learned);
    }

    void start() {
        reconnector.start();
        loop.every(this::checkLiveness, timings.livenessInterval());
    }

    /**
     * SSAP has no heartbeat and the JDK WebSocket does not ping, so a TV that lost power without
     * closing TCP would stay CONNECTED forever. A cheap request every {@link WebOsTimings#livenessInterval()}
     * settles it: any answer (even an error) proves the TV is there; silence past the request
     * timeout means the connection is gone. Runs on the loop, so it never races {@link #connect} or {@link #lost}.
     */
    private void checkLiveness() {
        connection.current().ifPresent(current -> {
            try {
                current.request(SsapUris.SYSTEM_INFO, SsapMessages.empty());
            } catch (DeviceTimeoutException _) {
                lost(current, "no answer to the liveness check");
            } catch (SsapException _) {
                // The TV answered; it is alive even if it refuses this request.
            } catch (IOException e) {
                lost(current, e.getMessage());
            }
        });
    }

    String host() {
        return device.host();
    }

    /** SSDP heard the TV announce itself: skip whatever is left of the backoff. */
    void reconnectNow() {
        reconnector.reconnectNow();
    }

    @Override
    public DeviceState state() {
        return publisher.current();
    }

    @Override
    public List<TvInput> inputs() {
        return inputs;
    }

    @Override
    public void execute(Action action) {
        switch (action) {
            case Action.PressKey(var key, var press) -> pressKey(key, press);
            case Action.OpenAppLink(var uri, _) -> {
                WebOsLaunch launch = WebOsLaunches.forUri(uri);
                call(launch.ssapUri(), launch.payload(), "open " + uri);
            }
            case Action.SelectInput(var inputId) -> call(SsapUris.SWITCH_INPUT,
                    SsapMessages.empty().put("inputId", inputId), "switch to input " + inputId);
            case Action.SetVolume(var level) -> call(SsapUris.SET_VOLUME,
                    SsapMessages.empty().put("volume", Math.clamp(level, 0, 100)), "set the volume");
            case Action.Mute(var muted) -> call(SsapUris.SET_MUTE, SsapMessages.empty().put("mute", muted),
                    muted ? "mute" : "unmute");
            case Action.Stop _ -> call(SsapUris.MEDIA_STOP, SsapMessages.empty(), "stop playback");
            case Action.CastLoad _ -> throw new UnsupportedActionException(device.name() + " is not a Cast receiver");
            case Action.CastMessage _ -> throw new UnsupportedActionException(device.name() + " is not a Cast receiver");
            case Action.PlayMedia _ -> throw new UnsupportedActionException(device.name() + " cannot play a direct stream");
            case Action.Pause _ -> throw new UnsupportedActionException(device.name() + " cannot pause a direct stream");
            case Action.Resume _ -> throw new UnsupportedActionException(device.name() + " cannot resume a direct stream");
            case Action.JoinGroup _ -> throw new UnsupportedActionException(device.name() + " cannot be grouped");
            case Action.LeaveGroup _ -> throw new UnsupportedActionException(device.name() + " cannot be grouped");
        }
    }

    /**
     * The pointer socket has no press-and-hold: a long press sends its button once when it starts
     * and nothing when it ends, so a held key still does something instead of failing.
     */
    private void pressKey(RemoteKey key, KeyPress press) {
        if (press == KeyPress.END_LONG) {
            requireConnected();
            return;
        }
        switch (key) {
            case POWER -> togglePower();
            case VOLUME_UP -> call(SsapUris.VOLUME_UP, SsapMessages.empty(), "raise the volume");
            case VOLUME_DOWN -> call(SsapUris.VOLUME_DOWN, SsapMessages.empty(), "lower the volume");
            case VOLUME_MUTE -> call(SsapUris.SET_MUTE, SsapMessages.empty().put("mute", !publisher.current().muted()),
                    "mute");
            case PLAY_PAUSE -> {
                boolean play = playPause.playNext();
                button(play ? "PLAY" : "PAUSE", play ? "play" : "pause");
            }
            default -> button(WebOsKeys.button(key).orElseThrow(() ->
                    new UnsupportedActionException(device.name() + " has no " + key.label() + " button")),
                    "press " + key.label());
        }
    }

    /** Presses {@code name}; {@code what} says what that does, for "LG TV did not answer … to press home". */
    private void button(String name, String what) {
        SsapConnection current = requireConnected();
        DeviceCalls.run(device.name(), what, () -> current.button(name));
    }

    /** A TV that is reachable but silent fails the command; the liveness check decides whether the connection is gone. */
    private void call(String uri, ObjectNode payload, String what) {
        SsapConnection current = requireConnected();
        DeviceCalls.run(device.name(), what, () -> current.request(uri, payload));
    }

    private void togglePower() {
        // A handle closed by a removal, a re-pair or shutdown has no connection either, but must not wake the TV.
        if (closed) {
            throw DeviceCalls.notConnected(device.name());
        }
        Optional<SsapConnection> current = connection.current();
        if (current.isPresent() && publisher.current().powerOn()) {
            DeviceCalls.run(device.name(), "switch off", () -> current.get().fire(SsapUris.TURN_OFF, SsapMessages.empty()));
            publisher.update(state -> state.withPower(false));
            return;
        }
        power.wake();
        reconnector.retryIn(timings.wakeGrace());
    }

    /** Runs on the loop, through the {@link Reconnector}. */
    private Reconnector.Outcome connect() {
        WebOsSettings settings = WebOsSettings.of(current(), secrets);
        String clientKey = settings.clientKey();
        if (clientKey == null) {
            publisher.publish(DeviceState.unpaired());
            return Reconnector.Outcome.STOP;
        }
        publisher.update(state -> state.withStatus(DeviceStatus.CONNECTING));
        AtomicReference<SsapConnection> attempt = new AtomicReference<>();
        SsapConnection opened = null;
        try {
            opened = SsapConnection.open(http, device.host(), properties.ssap(),
                    reason -> loop.execute(() -> lost(attempt.get(), reason)));
            attempt.set(opened);
            String key = opened.register(clientKey, timings.registerTimeout());
            if (!connection.set(opened)) {
                return Reconnector.Outcome.STOP; // closed while registering; the slot closed the connection
            }
            if (!key.equals(clientKey)) {
                secrets.putDeviceSecret(WebOsSettings.secretName(settings.keyRef()), key); // a key implies a reference
            }
            publisher.update(state -> state.withStatus(DeviceStatus.CONNECTED).withPower(true));
            subscribeToState(opened);
            loadInputs(opened);
            learnMacAddress(opened);
            return Reconnector.Outcome.CONNECTED;
        } catch (SsapPairingException e) {
            closeQuietly(opened);
            log.warn("{} no longer accepts this server ({}); pair it again on the setup page", device.name(), e.getMessage());
            publisher.publish(DeviceState.unpaired());
            return Reconnector.Outcome.STOP;
        } catch (IOException e) {
            closeQuietly(opened);
            log.debug("{} is not reachable: {}", device.name(), e.getMessage());
            publisher.update(state -> state.withStatus(DeviceStatus.DISCONNECTED).withPower(false).withCurrentApp(null));
            return Reconnector.Outcome.RETRY;
        }
    }

    private void subscribeToState(SsapConnection opened) {
        subscribe(opened, SsapUris.FOREGROUND_APP, payload -> {
            String appId = payload.path("appId").asString("");
            publisher.update(state -> state.withCurrentApp(appId.isEmpty() ? null : appId));
        });
        subscribe(opened, SsapUris.GET_VOLUME, payload -> publisher.update(state -> WebOsPayloads.volume(state, payload)));
        subscribe(opened, SsapUris.POWER_STATE, payload -> {
            String powerState = payload.path("state").asString("");
            if (!powerState.isEmpty()) {
                publisher.update(state -> state.withPower(!STANDBY_STATES.contains(powerState)));
            }
        });
    }

    private void subscribe(SsapConnection opened, String uri, Consumer<JsonNode> onPayload) {
        try {
            opened.subscribe(uri, onPayload);
        } catch (IOException e) {
            // Older firmware lacks some services (e.g. tvpower); everything else still works.
            log.debug("{} does not offer {}: {}", device.name(), uri, e.getMessage());
        }
    }

    private void loadInputs(SsapConnection opened) {
        try {
            inputs = WebOsPayloads.inputs(opened.request(SsapUris.EXTERNAL_INPUTS, SsapMessages.empty()));
        } catch (IOException e) {
            log.debug("{} did not list its inputs: {}", device.name(), e.getMessage());
            inputs = List.of();
        }
    }

    private void learnMacAddress(SsapConnection opened) {
        try {
            WebOsPayloads.macAddress(opened.request(SsapUris.CONNECTION_INFO, SsapMessages.empty()), device.host())
                    .ifPresent(learnedMac::offer);
        } catch (IOException e) {
            log.debug("{} did not report its MAC address: {}", device.name(), e.getMessage());
        }
    }

    /** Runs on the loop. */
    private void lost(SsapConnection which, String reason) {
        if (which == null || !connection.takeIf(which)) {
            return;
        }
        inputs = List.of();
        log.info("Lost the connection to {} ({}); reconnecting", device.name(), reason);
        publisher.update(state -> state.withStatus(DeviceStatus.DISCONNECTED).withPower(false).withCurrentApp(null));
        reconnector.lost();
    }

    private SsapConnection requireConnected() {
        Optional<SsapConnection> current = connection.current();
        if (current.isPresent()) {
            return current.get();
        }
        if (publisher.current().status() == DeviceStatus.UNPAIRED) {
            throw new DeviceOfflineException(device.name() + " must be paired again before it can be controlled");
        }
        throw DeviceCalls.notConnected(device.name());
    }

    /** The registry's copy: settings (MAC, key) may have changed since this handle was created. */
    private Device current() {
        return registry.findById(device.id()).orElse(device);
    }

    private static void closeQuietly(SsapConnection opened) {
        if (opened != null) {
            opened.close();
        }
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
