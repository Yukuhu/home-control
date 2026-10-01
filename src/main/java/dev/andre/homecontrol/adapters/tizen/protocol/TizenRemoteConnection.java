package dev.andre.homecontrol.adapters.tizen.protocol;

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
public final class TizenRemoteConnection implements AutoCloseable {

    private static final String CHANNEL_CONNECT_EVENT = "ms.channel.connect";
    private static final String CHANNEL_UNAUTHORIZED_EVENT = "ms.channel.unauthorized";

    public enum Authorization { CONNECTED, UNAUTHORIZED, NO_ANSWER }

    private static final Logger log = LoggerFactory.getLogger(TizenRemoteConnection.class);
    private static final Duration LATE_ANSWER = Duration.ofMillis(300);

    private final TextWebSocket socket;
    private final Channel channel;

    private TizenRemoteConnection(TextWebSocket socket, Channel channel) {
        this.socket = socket;
        this.channel = channel;
    }

      /** Nothing that needs closing exists until the socket is open, so a failed open leaves nothing behind. */
      public static TizenRemoteConnection open(HttpClient http, String host, TizenOptions options, String token,
                                               Consumer<String> onClosed) throws IOException {
          Channel channel = new Channel(onClosed);
          TextWebSocket socket = TextWebSocket.connect(http,
                  TizenMessages.remoteUri(host, options.port(), options.clientName(), token),
                  options.connectTimeout(), channel);
          return new TizenRemoteConnection(socket, channel);
      }
  

    public Authorization awaitAuthorization(Duration timeout) throws IOException {
        try {
            String event = channel.events.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (event == null) {
                return Authorization.NO_ANSWER;
            }
            if (event.equals("closed")) {
                // A Deny is "unauthorized" immediately followed by a close; the close can be reported first.
                String late = channel.events.poll(LATE_ANSWER.toMillis(), TimeUnit.MILLISECONDS);
                event = late == null ? event : late;
            }
            return switch (event) {
                case CHANNEL_CONNECT_EVENT -> Authorization.CONNECTED;
                case CHANNEL_UNAUTHORIZED_EVENT -> Authorization.UNAUTHORIZED;
                default -> throw new IOException("The TV closed the connection before answering");
            };
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for the TV", e);
        }
    }

    /** A token the TV issued on this connection (only after a fresh Allow). */
    public Optional<String> token() {
        return Optional.ofNullable(channel.issuedToken);
    }

    public void key(String code) throws IOException {
        socket.send(TizenMessages.key(code));
    }

    /** {@code Press} or {@code Release} of a held key. */
    public void key(String code, String command) throws IOException {
        socket.send(TizenMessages.key(code, command));
    }

    public void launchApp(String appId, String actionType) throws IOException {
        socket.send(TizenMessages.launchApp(appId, actionType));
    }

    public void requestInstalledApps() throws IOException {
        socket.send(TizenMessages.installedAppsRequest());
    }

    /** Empty until the TV answered {@link #requestInstalledApps()}. */
    public Optional<List<TizenApp>> installedApps() {
        return Optional.ofNullable(channel.installedApps);
    }

    @Override
    public void close() {
        socket.close();
    }

    /** What the TV sends on the channel; its callbacks run on the HTTP client's threads. */
    private static final class Channel implements TextWebSocket.Listener {

        private final BlockingQueue<String> events = new LinkedBlockingQueue<>();
        private final Consumer<String> whenClosed;
        private volatile String issuedToken;
        // Immutable list replaced wholesale on each answer from the TV; the session's threads only read it.
        @SuppressWarnings("java:S3077")
        private volatile List<TizenApp> installedApps;

        private Channel(Consumer<String> whenClosed) {
            this.whenClosed = whenClosed;
        }

        @Override
        public void onText(String text) {
            JsonNode message;
            try {
                message = TizenMessages.JSON.readTree(text);
            } catch (JacksonException _) {
                log.debug("Ignoring a message from the TV that is not JSON");
                return;
            }
            switch (message.path("event").asString("")) {
                case CHANNEL_CONNECT_EVENT -> {
                    String token = message.path("data").path("token").asString("");
                    if (!token.isEmpty()) {
                        issuedToken = token;
                    }
                    events.add(CHANNEL_CONNECT_EVENT);
                }
                case CHANNEL_UNAUTHORIZED_EVENT -> events.add(CHANNEL_UNAUTHORIZED_EVENT);
                case "ed.installedApp.get" -> installedApps = TizenMessages.installedApps(message);
                case "ms.error" -> log.atDebug()
                        .addArgument(() -> message.path("data").path("message").asString(""))
                        .log("The TV reported an error: {}");
                default -> {
                    // ed.edenTV.update, ms.voiceApp.hide, ed.apps.launch results, client (dis)connects: not needed.
                }
            }
        }

        @Override
        public void onClosed(String reason) {
            events.add("closed");
            whenClosed.accept(reason);
        }
    }
}
