package dev.andre.homecontrol.adapters.webos;

import dev.andre.homecontrol.adapters.net.TextWebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * One SSAP session with an LG TV: the JSON main socket (requests matched to answers by id,
 * subscriptions that keep their id) plus the line-based pointer input socket for buttons,
 * opened on first use. Blocking API; never call it from a subscription callback. Frames are
 * never logged: the registration answer carries the client key.
 */
final class SsapConnection implements AutoCloseable {

    static final String REGISTER_ID = "register_0";

    private static final Logger log = LoggerFactory.getLogger(SsapConnection.class);

    private final HttpClient http;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final Map<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final Map<String, Consumer<JsonNode>> subscriptions = new ConcurrentHashMap<>();
    private final BlockingQueue<JsonNode> registration = new LinkedBlockingQueue<>();
    private final AtomicInteger ids = new AtomicInteger();
    private volatile TextWebSocket socket;
    private volatile String closedReason;
    private TextWebSocket pointer; // guarded by this

    private SsapConnection(HttpClient http, WebOsProperties properties) {
        this.http = http;
        this.connectTimeout = Duration.ofSeconds(properties.connectTimeoutSeconds());
        this.requestTimeout = Duration.ofSeconds(properties.requestTimeoutSeconds());
    }

    /** ws://host:port first, then wss://host:securePort (firmware that closed the plain port or insists on TLS). */
    static SsapConnection open(HttpClient http, String host, WebOsProperties properties, Consumer<String> onClosed)
            throws IOException {
        SsapConnection connection = new SsapConnection(http, properties);
        TextWebSocket.Listener listener = new TextWebSocket.Listener() {
            @Override
            public void onText(String text) {
                connection.dispatch(text);
            }

            @Override
            public void onClosed(String reason) {
                connection.failEverythingWaiting(reason);
                onClosed.accept(reason);
            }
        };
        String authority = host.contains(":") ? "[" + host + "]" : host;
        try {
            connection.socket = TextWebSocket.connect(http,
                    URI.create("ws://" + authority + ":" + properties.port()), connection.connectTimeout, listener);
        } catch (IOException plainFailed) {
            try {
                connection.socket = TextWebSocket.connect(http,
                        URI.create("wss://" + authority + ":" + properties.securePort()), connection.connectTimeout, listener);
            } catch (IOException secureFailed) {
                secureFailed.addSuppressed(plainFailed);
                throw secureFailed;
            }
        }
        return connection;
    }

    /**
     * With a stored key the TV answers {@code registered} at once. Without one it answers
     * {@code response} with {@code pairingType: PROMPT}, shows the prompt, then sends
     * {@code registered} (accepted) or {@code error} (declined). A PROMPT despite a stored key means
     * the TV forgot this client.
     */
    String register(String clientKey, Duration promptTimeout) throws IOException {
        registration.clear();
        socket.send(SsapMessages.register(clientKey));
        JsonNode answer = awaitRegistration(requestTimeout);
        if (answer == null) {
            throw new IOException("The TV did not answer the registration within " + requestTimeout.toSeconds() + " seconds");
        }
        if (type(answer).equals("response") && answer.path("payload").path("pairingType").asString("").equals("PROMPT")) {
            if (clientKey != null) {
                throw new SsapPairingException(SsapPairingException.Reason.KEY_REJECTED,
                        "The TV no longer accepts the stored pairing");
            }
            answer = awaitRegistration(promptTimeout);
            if (answer == null) {
                throw new SsapPairingException(SsapPairingException.Reason.TIMED_OUT,
                        "Nobody answered the prompt on the TV within " + promptTimeout.toSeconds() + " seconds");
            }
        }
        return switch (type(answer)) {
            case "registered" -> {
                String key = answer.path("payload").path("client-key").asString("");
                if (key.isEmpty()) {
                    throw new SsapException("The TV registered this client without a client key");
                }
                yield key;
            }
            case "error" -> throw new SsapPairingException(
                    clientKey == null ? SsapPairingException.Reason.DECLINED : SsapPairingException.Reason.KEY_REJECTED,
                    answer.path("error").asString("The TV refused the registration"));
            case "closed" -> throw new IOException("The TV closed the connection during registration");
            default -> throw new SsapException("Unexpected registration answer of type " + type(answer));
        };
    }

    JsonNode request(String uri, ObjectNode payload) throws IOException {
        return payloadOf(uri, send("req_" + ids.incrementAndGet(), "request", uri, payload));
    }

    /** Sends without waiting; for {@code system/turnOff}, whose answer is unreliable while the TV shuts down. */
    void fire(String uri, ObjectNode payload) throws IOException {
        socket.send(SsapMessages.command("req_" + ids.incrementAndGet(), "request", uri, payload));
    }

    /** {@code onPayload} receives the first answer's payload and every later push for this subscription. */
    void subscribe(String uri, Consumer<JsonNode> onPayload) throws IOException {
        String id = "sub_" + ids.incrementAndGet();
        subscriptions.put(id, onPayload);
        try {
            payloadOf(uri, send(id, "subscribe", uri, SsapMessages.empty()));
        } catch (IOException e) {
            subscriptions.remove(id);
            throw e;
        }
    }

    synchronized void button(String name) throws IOException {
        if (pointer == null || !pointer.isOpen()) {
            String path = request(SsapUris.POINTER_INPUT_SOCKET, SsapMessages.empty()).path("socketPath").asString("");
            if (path.isEmpty()) {
                throw new SsapException("The TV did not offer a pointer input socket");
            }
            pointer = TextWebSocket.connect(http, URI.create(path), connectTimeout, new TextWebSocket.Listener() {
                @Override
                public void onText(String text) {
                }

                @Override
                public void onClosed(String reason) {
                    log.debug("Pointer input socket closed: {}", reason);
                }
            });
        }
        pointer.send(SsapMessages.button(name));
    }

    static JsonNode payloadOf(String uri, JsonNode message) throws SsapException {
        JsonNode payload = message.path("payload");
        if (type(message).equals("error")) {
            String errorText = payload.path("errorText").asString("");
            throw new SsapException(uri + " failed: " + message.path("error").asString("unknown error")
                    + (errorText.isEmpty() ? "" : " (" + errorText + ")"));
        }
        JsonNode returnValue = payload.path("returnValue");
        if (!returnValue.isMissingNode() && !returnValue.asBoolean(true)) {
            throw new SsapException(uri + " failed: " + payload.path("errorText").asString("the TV refused"));
        }
        return payload;
    }

    private JsonNode send(String id, String type, String uri, ObjectNode payload) throws IOException {
        if (closedReason != null) {
            throw new IOException("The TV closed the connection: " + closedReason);
        }
        CompletableFuture<JsonNode> answer = new CompletableFuture<>();
        pending.put(id, answer);
        try {
            socket.send(SsapMessages.command(id, type, uri, payload));
            return answer.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IOException("No answer from the TV to " + uri + " within " + requestTimeout.toSeconds() + " seconds");
        } catch (ExecutionException e) {
            throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for " + uri, e);
        } finally {
            pending.remove(id);
        }
    }

    private JsonNode awaitRegistration(Duration timeout) throws IOException {
        try {
            return registration.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while registering", e);
        }
    }

    private void dispatch(String text) {
        JsonNode message;
        try {
            message = SsapMessages.JSON.readTree(text);
        } catch (JacksonException e) {
            log.debug("Ignoring a message from the TV that is not JSON");
            return;
        }
        String id = message.path("id").asString("");
        if (id.equals(REGISTER_ID)) {
            registration.add(message);
            return;
        }
        CompletableFuture<JsonNode> waiting = pending.remove(id);
        if (waiting != null) {
            waiting.complete(message);
        }
        Consumer<JsonNode> subscriber = subscriptions.get(id);
        if (subscriber != null && type(message).equals("response")) {
            try {
                subscriber.accept(message.path("payload"));
            } catch (RuntimeException e) {
                log.warn("Applying a webOS state update failed", e);
            }
        }
    }

    private void failEverythingWaiting(String reason) {
        closedReason = reason;
        IOException closed = new IOException("The TV closed the connection: " + reason);
        pending.values().forEach(future -> future.completeExceptionally(closed));
        registration.add(SsapMessages.JSON.createObjectNode().put("type", "closed"));
    }

    private static String type(JsonNode message) {
        return message.path("type").asString("");
    }

    @Override
    public synchronized void close() {
        if (pointer != null) {
            pointer.close();
        }
        if (socket != null) {
            socket.close();
        }
    }
}
