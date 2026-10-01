package dev.andre.homecontrol.adapters.cast.protocol;

import dev.andre.homecontrol.adapters.net.DeviceTimeoutException;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static dev.andre.homecontrol.adapters.cast.protocol.CastNamespaces.MEDIA;
import static dev.andre.homecontrol.adapters.cast.protocol.CastNamespaces.PLATFORM_RECEIVER_ID;
import static dev.andre.homecontrol.adapters.cast.protocol.CastNamespaces.RECEIVER;

/**
 * What a sender does with receiver apps over one {@link CastConnection}: start them, wait until a custom one speaks
 * its namespace, and exchange receiver, media and custom messages with them. Every call blocks its caller; none may
 * run on the connection's reader thread. No answer in time is a {@link DeviceTimeoutException}; an answer that says
 * no is a {@link CastRefusedException} carrying the receiver's reason.
 */
public final class CastApps {

    private static final String RECEIVER_STATUS = "RECEIVER_STATUS";
    private static final String STATUS_FIELD = "status";
    private static final Set<String> CUSTOM_ERROR_TYPES = Set.of("error", "connectionerror", "playbackerror");

    private final CastConnection connection;
    private final Duration commandTimeout;
    private final Duration loadTimeout;
    private final Duration errorWindow;

    /**
     * {@code errorWindow}: receivers check a custom request at once, and loading goes on after the request returns,
     * so a rejection that has not arrived within it counts as accepted.
     */
    public CastApps(CastConnection connection, Duration commandTimeout, Duration loadTimeout, Duration errorWindow) {
        this.connection = connection;
        this.commandTimeout = commandTimeout;
        this.loadTimeout = loadTimeout;
        this.errorWindow = errorWindow;
    }

    /** A receiver-namespace command, answered with a RECEIVER_STATUS; any other answer is a refusal. */
    public void receiverCommand(ObjectNode payload) throws IOException {
        CastIncoming reply = connection.request(RECEIVER, PLATFORM_RECEIVER_ID, payload, commandTimeout);
        if (!RECEIVER_STATUS.equals(reply.type())) {
            throw new CastRefusedException(reply.describeFailure());
        }
    }

    /** The app as {@code known} lists it, or else launched. {@code known} may be null. */
    public ReceiverStatus.ReceiverApp running(ReceiverStatus known, String appId) throws IOException {
        Optional<ReceiverStatus.ReceiverApp> listed = known == null ? Optional.empty() : known.app(appId);
        return listed.isPresent() ? listed.get() : launch(appId);
    }

    /** {@code app} once it lists {@code namespace}: a freshly launched custom receiver announces it in a later status. */
    public ReceiverStatus.ReceiverApp speaking(ReceiverStatus.ReceiverApp app, String namespace) throws IOException {
        if (app.speaks(namespace)) {
            return app;
        }
        String appId = app.appId();
        CastConnection.Waiter ready = connection.expect(incoming -> RECEIVER.equals(incoming.namespace())
                && RECEIVER_STATUS.equals(incoming.type())
                && ReceiverStatus.parse(incoming.payload().path(STATUS_FIELD)).app(appId)
                        .filter(candidate -> candidate.speaks(namespace)).isPresent());
        CastIncoming status;
        try {
            connection.send(RECEIVER, PLATFORM_RECEIVER_ID, CastPayloads.getStatus());
            status = ready.await(loadTimeout);
        } finally {
            ready.cancel();
        }
        return ReceiverStatus.parse(status.payload().path(STATUS_FIELD)).app(appId).orElseThrow();
    }

    /** Connects to the app's transport and loads media there; any answer but MEDIA_STATUS is a refusal. */
    public void load(ReceiverStatus.ReceiverApp app, Map<String, Object> body) throws IOException {
        connection.connect(app.transportId()); // harmless if the media follower already connected
        CastIncoming reply = connection.request(MEDIA, app.transportId(), CastPayloads.load(app.sessionId(), body),
                loadTimeout);
        if (!"MEDIA_STATUS".equals(reply.type())) {
            throw new CastRefusedException(reply.describeFailure());
        }
    }

    /** Sends a custom message; an error answer within the error window is a refusal, silence is acceptance. */
    public void send(ReceiverStatus.ReceiverApp app, String namespace, Map<String, Object> message) throws IOException {
        connection.connect(app.transportId());
        CastConnection.Waiter rejection = connection.expect(incoming -> namespace.equals(incoming.namespace())
                && app.transportId().equals(incoming.sourceId())
                && CUSTOM_ERROR_TYPES.contains(incoming.type()));
        try {
            connection.send(namespace, app.transportId(), CastPayloads.custom(message));
            CastIncoming error;
            try {
                error = rejection.await(errorWindow);
            } catch (DeviceTimeoutException _) {
                return; // no rejection: the receiver took the request
            }
            throw new CastRefusedException(reason(error));
        } finally {
            rejection.cancel();
        }
    }

    /** Sends a query and waits (command timeout) for the reply of {@code replyType}; an error reply is a refusal. */
    public Map<String, Object> query(ReceiverStatus.ReceiverApp app, String namespace, Map<String, Object> message,
                                     String replyType) throws IOException {
        connection.connect(app.transportId());
        CastConnection.Waiter answer = connection.expect(incoming -> namespace.equals(incoming.namespace())
                && app.transportId().equals(incoming.sourceId())
                && (replyType.equals(incoming.type()) || CUSTOM_ERROR_TYPES.contains(incoming.type())));
        try {
            connection.send(namespace, app.transportId(), CastPayloads.custom(message));
            CastIncoming reply = answer.await(commandTimeout);
            if (!replyType.equals(reply.type())) {
                throw new CastRefusedException(reason(reply));
            }
            return CastPayloads.toMap(reply.payload());
        } finally {
            answer.cancel();
        }
    }

    private ReceiverStatus.ReceiverApp launch(String appId) throws IOException {
        int requestId = connection.nextRequestId();
        ObjectNode launch = CastPayloads.launch(appId);
        launch.put("requestId", requestId);
        // The reply to LAUNCH can be a RECEIVER_STATUS still showing the previous app; wait for
        // the status that lists ours, or for an error answering our request.
        CastConnection.Waiter outcome = connection.expect(message -> RECEIVER.equals(message.namespace())
                && ((RECEIVER_STATUS.equals(message.type())
                        && ReceiverStatus.parse(message.payload().path(STATUS_FIELD)).app(appId).isPresent())
                    || (message.requestId() == requestId && !RECEIVER_STATUS.equals(message.type()))));
        CastIncoming reply;
        try {
            connection.send(RECEIVER, PLATFORM_RECEIVER_ID, launch);
            reply = outcome.await(loadTimeout);
        } finally {
            outcome.cancel();
        }
        if (!RECEIVER_STATUS.equals(reply.type())) {
            throw new CastRefusedException(reply.describeFailure());
        }
        return ReceiverStatus.parse(reply.payload().path(STATUS_FIELD)).app(appId).orElseThrow();
    }

    private static String reason(CastIncoming reply) {
        String reason = reply.payload().path("message").asString("");
        return reason.isBlank() ? reply.type() : reason;
    }
}
