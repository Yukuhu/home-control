package dev.andre.homecontrol.adapters.cast;

import dev.andre.homecontrol.adapters.cast.protocol.CastConnection;
import dev.andre.homecontrol.adapters.cast.protocol.CastDisconnectCause;
import dev.andre.homecontrol.adapters.cast.protocol.CastIncoming;
import dev.andre.homecontrol.adapters.cast.protocol.CastPayloads;
import dev.andre.homecontrol.adapters.cast.protocol.CastTimeoutException;
import dev.andre.homecontrol.adapters.cast.protocol.MediaStatus;
import dev.andre.homecontrol.adapters.cast.protocol.ReceiverStatus;
import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.CastAppQuery;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.NowPlaying;
import dev.andre.homecontrol.core.PlaybackState;
import dev.andre.homecontrol.core.UnsupportedActionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static dev.andre.homecontrol.adapters.cast.protocol.CastNamespaces.CONNECTION;
import static dev.andre.homecontrol.adapters.cast.protocol.CastNamespaces.MEDIA;
import static dev.andre.homecontrol.adapters.cast.protocol.CastNamespaces.PLATFORM_RECEIVER_ID;
import static dev.andre.homecontrol.adapters.cast.protocol.CastNamespaces.RECEIVER;

/**
 * One Cast receiver's live connection, shaped like the Android TV session: every mutation of
 * connection and state happens on one scheduler thread; reconnects back off exponentially.
 * Commands run on the caller's thread and fail immediately when they cannot be sent (nothing is
 * queued). The session follows the media channel of whichever app is in front, so casts started
 * from a phone show up as now playing too. Cast has no pairing, so there is no UNPAIRED state.
 */
public class CastSession implements DeviceHandle {

    private static final String RECEIVER_STATUS_TYPE = "RECEIVER_STATUS";
    private static final String REACH_PREFIX = "reach ";
    private static final String STATUS_FIELD = "status";

    private static final Logger log = LoggerFactory.getLogger(CastSession.class);

    private final Device device;
    private final CastSettings settings;
    private final CastProperties properties;
    private final Consumer<DeviceState> onChange;
    private final ScheduledExecutorService scheduler;

    private volatile CastConnection connection;
    /** Incremented per connection attempt; callbacks from older connections are ignored. */
    private final AtomicLong generation = new AtomicLong();
    private volatile DeviceState state = DeviceState.initial();
    private volatile ReceiverStatus receiver;
    /** Transport of the foreground app whose media channel we follow, or null. */
    private volatile String mediaTransportId;
    /** The last media status, so partial statuses (without {@code media}) keep their title. */
    private volatile MediaStatus lastMedia;
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
        long interval = properties.mediaStatusIntervalSeconds();
        scheduler.scheduleWithFixedDelay(this::pollMediaPosition, interval, interval, TimeUnit.SECONDS);
    }

    @Override
    public DeviceState state() {
        return state;
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
     * Receiver-namespace commands are answered with a RECEIVER_STATUS; anything else is a refusal.
     * Runs on the caller's thread: the reply arrives on the reader thread, and the scheduler
     * thread keeps handling state updates meanwhile.
     */
    private void receiverCommand(ObjectNode payload, String what) {
        CastConnection current = requireConnected();
        CastIncoming reply = call(() -> current.request(RECEIVER, PLATFORM_RECEIVER_ID, payload, commandTimeout()), what);
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
        call(() -> {
            current.connect(app.transportId()); // harmless if the media follower already connected
            return null;
        }, REACH_PREFIX + describe(app));
        CastIncoming reply = call(() -> current.request(MEDIA, app.transportId(),
                CastPayloads.load(app.sessionId(), body), loadTimeout()), "load the media");
        if (!"MEDIA_STATUS".equals(reply.type())) {
            throw new ActionFailedException(device.name() + " could not play it (" + reply.describeFailure() + ")");
        }
    }

    private static final Set<String> CUSTOM_ERROR_TYPES = Set.of("error", "connectionerror", "playbackerror");
    /** Receivers validate a custom request synchronously; media loading continues after we return. */
    private static final Duration CUSTOM_MESSAGE_ERROR_WINDOW = Duration.ofMillis(750);

    /** Launch the app unless it runs, wait until it speaks {@code namespace}, connect, send; a quick error reply fails. */
    private void customMessage(String appId, String namespace, Map<String, Object> message) {
        CastConnection current = requireConnected();
        ReceiverStatus.ReceiverApp running = Optional.ofNullable(receiver)
                .flatMap(status -> status.app(appId))
                .orElseGet(() -> launch(current, appId));
        ReceiverStatus.ReceiverApp app = running.speaks(namespace) ? running : awaitNamespace(current, appId, namespace);
        call(() -> {
            current.connect(app.transportId());
            return null;
        }, REACH_PREFIX + describe(app));
        CastConnection.Waiter rejection = current.expect(incoming -> namespace.equals(incoming.namespace())
                && app.transportId().equals(incoming.sourceId())
                && CUSTOM_ERROR_TYPES.contains(incoming.type()));
        try {
            call(() -> {
                current.send(namespace, app.transportId(), CastPayloads.custom(message));
                return null;
            }, "send the request to " + describe(app));
            CastIncoming error;
            try {
                error = rejection.await(CUSTOM_MESSAGE_ERROR_WINDOW);
            } catch (CastTimeoutException _) {
                return; // no rejection: the receiver took the request
            } catch (IOException _) {
                throw new DeviceOfflineException(device.name() + " dropped the connection while starting playback");
            }
            String reason = error.payload().path("message").asString("");
            throw new ActionFailedException(device.name() + " refused to play it (" + (reason.isBlank() ? error.type() : reason) + ")");
        } finally {
            rejection.cancel();
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
        call(() -> {
            current.connect(app.transportId());
            return null;
        }, REACH_PREFIX + describe(app));
        CastConnection.Waiter answer = current.expect(incoming -> query.namespace().equals(incoming.namespace())
                && app.transportId().equals(incoming.sourceId())
                && (query.replyType().equals(incoming.type()) || CUSTOM_ERROR_TYPES.contains(incoming.type())));
        try {
            CastIncoming reply = call(() -> {
                current.send(query.namespace(), app.transportId(), CastPayloads.custom(query.message()));
                return answer.await(commandTimeout());
            }, "answer " + query.replyType());
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
        CastIncoming status = call(() -> {
            try {
                current.send(RECEIVER, PLATFORM_RECEIVER_ID, CastPayloads.getStatus());
                return ready.await(loadTimeout());
            } finally {
                ready.cancel();
            }
        }, "start receiver app " + appId);
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
        CastIncoming reply = call(() -> {
            try {
                current.send(RECEIVER, PLATFORM_RECEIVER_ID, launch);
                return outcome.await(loadTimeout());
            } finally {
                outcome.cancel();
            }
        }, "start receiver app " + appId);
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
        } catch (CastTimeoutException _) {
            throw new ActionFailedException(device.name() + " did not answer in time when asked to " + what);
        } catch (IOException _) {
            throw new DeviceOfflineException(device.name() + " dropped the connection while trying to " + what);
        }
    }

    private Duration commandTimeout() {
        return Duration.ofSeconds(properties.commandTimeoutSeconds());
    }

    private Duration loadTimeout() {
        return Duration.ofSeconds(properties.loadTimeoutSeconds());
    }

    private void connect() {
        if (closed) {
            return;
        }
        long attempt = generation.incrementAndGet();
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
            generation.incrementAndGet(); // anything the failed attempt still reports is stale
            log.debug("Could not reach Cast receiver {} at {}:{}: {}", device.id(), settings.host(), settings.port(), e.getMessage());
            update(state.withStatus(DeviceStatus.DISCONNECTED));
            scheduleReconnect();
        }
    }

    private void onReceiverStatus(ReceiverStatus status) {
        receiver = status;
        Optional<ReceiverStatus.ReceiverApp> foreground = status.foregroundApp();
        update(state.withPower(!status.standBy())
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
            update(state.withNowPlaying(null));
            return;
        }
        CastConnection current = connection;
        if (current == null) {
            return;
        }
        try {
            current.connect(transportId);
            sendMediaGetStatus(current, transportId);
        } catch (IOException e) {
            log.debug("Could not follow media on {}: {}", device.id(), e.getMessage()); // the reader reports the drop
        }
    }

    private void onMediaStatus(List<MediaStatus> statuses) {
        if (statuses.isEmpty()) {
            lastMedia = null;
            update(state.withNowPlaying(null));
            return;
        }
        MediaStatus latest = statuses.getFirst().fillFrom(lastMedia);
        lastMedia = latest;
        PlaybackState playback = latest.playbackState();
        update(state.withNowPlaying(playback == PlaybackState.IDLE ? null
                : new NowPlaying(latest.displayTitle(), playback, latest.currentTime(), latest.duration())));
    }

    /** Receivers only push on changes; ask while playing so the position moves. */
    private void pollMediaPosition() {
        try {
            CastConnection current = connection;
            String transport = mediaTransportId;
            NowPlaying playing = state.nowPlaying();
            if (!closed && current != null && transport != null && playing != null
                    && playing.state() == PlaybackState.PLAYING) {
                sendMediaGetStatus(current, transport);
            }
        } catch (IOException | RuntimeException e) {
            // Never let it escape: a scheduled task that throws is silently never run again.
            log.debug("Media status poll failed for {}: {}", device.id(), e.getMessage());
        }
    }

    /** The reply arrives via Link like any other status; the id only keeps strict receivers happy. */
    private static void sendMediaGetStatus(CastConnection current, String transportId) throws IOException {
        ObjectNode getStatus = CastPayloads.getStatus();
        getStatus.put("requestId", current.nextRequestId());
        current.send(MEDIA, transportId, getStatus);
    }

    private void scheduleReconnect() {
        if (closed) {
            return;
        }
        Duration delay = backoff;
        backoff = Duration.ofSeconds(Math.min(backoff.toSeconds() * 2, properties.reconnectMaxDelaySeconds()));
        try {
            scheduler.schedule(this::connect, delay.toSeconds(), TimeUnit.SECONDS);
        } catch (RejectedExecutionException _) {
            // Closing.
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
        } catch (RuntimeException t) {
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
                update(state.withNowPlaying(null));
            }
        }

        private void handleDisconnect(CastDisconnectCause cause) {
            connection = null;
            receiver = null;
            mediaTransportId = null;
            lastMedia = null;
            log.info("Lost the Cast connection to {} ({}); reconnecting", device.id(), cause);
            update(state.withStatus(DeviceStatus.DISCONNECTED).withNowPlaying(null));
            scheduleReconnect();
        }

        /** Hands a reader-thread callback to the scheduler, dropping it if its connection is outdated. */
        private void runOnScheduler(Runnable task) {
            if (closed) {
                return;
            }
            try {
                scheduler.execute(() -> {
                    if (!closed && attempt == generation.get()) {
                        task.run();
                    }
                });
            } catch (RejectedExecutionException _) {
                // close() shut the scheduler down in between.
            }
        }

        @Override
        public void onMessage(CastIncoming message) {
            runOnScheduler(() -> handle(message));
        }

        @Override
        public void onDisconnected(CastDisconnectCause cause) {
            runOnScheduler(() -> handleDisconnect(cause));
        }
    }
}
