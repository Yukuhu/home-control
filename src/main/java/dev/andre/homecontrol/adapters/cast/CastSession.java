package dev.andre.homecontrol.adapters.cast;

import dev.andre.homecontrol.adapters.cast.protocol.CastApps;
import dev.andre.homecontrol.adapters.cast.protocol.CastConnection;
import dev.andre.homecontrol.adapters.cast.protocol.CastDisconnectCause;
import dev.andre.homecontrol.adapters.cast.protocol.CastIncoming;
import dev.andre.homecontrol.adapters.cast.protocol.CastPayloads;
import dev.andre.homecontrol.adapters.cast.protocol.MediaStatus;
import dev.andre.homecontrol.adapters.cast.protocol.ReceiverStatus;
import dev.andre.homecontrol.adapters.support.Backoff;
import dev.andre.homecontrol.adapters.support.ConnectionSlot;
import dev.andre.homecontrol.adapters.support.DeviceCalls;
import dev.andre.homecontrol.adapters.support.Reconnector;
import dev.andre.homecontrol.adapters.support.SessionLoop;
import dev.andre.homecontrol.adapters.support.StatePublisher;
import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.CastAppQuery;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.DiscoveredDevice;
import dev.andre.homecontrol.core.NowPlaying;
import dev.andre.homecontrol.core.PlaybackState;
import dev.andre.homecontrol.core.ReceiverApps;
import dev.andre.homecontrol.core.UnsupportedActionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import static dev.andre.homecontrol.adapters.cast.protocol.CastNamespaces.CONNECTION;
import static dev.andre.homecontrol.adapters.cast.protocol.CastNamespaces.MEDIA;
import static dev.andre.homecontrol.adapters.cast.protocol.CastNamespaces.PLATFORM_RECEIVER_ID;
import static dev.andre.homecontrol.adapters.cast.protocol.CastNamespaces.RECEIVER;

/**
 * One Cast receiver's live connection. Connection, receiver and media state change only on the session's loop, and
 * the {@link Reconnector} retries with a growing backoff. A connection counts once the receiver has answered: until
 * its first RECEIVER_STATUS, which must come within the command timeout, the attempt stays pending, so a receiver
 * that hangs up at once backs off like one that cannot be reached. Commands run on the caller's thread and fail at
 * once when they cannot be sent (nothing is queued). The session follows the media channel of whichever app is in
 * front, so casts started from a phone show up as now playing too. Cast has no pairing, so there is no UNPAIRED
 * state.
 */
public class CastSession implements DeviceHandle, ReceiverApps {

    private static final String RECEIVER_STATUS_TYPE = "RECEIVER_STATUS";
    private static final String STATUS_FIELD = "status";

    private static final Logger log = LoggerFactory.getLogger(CastSession.class);

    private final Device device;
    private final CastSettings settings;
    private final CastTimings timings;
    private final StatePublisher publisher;
    private final SessionLoop loop;
    private final Reconnector reconnector;
    private final ConnectionSlot<CastConnection> connection;
    /** Ends a connection whose receiver has not answered in time; its own slot, as the Reconnector owns the loop's. */
    private final SessionLoop.Timer firstAnswer;
    private final Runnable onClosed;

    // Immutable record replaced wholesale on the loop; command threads only read it.
    @SuppressWarnings("java:S3077")
    private volatile ReceiverStatus receiver;
    /** Transport of the foreground app whose media channel we follow, or null. */
    private volatile String mediaTransportId;
    /** The last media status, so partial statuses (without {@code media}) keep their title. Loop thread only. */
    private MediaStatus lastMedia;

    public CastSession(Device device, CastProperties properties, Consumer<DeviceState> onChange, Runnable onClosed) {
        this(device, CastTimings.from(properties), onChange, onClosed);
    }

    CastSession(Device device, CastTimings timings, Consumer<DeviceState> onChange) {
        this(device, timings, onChange, () -> { });
    }

    CastSession(Device device, CastTimings timings, Consumer<DeviceState> onChange, Runnable onClosed) {
        this.device = device;
        this.settings = CastSettings.of(device);
        this.timings = timings;
        this.publisher = new StatePublisher(device.id(), DeviceState.initial(), onChange);
        this.loop = new SessionLoop("cast-session-" + device.id());
        this.reconnector = new Reconnector(loop, new Backoff(timings.reconnectInitialDelay(),
                timings.reconnectMaxDelay()), this::connect);
        this.connection = new ConnectionSlot<>("cast-session-" + device.id());
        this.firstAnswer = loop.timer();
        this.onClosed = onClosed;
    }

    public void start() {
        reconnector.start();
        loop.every(this::pollMediaPosition, timings.mediaStatusInterval());
    }

    /** Whether {@code found} is this session's receiver announcing itself. */
    public boolean isAnnouncedBy(DiscoveredDevice found) {
        return CastSettings.isReceiver(settings.castId(), settings.host(), found);
    }

    /** The receiver announced itself, so it may be back: try now instead of waiting out the backoff. */
    public void reconnectNow() {
        reconnector.reconnectNow();
    }

    @Override
    public DeviceState state() {
        return publisher.current();
    }

    @Override
    public void execute(Action action) {
        switch (action) {
            case Action.PressKey _ -> throw new UnsupportedActionException(
                    device.name() + " is a Cast receiver and has no remote keys");
            case Action.OpenAppLink _ -> throw new UnsupportedActionException(
                    device.name() + " is a Cast receiver and cannot open app links");
            case Action.SetVolume(var level) -> receiverCommand(CastPayloads.setVolumeLevel(level / 100.0), "set the volume");
            case Action.Mute(var muted) -> receiverCommand(CastPayloads.setMuted(muted), muted ? "mute" : "unmute");
            case Action.Stop _ -> stopForegroundApp();
            case Action.CastLoad(var receiverAppId, var body) -> load(receiverAppId, body);
            case Action.CastMessage(var receiverAppId, var namespace, var body) -> customMessage(receiverAppId, namespace, body);
            case Action.SelectInput _ -> throw new UnsupportedActionException(
                    device.name() + " is a Cast receiver and has no inputs");
            case Action.PlayMedia _ -> throw new UnsupportedActionException(device.name() + " cannot play a direct stream");
            case Action.Pause _ -> throw new UnsupportedActionException(device.name() + " cannot pause a direct stream");
            case Action.Resume _ -> throw new UnsupportedActionException(device.name() + " cannot resume a direct stream");
            case Action.JoinGroup _ -> throw new UnsupportedActionException(device.name() + " cannot be grouped");
            case Action.LeaveGroup _ -> throw new UnsupportedActionException(device.name() + " cannot be grouped");
        }
    }

    /** The commands of one connection; built per command, so a command never uses a connection a reconnect replaced. */
    private CastApps apps(CastConnection current) {
        return new CastApps(current, timings.commandTimeout(), timings.loadTimeout(), timings.customMessageErrorWindow());
    }

    /** Runs on the caller's thread: the reply arrives on the reader thread, and the loop keeps following state. */
    private void receiverCommand(ObjectNode payload, String what) {
        CastApps apps = apps(requireConnected());
        DeviceCalls.run(device.name(), what, () -> apps.receiverCommand(payload));
    }

    private void stopForegroundApp() {
        requireConnected();
        ReceiverStatus status = receiver;
        if (status == null) {
            throw DeviceCalls.notConnected(device.name()); // the connection dropped just now
        }
        Optional<ReceiverStatus.ReceiverApp> app = status.foregroundApp();
        if (app.isEmpty()) {
            return; // nothing is casting, so it is already stopped
        }
        receiverCommand(CastPayloads.stop(app.get().sessionId()), "stop " + describe(app.get()));
    }

    /** Launch the receiver app unless it already runs, then load the media on its transport. */
    private void load(String appId, Map<String, Object> body) {
        CastApps apps = apps(requireConnected());
        ReceiverStatus.ReceiverApp app = DeviceCalls.run(device.name(), "start receiver app " + appId,
                () -> apps.running(receiver, appId));
        DeviceCalls.run(device.name(), "load the media", () -> apps.load(app, body));
    }

    /** Launch the app unless it runs, wait until it speaks {@code namespace}, send; a quick error reply fails. */
    private void customMessage(String appId, String namespace, Map<String, Object> message) {
        CastApps apps = apps(requireConnected());
        ReceiverStatus.ReceiverApp app = speaking(apps, appId, namespace);
        DeviceCalls.run(device.name(), "start playback", () -> apps.send(app, namespace, message));
    }

    /**
     * Launch the app unless it runs, wait until it speaks the namespace, send, and wait
     * (command timeout) for the reply of the asked type; an error reply fails.
     */
    @Override
    public Map<String, Object> query(CastAppQuery query) {
        CastApps apps = apps(requireConnected());
        ReceiverStatus.ReceiverApp app = speaking(apps, query.receiverAppId(), query.namespace());
        return DeviceCalls.run(device.name(), "answer " + query.replyType(),
                () -> apps.query(app, query.namespace(), query.message(), query.replyType()));
    }

    private ReceiverStatus.ReceiverApp speaking(CastApps apps, String appId, String namespace) {
        return DeviceCalls.run(device.name(), "start receiver app " + appId,
                () -> apps.speaking(apps.running(receiver, appId), namespace));
    }

    /** Receivers may send a blank display name; the app id still tells the user something. */
    private static String describe(ReceiverStatus.ReceiverApp app) {
        if (!app.displayName().isBlank()) {
            return app.displayName();
        }
        return app.appId().isBlank() ? "the current app" : app.appId();
    }

    private CastConnection requireConnected() {
        Optional<CastConnection> current = connection.current();
        if (current.isEmpty() || publisher.current().status() != DeviceStatus.CONNECTED) {
            throw DeviceCalls.notConnected(device.name());
        }
        return current.get();
    }

    /** Runs on the loop, through the {@link Reconnector}. */
    private Reconnector.Outcome connect() {
        publisher.update(state -> state.withStatus(DeviceStatus.CONNECTING));
        Link link = new Link();
        CastConnection opened = null;
        try {
            opened = CastConnection.open(settings.host(), settings.port(),
                    timings.heartbeatInterval(),
                    timings.staleTimeout(),
                    link);
            opened.send(RECEIVER, PLATFORM_RECEIVER_ID, getStatus(opened)); // the reply arrives via Link
            link.own = opened;
            if (!connection.set(opened)) {
                return Reconnector.Outcome.STOP; // closed meanwhile; the slot closed the connection
            }
            firstAnswer.schedule(link::unanswered, timings.commandTimeout());
            return Reconnector.Outcome.PENDING; // connected once the receiver answers
        } catch (IOException e) {
            if (opened != null) {
                opened.close();
            }
            log.debug("Could not reach Cast receiver {} at {}:{}: {}", device.id(), settings.host(), settings.port(), e.getMessage());
            publisher.update(state -> state.withStatus(DeviceStatus.DISCONNECTED));
            return Reconnector.Outcome.RETRY;
        }
    }

    /** Receivers only push on changes; ask while playing so the position moves. Runs on the loop. */
    private void pollMediaPosition() {
        Optional<CastConnection> current = connection.current();
        String transport = mediaTransportId;
        NowPlaying playing = publisher.current().nowPlaying();
        if (current.isEmpty() || transport == null || playing == null || playing.state() != PlaybackState.PLAYING) {
            return;
        }
        try {
            sendMediaGetStatus(current.get(), transport);
        } catch (IOException e) {
            log.debug("Media status poll failed for {}: {}", device.id(), e.getMessage());
        }
    }

    /** The reply arrives via Link like any other status. */
    private static void sendMediaGetStatus(CastConnection current, String transportId) throws IOException {
        current.send(MEDIA, transportId, getStatus(current));
    }

    /** The id only keeps strict receivers happy: the answer is matched by its type. */
    private static ObjectNode getStatus(CastConnection current) {
        ObjectNode getStatus = CastPayloads.getStatus();
        getStatus.put("requestId", current.nextRequestId());
        return getStatus;
    }

    @Override
    public void close() {
        publisher.close();
        loop.close();
        connection.close();
        onClosed.run();
    }

    /** One connection's listener: its callbacks run on the loop, and only while its connection is the current one. */
    private final class Link implements CastConnection.Listener {

        /** Set on the loop before any callback of this connection runs there. */
        private CastConnection own;

        private boolean current() {
            return own != null && connection.current().filter(live -> live == own).isPresent();
        }

        private void handle(CastIncoming message) {
            if (RECEIVER.equals(message.namespace()) && RECEIVER_STATUS_TYPE.equals(message.type())) {
                onReceiverStatus(ReceiverStatus.parse(message.payload().path(STATUS_FIELD)));
            } else if (MEDIA.equals(message.namespace()) && "MEDIA_STATUS".equals(message.type())
                    && message.sourceId().equals(mediaTransportId)) {
                onMediaStatus(MediaStatus.parse(message.payload().path(STATUS_FIELD)));
            } else if (CONNECTION.equals(message.namespace()) && "CLOSE".equals(message.type())
                    && message.sourceId().equals(mediaTransportId)) {
                // The app closed our virtual connection (it stopped); the next RECEIVER_STATUS says what replaced it.
                mediaTransportId = null;
                lastMedia = null;
                publisher.update(state -> state.withNowPlaying(null));
            }
        }

        private void onReceiverStatus(ReceiverStatus status) {
            receiver = status;
            if (publisher.current().status() == DeviceStatus.CONNECTING) {
                firstAnswer.cancel();
                reconnector.connected();
            }
            Optional<ReceiverStatus.ReceiverApp> foreground = status.foregroundApp();
            publisher.update(state -> state.withStatus(DeviceStatus.CONNECTED).withPower(!status.standBy())
                    .withCurrentApp(foreground.map(ReceiverStatus.ReceiverApp::displayName).orElse(null))
                    .withVolume(status.volumePercent(), 100, status.muted()));
            followMedia(foreground.filter(app -> app.speaks(MEDIA)).map(ReceiverStatus.ReceiverApp::transportId).orElse(null));
        }

        /** Subscribes to the media channel of whatever app is in front — including casts started from a phone. */
        private void followMedia(String transportId) {
            if (Objects.equals(transportId, mediaTransportId)) {
                return;
            }
            mediaTransportId = transportId;
            lastMedia = null;
            if (transportId == null) {
                publisher.update(state -> state.withNowPlaying(null));
                return;
            }
            try {
                own.connect(transportId);
                sendMediaGetStatus(own, transportId);
            } catch (IOException e) {
                log.debug("Could not follow media on {}: {}", device.id(), e.getMessage()); // the reader reports the drop
            }
        }

        private void onMediaStatus(List<MediaStatus> statuses) {
            if (statuses.isEmpty()) {
                lastMedia = null;
                publisher.update(state -> state.withNowPlaying(null));
                return;
            }
            MediaStatus latest = statuses.getFirst().fillFrom(lastMedia);
            lastMedia = latest;
            PlaybackState playback = playback(latest.state());
            NowPlaying playing = playback == PlaybackState.IDLE ? null
                    : new NowPlaying(latest.displayTitle(), playback, latest.currentTime(), latest.duration());
            publisher.update(state -> state.withNowPlaying(playing));
        }

        private static PlaybackState playback(MediaStatus.PlayerState state) {
            return switch (state) {
                case PLAYING -> PlaybackState.PLAYING;
                case PAUSED -> PlaybackState.PAUSED;
                case IDLE -> PlaybackState.IDLE;
                case BUFFERING -> PlaybackState.BUFFERING;
            };
        }

        /** The receiver took the connection but never said what it runs: drop it, and back off as after any loss. */
        private void unanswered() {
            if (!current() || publisher.current().status() != DeviceStatus.CONNECTING) {
                return;
            }
            log.debug("Cast receiver {} did not answer its status request; reconnecting", device.id());
            connection.takeIf(own);
            own.close();
            publisher.update(state -> state.withStatus(DeviceStatus.DISCONNECTED));
            reconnector.lost();
        }

        private void handleDisconnect(CastDisconnectCause cause) {
            connection.takeIf(own);
            receiver = null;
            mediaTransportId = null;
            lastMedia = null;
            log.info("Lost the Cast connection to {} ({}); reconnecting", device.id(), cause);
            publisher.update(state -> state.withStatus(DeviceStatus.DISCONNECTED).withNowPlaying(null));
            reconnector.lost();
        }

        /** Hands a reader-thread callback to the loop, which drops it once its connection is outdated. */
        private void onLoop(Runnable task) {
            loop.execute(() -> {
                if (current()) {
                    task.run();
                }
            });
        }

        @Override
        public void onMessage(CastIncoming message) {
            onLoop(() -> handle(message));
        }

        @Override
        public void onDisconnected(CastDisconnectCause cause) {
            onLoop(() -> handleDisconnect(cause));
        }
    }
}
