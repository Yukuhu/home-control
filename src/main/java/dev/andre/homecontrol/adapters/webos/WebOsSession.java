package dev.andre.homecontrol.adapters.webos;

import dev.andre.homecontrol.adapters.net.WakeOnLan;
import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DeviceRegistry;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.InputListing;
import dev.andre.homecontrol.core.KeyPress;
import dev.andre.homecontrol.core.LearnedSettings;
import dev.andre.homecontrol.core.RemoteKey;
import dev.andre.homecontrol.core.TvInput;
import dev.andre.homecontrol.core.UnsupportedActionException;
import dev.andre.homecontrol.core.WakeOnLanAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * One LG webOS TV. Connects with the stored client key, mirrors foreground app, volume and power
 * into {@link DeviceState}, and reconnects with backoff until closed — unless the TV no longer
 * accepts the key: then it is UNPAIRED and only re-pairing helps (connecting again would re-prompt).
 *
 * <p>Settings are read from the registry (the MAC may be typed in while connected) but written only
 * through {@link LearnedSettings}, i.e. by the device manager under its lock.
 *
 * <p>Threading: connect, loss and reconnect run on one scheduler thread; commands run on the
 * caller's thread against the current connection and never wait for a reconnect; subscription
 * callbacks only update state.
 */
public class WebOsSession implements DeviceHandle, InputListing {

    private static final Logger log = LoggerFactory.getLogger(WebOsSession.class);
    private static final Set<String> STANDBY_STATES = Set.of("Suspend", "Active Standby", "Power Off");

    private final Device device;
    private final WebOsProperties properties;
    private final HttpClient http;
    private final DeviceRegistry registry;
    private final LearnedSettings learned;
    private final WakeOnLan wakeOnLan;
    private final Consumer<DeviceState> onChange;
    private final Runnable onClose;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean nextPlayPauseIsPlay = new AtomicBoolean();

    private volatile SsapConnection connection;
    private volatile DeviceState state = DeviceState.initial();
    private volatile List<TvInput> inputs = List.of();
    private volatile boolean closed;
    private Duration backoff;                   // scheduler thread only
    private ScheduledFuture<?> pendingConnect;  // scheduler thread only

    WebOsSession(Device device, WebOsProperties properties, HttpClient http, DeviceRegistry registry,
                 LearnedSettings learned, WakeOnLan wakeOnLan, Consumer<DeviceState> onChange, Runnable onClose) {
        this.device = device;
        this.properties = properties;
        this.http = http;
        this.registry = registry;
        this.learned = learned;
        this.wakeOnLan = wakeOnLan;
        this.onChange = onChange;
        this.onClose = onClose;
        this.backoff = initialBackoff();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("webos-" + device.id()).factory());
    }

    void start() {
        onScheduler(this::connect);
        long interval = properties.livenessIntervalSeconds();
        try {
            scheduler.scheduleWithFixedDelay(this::checkLiveness, interval, interval, TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            // Closed before it started.
        }
    }

    /**
     * SSAP has no heartbeat and the JDK WebSocket does not ping, so a TV that lost power without
     * closing TCP would stay CONNECTED forever. A cheap request every {@code livenessIntervalSeconds}
     * settles it: any answer (even an error) proves the TV is there; silence past the request
     * timeout means the connection is gone. Runs on the scheduler thread, so it never races
     * {@link #connect} or {@link #lost}.
     */
    private void checkLiveness() {
        SsapConnection current = connection;
        if (current == null || closed) {
            return;
        }
        try {
            current.request(SsapUris.SYSTEM_INFO, SsapMessages.empty());
        } catch (SsapTimeoutException e) {
            lost(current, "no answer to the liveness check");
        } catch (SsapException e) {
            // The TV answered; it is alive even if it refuses this request.
        } catch (IOException e) {
            lost(current, e.getMessage());
        } catch (RuntimeException e) {
            // Never let an exception cancel the fixed-delay schedule.
            log.warn("Checking the connection to {} failed", device.name(), e);
        }
    }

    String host() {
        return device.host();
    }

    /** SSDP heard the TV announce itself: skip whatever is left of the backoff. */
    void reconnectNow() {
        onScheduler(() -> {
            if (connection == null && state.status() != DeviceStatus.UNPAIRED) {
                backoff = initialBackoff();
                connect();
            }
        });
    }

    @Override
    public DeviceState state() {
        return state;
    }

    @Override
    public List<TvInput> inputs() {
        return inputs;
    }

    @Override
    public void execute(Action action) {
        switch (action) {
            case Action.PressKey press -> pressKey(press.key(), press.press());
            case Action.OpenAppLink open -> {
                WebOsLaunch launch = WebOsLaunches.forUri(open.uri());
                call(launch.ssapUri(), launch.payload(), "open " + open.uri());
            }
            case Action.SelectInput select -> call(SsapUris.SWITCH_INPUT,
                    SsapMessages.empty().put("inputId", select.inputId()), "switch to input " + select.inputId());
            case Action.SetVolume volume -> call(SsapUris.SET_VOLUME,
                    SsapMessages.empty().put("volume", Math.clamp(volume.level(), 0, 100)), "set the volume");
            case Action.Mute mute -> call(SsapUris.SET_MUTE, SsapMessages.empty().put("mute", mute.muted()),
                    mute.muted() ? "mute" : "unmute");
            case Action.Stop ignored -> call(SsapUris.MEDIA_STOP, SsapMessages.empty(), "stop playback");
            case Action.CastLoad ignored -> throw new UnsupportedActionException(device.name() + " is not a Cast receiver");
            case Action.CastMessage ignored -> throw new UnsupportedActionException(device.name() + " is not a Cast receiver");
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
            case VOLUME_MUTE -> call(SsapUris.SET_MUTE, SsapMessages.empty().put("mute", !state.muted()), "mute");
            case PLAY_PAUSE -> button(nextPlayPauseIsPlay.getAndSet(!nextPlayPauseIsPlay.get()) ? "PLAY" : "PAUSE");
            default -> button(WebOsKeys.button(key).orElseThrow(() ->
                    new UnsupportedActionException(device.name() + " has no " + key + " button")));
        }
    }

    private void button(String name) {
        SsapConnection current = requireConnected();
        try {
            current.button(name);
        } catch (SsapTimeoutException e) {
            throw new ActionFailedException(device.name() + " did not answer in time when asked to press " + name);
        } catch (SsapException e) {
            throw new ActionFailedException(device.name() + " refused the " + name + " button: " + e.getMessage());
        } catch (IOException e) {
            throw new DeviceOfflineException(device.name() + " dropped the connection");
        }
    }

    private void call(String uri, ObjectNode payload, String what) {
        SsapConnection current = requireConnected();
        try {
            current.request(uri, payload);
        } catch (SsapTimeoutException e) {
            // Reachable but silent is a refusal (502), not an offline device; the liveness check
            // decides separately whether the whole connection is gone.
            throw new ActionFailedException(device.name() + " did not answer in time when asked to " + what);
        } catch (SsapException e) {
            throw new ActionFailedException(device.name() + " could not " + what + ": " + e.getMessage());
        } catch (IOException e) {
            throw new DeviceOfflineException(device.name() + " dropped the connection");
        }
    }

    private void togglePower() {
        SsapConnection current = connection;
        if (current != null && state.powerOn()) {
            try {
                current.fire(SsapUris.TURN_OFF, SsapMessages.empty());
            } catch (IOException e) {
                throw new DeviceOfflineException(device.name() + " dropped the connection");
            }
            update(s -> s.withPower(false));
            return;
        }
        String mac = current().adapterSettings(WebOsSettings.ADAPTER_ID).get(WakeOnLanAdapter.MAC_ADDRESS);
        if (mac == null || mac.isBlank()) {
            throw new DeviceOfflineException(device.name() + " is off and no MAC address is known for Wake-on-LAN;"
                    + " switch it on once by hand or enter its MAC address on the setup page");
        }
        try {
            wakeOnLan.wake(mac);
        } catch (IOException | IllegalArgumentException e) {
            throw new DeviceOfflineException("Could not send the Wake-on-LAN packet: " + e.getMessage());
        }
        onScheduler(() -> {
            cancelPendingConnect();
            backoff = initialBackoff();
            pendingConnect = scheduler.schedule(this::connect, properties.wakeGraceSeconds(), TimeUnit.SECONDS);
        });
    }

    private void connect() {
        cancelPendingConnect();
        if (closed || connection != null) {
            return;
        }
        String clientKey = WebOsSettings.of(current()).clientKey();
        if (clientKey == null) {
            update(ignored -> DeviceState.unpaired());
            return;
        }
        update(s -> s.withStatus(DeviceStatus.CONNECTING));
        AtomicReference<SsapConnection> attempt = new AtomicReference<>();
        SsapConnection opened = null;
        try {
            opened = SsapConnection.open(http, device.host(), properties,
                    reason -> onScheduler(() -> lost(attempt.get(), reason)));
            attempt.set(opened);
            String key = opened.register(clientKey, Duration.ofSeconds(properties.requestTimeoutSeconds()));
            connection = opened;
            if (closed) {
                // close() ran while registering and saw no connection to close.
                connection = null;
                opened.close();
                return;
            }
            backoff = initialBackoff();
            if (!key.equals(clientKey)) {
                learned.store(Map.of(WebOsSettings.CLIENT_KEY, key));
            }
            update(s -> s.withStatus(DeviceStatus.CONNECTED).withPower(true));
            subscribeToState(opened);
            loadInputs(opened);
            learnMacAddress(opened);
        } catch (SsapPairingException e) {
            closeQuietly(opened);
            log.warn("{} no longer accepts this server ({}); pair it again on the setup page", device.name(), e.getMessage());
            update(ignored -> DeviceState.unpaired());
        } catch (IOException e) {
            closeQuietly(opened);
            log.debug("{} is not reachable: {}", device.name(), e.getMessage());
            update(s -> s.withStatus(DeviceStatus.DISCONNECTED).withPower(false).withCurrentApp(null));
            scheduleReconnect();
        }
    }

    private void subscribeToState(SsapConnection opened) {
        subscribe(opened, SsapUris.FOREGROUND_APP, payload -> {
            String appId = payload.path("appId").asString("");
            update(s -> s.withCurrentApp(appId.isEmpty() ? null : appId));
        });
        subscribe(opened, SsapUris.GET_VOLUME, payload -> update(s -> WebOsPayloads.volume(s, payload)));
        subscribe(opened, SsapUris.POWER_STATE, payload -> {
            String power = payload.path("state").asString("");
            if (!power.isEmpty()) {
                update(s -> s.withPower(!STANDBY_STATES.contains(power)));
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
                    .ifPresent(mac -> {
                        WebOsSettings settings = WebOsSettings.of(current());
                        if (!settings.macAddressManual() && !mac.equals(settings.macAddress())) {
                            learned.store(Map.of(WakeOnLanAdapter.MAC_ADDRESS, mac));
                        }
                    });
        } catch (IOException e) {
            log.debug("{} did not report its MAC address: {}", device.name(), e.getMessage());
        }
    }

    private void lost(SsapConnection which, String reason) {
        if (which == null || which != connection) {
            return;
        }
        connection = null;
        which.close();
        inputs = List.of();
        log.info("Lost the connection to {} ({}); reconnecting", device.name(), reason);
        update(s -> s.withStatus(DeviceStatus.DISCONNECTED).withPower(false).withCurrentApp(null));
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (closed) {
            return;
        }
        Duration delay = backoff;
        backoff = Duration.ofSeconds(Math.min(Math.max(1, backoff.toSeconds() * 2), properties.reconnectMaxDelaySeconds()));
        try {
            pendingConnect = scheduler.schedule(this::connect, delay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // Closed meanwhile.
        }
    }

    private void cancelPendingConnect() {
        if (pendingConnect != null) {
            pendingConnect.cancel(false);
            pendingConnect = null;
        }
    }

    private SsapConnection requireConnected() {
        SsapConnection current = connection;
        if (current != null) {
            return current;
        }
        if (state.status() == DeviceStatus.UNPAIRED) {
            throw new DeviceOfflineException(device.name() + " must be paired again before it can be controlled");
        }
        throw new DeviceOfflineException(device.name() + " is not connected");
    }

    /** The registry's copy: settings (MAC, key) may have changed since this handle was created. */
    private Device current() {
        return registry.findById(device.id()).orElse(device);
    }

    /** Publishes only visible changes; entering CONNECTING is always published so every attempt shows. */
    private synchronized void update(UnaryOperator<DeviceState> change) {
        if (closed) {
            return;
        }
        DeviceState next = change.apply(state);
        if (next.sameIgnoringTime(state) && next.status() != DeviceStatus.CONNECTING) {
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
            scheduler.execute(() -> {
                if (!closed) {
                    task.run();
                }
            });
        } catch (RejectedExecutionException e) {
            // Closed meanwhile.
        }
    }

    private Duration initialBackoff() {
        return Duration.ofSeconds(properties.reconnectInitialDelaySeconds());
    }

    private static void closeQuietly(SsapConnection opened) {
        if (opened != null) {
            opened.close();
        }
    }

    @Override
    public void close() {
        closed = true;
        scheduler.shutdownNow();
        SsapConnection current = connection;
        connection = null;
        closeQuietly(current);
        onClose.run();
    }
}
