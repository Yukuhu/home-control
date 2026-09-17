package dev.andre.homecontrol.adapters.tizen;

import dev.andre.homecontrol.adapters.net.TextWebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The remote-control channel of one Samsung TV. After opening, the TV decides: a known token (or an
 * Allow on screen) yields {@code ms.channel.connect}, Deny yields {@code ms.channel.unauthorized},
 * and an unanswered prompt yields nothing. Commands are fire-and-forget; the TV does not answer them.
 * Frames are never logged: the connect event carries the token.
 */
final class TizenRemoteConnection implements AutoCloseable {

    enum Authorization { CONNECTED, UNAUTHORIZED, NO_ANSWER }

    private static final Logger log = LoggerFactory.getLogger(TizenRemoteConnection.class);
    private static final Duration LATE_ANSWER = Duration.ofMillis(300);

    private final BlockingQueue<String> channelEvents = new LinkedBlockingQueue<>();
    private volatile TextWebSocket socket;
    private volatile String issuedToken;
    private volatile List<TizenApp> installedApps;

    private TizenRemoteConnection() {
    }

    static TizenRemoteConnection open(HttpClient http, String host, TizenProperties properties, String token,
                                      Consumer<String> onClosed) throws IOException {
        TizenRemoteConnection connection = new TizenRemoteConnection();
        connection.socket = TextWebSocket.connect(http,
                TizenMessages.remoteUri(host, properties.port(), properties.clientName(), token),
                Duration.ofSeconds(properties.connectTimeoutSeconds()),
                new TextWebSocket.Listener() {
                    @Override
                    public void onText(String text) {
                        connection.dispatch(text);
                    }

                    @Override
                    public void onClosed(String reason) {
                        connection.channelEvents.add("closed");
                        onClosed.accept(reason);
                    }
                });
        return connection;
    }

    Authorization awaitAuthorization(Duration timeout) throws IOException {
        try {
            String event = channelEvents.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (event == null) {
                return Authorization.NO_ANSWER;
            }
            if (event.equals("closed")) {
                // A Deny is "unauthorized" immediately followed by a close; the close can be reported first.
                String late = channelEvents.poll(LATE_ANSWER.toMillis(), TimeUnit.MILLISECONDS);
                event = late == null ? event : late;
            }
            return switch (event) {
                case "ms.channel.connect" -> Authorization.CONNECTED;
                case "ms.channel.unauthorized" -> Authorization.UNAUTHORIZED;
                default -> throw new IOException("The TV closed the connection before answering");
            };
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for the TV", e);
        }
    }

    /** A token the TV issued on this connection (only after a fresh Allow). */
    Optional<String> token() {
        return Optional.ofNullable(issuedToken);
    }

    void key(String code) throws IOException {
        socket.send(TizenMessages.key(code));
    }

    /** {@code Press} or {@code Release} of a held key. */
    void key(String code, String command) throws IOException {
        socket.send(TizenMessages.key(code, command));
    }

    void launchApp(String appId, String actionType) throws IOException {
        socket.send(TizenMessages.launchApp(appId, actionType));
    }

    void requestInstalledApps() throws IOException {
        socket.send(TizenMessages.installedAppsRequest());
    }

    /** Empty until the TV answered {@link #requestInstalledApps()}. */
    Optional<List<TizenApp>> installedApps() {
        return Optional.ofNullable(installedApps);
    }

    private void dispatch(String text) {
        JsonNode message;
        try {
            message = TizenMessages.JSON.readTree(text);
        } catch (JacksonException e) {
            log.debug("Ignoring a message from the TV that is not JSON");
            return;
        }
        switch (message.path("event").asString("")) {
            case "ms.channel.connect" -> {
                String token = message.path("data").path("token").asString("");
                if (!token.isEmpty()) {
                    issuedToken = token;
                }
                channelEvents.add("ms.channel.connect");
            }
            case "ms.channel.unauthorized" -> channelEvents.add("ms.channel.unauthorized");
            case "ed.installedApp.get" -> installedApps = TizenMessages.installedApps(message);
            case "ms.error" -> log.debug("The TV reported an error: {}", message.path("data").path("message").asString(""));
            default -> {
                // ed.edenTV.update, ms.voiceApp.hide, ed.apps.launch results, client (dis)connects: not needed.
            }
        }
    }

    @Override
    public void close() {
        if (socket != null) {
            socket.close();
        }
    }
}
