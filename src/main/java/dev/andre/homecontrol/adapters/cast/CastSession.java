package dev.andre.homecontrol.adapters.cast;

import dev.andre.homecontrol.adapters.cast.protocol.CastConnection;
import dev.andre.homecontrol.adapters.cast.protocol.CastDisconnectCause;
import dev.andre.homecontrol.adapters.cast.protocol.CastIncoming;
import dev.andre.homecontrol.adapters.cast.protocol.CastPayloads;
import dev.andre.homecontrol.adapters.cast.protocol.MediaStatus;
import dev.andre.homecontrol.adapters.cast.protocol.ReceiverStatus;
import dev.andre.homecontrol.adapters.net.DeviceTimeoutException;
import dev.andre.homecontrol.adapters.support.Backoff;
import dev.andre.homecontrol.adapters.support.ConnectionSlot;
import dev.andre.homecontrol.adapters.support.DeviceCalls;
import dev.andre.homecontrol.adapters.support.Reconnector;
import dev.andre.homecontrol.adapters.support.SessionLoop;
import dev.andre.homecontrol.adapters.support.StatePublisher;
import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.CastAppQuery;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
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
import java.util.Set;
import java.util.function.Consumer;

import static dev.andre.homecontrol.adapters.cast.protocol.CastNamespaces.CONNECTION;
import static dev.andre.homecontrol.adapters.cast.protocol.CastNamespaces.MEDIA;
import static dev.andre.homecontrol.adapters.cast.protocol.CastNamespaces.PLATFORM_RECEIVER_ID;
import static dev.andre.homecontrol.adapters.cast.protocol.CastNamespaces.RECEIVER;

/**
 * One Cast receiver's live connection. Connection, receiver and media state change only on the session's loop, and
 * the {@link Reconnector} retries with a growing backoff. Commands run on the caller's thread and fail at once when
 * they cannot be sent (nothing is queued). The session follows the media channel of whichever app is in front, so
 * casts started from a phone show up as now playing too. Cast has no pairing, so there is no UNPAIRED state.
 */
public class CastSession implements DeviceHandle, ReceiverApps {

    private static final String RECEIVER_STATUS_TYPE = "RECEIVER_STATUS";
    private static final String REACH_PREFIX = "reach ";
    private static final String STATUS_FIELD = "status";
    private static final Set<String> CUSTOM_ERROR_TYPES = Set.of("error", "connectionerror", "playbackerror");

    private static final Logger log = LoggerFactory.getLogger(CastSession.class);

    private final Device device;
    private final CastSettings settings;
    private final CastTimings timings;
    private final StatePublisher publisher;
    private final SessionLoop loop;
    private final Reconnector reconnector;
    private final ConnectionSlot<CastConnection> connection;

    // Immutable record replaced wholesale on the loop; command threads only read it.
    @SuppressWarnings("java:S3077")
    private volatile ReceiverStatus receiver;
    /** Transport of the foreground app whose media channel we follow, or null. */
    private volatile String mediaTransportId;
    /** The last media status, so partial statuses (without {@code media}) keep their title. Loop thread only. */
    private MediaStatus lastMedia;

    public CastSession(Device device, CastProperties properties, Consumer<DeviceState> onChange) {
        this(device, CastTimings.from(properties), onChange);
    }

    CastSession(Device device, CastTimings timings, Consumer<DeviceState> onChange) {
        this.device = device;
        this.settings = CastSettings.of(device);
        this.timings = timings;
        this.publisher = new StatePublisher(device.id(), DeviceState.initial(), onChange);
        this.loop = new SessionLoop("cast-session-" + device.id());
        this.reconnector = new Reconnector(loop, new Backoff(timings.reconnectInitialDelay(),
                timings.reconnectMaxDelay()), this::connect);
        this.connection = new ConnectionSlot<>("cast-session-" + device.id());
    }

    public void start() {
        reconnector.start();
        loop.every(this::pollMediaPosition, timings.mediaStatusInterval());
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

    /**
     * Receiver-namespace commands are answered with a RECEIVER_STATUS; anything else is a refusal. Runs on the
     * caller's thread: the reply arrives on the reader thread, and the loop keeps handling state updates meanwhile.
     */
    private void receiverCommand(ObjectNode payload, String what) {
        CastConnection current = requireConnected();
        CastIncoming reply = DeviceCalls.run(device.name(), what,
                () -> current.request(RECEIVER, PLATFORM_RECEIVER_ID, payload, timings.commandTimeout()));
        if (!RECEIVER_STATUS_TYPE.equals(reply.type())) {
            throw new ActionFailedException(device.name() + " refused to " + what + " (" + reply.describeFailure() + ")");
        }
    }

    private void stopForegroundApp() {
        requireConnected();
        ReceiverStatus status = receiver;
        Optional<ReceiverStatus.ReceiverApp> app = status == null ? Optional.empty() : status.foregroundApp();
        if (app.isEmpty()) {
            return; // nothing is casting, so it is already stopped
        }
        receiverCommand(CastPayloads.stop(app.get().sessionId()), "stop " + describe(app.get()));
    }

    /** Launch the receiver app unless it already runs, connect to its transport, LOAD. */
    private void load(String appId, Map<String, Object> body) {
        CastConnection current = requireConnected();
        ReceiverStatus.ReceiverApp app = Optional.ofNullable(receiver)
                .flatMap(status -> status.app(appId))
                .orElseGet(() -> launch(current, appId));
        // Harmless if the media follower already connected.
        DeviceCalls.run(device.name(), REACH_PREFIX + describe(app), () -> current.connect(app.transportId()));
        CastIncoming reply = DeviceCalls.run(device.name(), "load the media", () -> current.request(MEDIA,
                app.transportId(), CastPayloads.load(app.sessionId(), body), timings.loadTimeout()));
        if (!"MEDIA_STATUS".equals(reply.type())) {
            throw new ActionFailedException(device.name() + " could not play it (" + reply.describeFailure() + ")");
        }
    }

    /** Launch the app unless it runs, wait until it speaks {@code namespace}, connect, send; a quick error reply fails. */
    private void customMessage(String appId, String namespace, Map<String, Object> message) {
        CastConnection current = requireConnected();
        ReceiverStatus.ReceiverApp running = Optional.ofNullable(receiver)
                .flatMap(status -> status.app(appId))
                .orElseGet(() -> launch(current, appId));
        ReceiverStatus.ReceiverApp app = running.speaks(namespace) ? running : awaitNamespace(current, appId, namespace);
        DeviceCalls.run(device.name(), REACH_PREFIX + describe(app), () -> current.connect(app.transportId()));
        CastConnection.Waiter rejection = current.expect(incoming -> namespace.equals(incoming.namespace())
                && app.transportId().equals(incoming.sourceId())
                && CUSTOM_ERROR_TYPES.contains(incoming.type()));
        try {
            DeviceCalls.run(device.name(), "send the request to " + describe(app),
                    () -> current.send(namespace, app.transportId(), CastPayloads.custom(message)));
            Optional<CastIncoming> error = DeviceCalls.run(device.name(), "start playback", () -> awaitRejection(rejection));
            if (error.isEmpty()) {
                return; // no rejection: the receiver took the request
            }
            String reason = error.get().payload().path("message").asString("");
            throw new ActionFailedException(device.name() + " refused to play it ("
                    + (reason.isBlank() ? error.get().type() : reason) + ")");
        } finally {
            rejection.cancel();
        }
    }

    /** Receivers check a custom request at once: silence through the error window means they took it. */
    private Optional<CastIncoming> awaitRejection(CastConnection.Waiter rejection) throws IOException {
        try {
            return Optional.of(rejection.await(timings.customMessageErrorWindow()));
        } catch (DeviceTimeoutException _) {
            return Optional.empty();
        }
    }

    /**
     * Launch the app unless it runs, wait until it speaks the namespace, connect, send, and wait
     * (command timeout) for the reply of the asked type; an error reply fails.
     */
    @Override
    public Map<String, Object> query(CastAppQuery query) {
        CastConnection current = requireConnected();
        ReceiverStatus.ReceiverApp running = Optional.ofNullable(receiver)
                .flatMap(status -> status.app(query.receiverAppId()))
                .orElseGet(() -> launch(current, query.receiverAppId()));
        ReceiverStatus.ReceiverApp app = running.speaks(query.namespace())
                ? running : awaitNamespace(current, query.receiverAppId(), query.namespace());
        DeviceCalls.run(device.name(), REACH_PREFIX + describe(app), () -> current.connect(app.transportId()));
        CastConnection.Waiter answer = current.expect(incoming -> query.namespace().equals(incoming.namespace())
                && app.transportId().equals(incoming.sourceId())
                && (query.replyType().equals(incoming.type()) || CUSTOM_ERROR_TYPES.contains(incoming.type())));
        try {
            CastIncoming reply = DeviceCalls.run(device.name(), "answer " + query.replyType(), () -> {
                current.send(query.namespace(), app.transportId(), CastPayloads.custom(query.message()));
                return answer.await(timings.commandTimeout());
            });
            if (!query.replyType().equals(reply.type())) {
                String reason = reply.payload().path("message").asString("");
                throw new ActionFailedException(device.name() + " refused the request ("
                        + (reason.isBlank() ? reply.type() : reason) + ")");
            }
            return CastPayloads.toMap(reply.payload());
        } finally {
            answer.cancel();
        }
    }

    /** A freshly launched custom receiver announces its namespaces in a later RECEIVER_STATUS. */
    private ReceiverStatus.ReceiverApp awaitNamespace(CastConnection current, String appId, String namespace) {
        CastConnection.Waiter ready = current.expect(incoming -> RECEIVER.equals(incoming.namespace())
                && RECEIVER_STATUS_TYPE.equals(incoming.type())
                && ReceiverStatus.parse(incoming.payload().path(STATUS_FIELD)).app(appId)
                        .filter(candidate -> candidate.speaks(namespace)).isPresent());
        CastIncoming status = DeviceCalls.run(device.name(), "start receiver app " + appId, () -> {
            try {
                current.send(RECEIVER, PLATFORM_RECEIVER_ID, CastPayloads.getStatus());
                return ready.await(timings.loadTimeout());
            } finally {
                ready.cancel();
            }
        });
        return ReceiverStatus.parse(status.payload().path(STATUS_FIELD)).app(appId).orElseThrow();
    }

    private ReceiverStatus.ReceiverApp launch(CastConnection current, String appId) {
        int requestId = current.nextRequestId();
        ObjectNode launch = CastPayloads.launch(appId);
        launch.put("requestId", requestId);
        // The reply to LAUNCH can be a RECEIVER_STATUS still showing the previous app; wait for
        // the status that lists ours, or for an error answering our request.
        CastConnection.Waiter outcome = current.expect(message -> RECEIVER.equals(message.namespace())
                && ((RECEIVER_STATUS_TYPE.equals(message.type())
                        && ReceiverStatus.parse(message.payload().path(STATUS_FIELD)).app(appId).isPresent())
                    || (message.requestId() == requestId && !RECEIVER_STATUS_TYPE.equals(message.type()))));
        CastIncoming reply = DeviceCalls.run(device.name(), "start receiver app " + appId, () -> {
            try {
                current.send(RECEIVER, PLATFORM_RECEIVER_ID, launch);
                return outcome.await(timings.loadTimeout());
            } finally {
                outcome.cancel();
            }
        });
        if (!RECEIVER_STATUS_TYPE.equals(reply.type())) {
            throw new ActionFailedException(device.name() + " could not start receiver app " + appId
                    + " (" + reply.describeFailure() + ")");
        }
        return ReceiverStatus.parse(reply.payload().path(STATUS_FIELD)).app(appId).orElseThrow();
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
            opened.send(RECEIVER, PLATFORM_RECEIVER_ID, CastPayloads.getStatus()); // the reply arrives via Link
            link.own = opened;
            if (!connection.set(opened)) {
                return Reconnector.Outcome.STOP; // closed meanwhile; the slot closed the connection
            }
            publisher.update(state -> state.withStatus(DeviceStatus.CONNECTED));
            return Reconnector.Outcome.CONNECTED;
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

    /** The reply arrives via Link like any other status; the id only keeps strict receivers happy. */
    private static void sendMediaGetStatus(CastConnection current, String transportId) throws IOException {
        ObjectNode getStatus = CastPayloads.getStatus();
        getStatus.put("requestId", current.nextRequestId());
        current.send(MEDIA, transportId, getStatus);
    }

    @Override
    public void close() {
        publisher.close();
        loop.close();
        connection.close();
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
            Optional<ReceiverStatus.ReceiverApp> foreground = status.foregroundApp();
            publisher.update(state -> state.withPower(!status.standBy())
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
            PlaybackState playback = latest.playbackState();
            NowPlaying playing = playback == PlaybackState.IDLE ? null
                    : new NowPlaying(latest.displayTitle(), playback, latest.currentTime(), latest.duration());
            publisher.update(state -> state.withNowPlaying(playing));
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
