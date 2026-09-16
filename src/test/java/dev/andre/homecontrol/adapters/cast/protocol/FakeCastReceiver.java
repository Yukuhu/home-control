package dev.andre.homecontrol.adapters.cast.protocol;

import dev.andre.homecontrol.adapters.cast.protocol.channel.CastMessage;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An in-process Cast receiver speaking enough CASTV2 for the sender (spec §12): virtual
 * connections, heartbeat, receiver status, volume, LAUNCH/STOP, and a Default-Media-Receiver
 * media channel (LOAD, GET_STATUS, PAUSE, PLAY, STOP). One sender connection at a time.
 * Every non-heartbeat message it receives is recorded for assertions.
 */
public class FakeCastReceiver implements AutoCloseable {

    public static final String BACKDROP_APP_ID = "E8C28D3C";
    public static final String DEFAULT_MEDIA_RECEIVER = CastNamespaces.DEFAULT_MEDIA_RECEIVER_APP_ID;

    private record App(String appId, String displayName, String sessionId, String transportId,
                       boolean idleScreen, boolean speaksMedia) {
    }

    private record Media(long mediaSessionId, String contentId, String contentType, String title,
                         double duration, String playerState, double currentTime) {
        Media with(String state, double time) {
            return new Media(mediaSessionId, contentId, contentType, title, duration, state, time);
        }
    }

    private final SSLServerSocket serverSocket;
    private final List<CastIncoming> received = new CopyOnWriteArrayList<>();
    private final Set<String> virtualConnections = ConcurrentHashMap.newKeySet();
    private final Set<String> ignoredTypes = ConcurrentHashMap.newKeySet();
    private final Set<String> refusedApps = ConcurrentHashMap.newKeySet();
    private final AtomicInteger connections = new AtomicInteger();
    private final AtomicInteger pings = new AtomicInteger();
    private final AtomicInteger pongs = new AtomicInteger();
    private final AtomicInteger sessions = new AtomicInteger();

    private volatile double volumeLevel = 0.5;
    private volatile boolean muted;
    private volatile App app = backdrop();
    private volatile Media media;
    private volatile boolean silent;
    private volatile boolean failNextLoad;
    private volatile SSLSocket socket;
    private volatile CastFraming framing;
    private volatile OutputStream rawOut;
    private volatile boolean closed;

    public FakeCastReceiver() throws Exception {
        this(0);
    }

    /** A fixed port lets a test bring a "rebooted" receiver back where the sender expects it. */
    public FakeCastReceiver(int port) throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(selfSignedKeyManagers(), null, new SecureRandom());
        serverSocket = (SSLServerSocket) context.getServerSocketFactory().createServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress("127.0.0.1", port));
        Thread.ofVirtual().name("fake-cast-receiver").start(this::serve);
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    // ---- scripting ----

    public void setVolume(double level, boolean isMuted) {
        volumeLevel = level;
        muted = isMuted;
    }

    /** Puts an app in front as if another sender had launched it; it speaks the media namespace. */
    public void runApp(String appId, String displayName) {
        int n = sessions.incrementAndGet();
        app = new App(appId, displayName, "session-" + n, "transport-" + n, false, true);
        media = null;
    }

    /** Media loaded by someone else; requires {@link #runApp}. */
    public void startMedia(String title, String playerState, double currentTime) {
        media = new Media(1, "http://media.invalid/" + title, "video/mp4", title, 596.5, playerState, currentTime);
    }

    public void setMediaState(String playerState, double currentTime) {
        media = media.with(playerState, currentTime);
    }

    public void pushReceiverStatus() throws IOException {
        send(CastNamespaces.RECEIVER, CastNamespaces.PLATFORM_RECEIVER_ID, "*", receiverStatus(0));
    }

    public void pushMediaStatus(boolean includeMedia) throws IOException {
        send(CastNamespaces.MEDIA, app.transportId(), "*", mediaStatus(0, includeMedia));
    }

    public void ping() throws IOException {
        send(CastNamespaces.HEARTBEAT, CastNamespaces.PLATFORM_RECEIVER_ID, CastNamespaces.SENDER_ID, CastPayloads.ping());
    }

    /** Stop answering anything (heartbeats included) while keeping the socket open. */
    public void goSilent() {
        silent = true;
    }

    public void resume() {
        silent = false;
    }

    /** Record but never answer messages of this type. */
    public void ignore(String type) {
        ignoredTypes.add(type);
    }

    public void refuseLaunch(String appId) {
        refusedApps.add(appId);
    }

    public void failNextLoad() {
        failNextLoad = true;
    }

    /** A string message whose payload is not JSON, as a broken receiver might send. */
    public void sendUnreadablePayload(String namespace) throws IOException {
        CastFraming current = framing;
        if (current == null) {
            throw new IOException("No sender is connected to the fake receiver");
        }
        current.write(CastMessage.newBuilder()
                .setProtocolVersion(CastMessage.ProtocolVersion.CASTV2_1_0)
                .setSourceId(CastNamespaces.PLATFORM_RECEIVER_ID)
                .setDestinationId("*")
                .setNamespace(namespace)
                .setPayloadType(CastMessage.PayloadType.STRING)
                .setPayloadUtf8("{not json")
                .build());
    }

    /** A frame header announcing more than {@link CastFraming#MAX_MESSAGE_BYTES}; no body follows. */
    public void sendOversizedFrameHeader() throws IOException {
        CastFraming current = framing;
        OutputStream out = rawOut;
        if (current == null || out == null) {
            throw new IOException("No sender is connected to the fake receiver");
        }
        synchronized (current) { // CastFraming.write locks the same monitor: no interleaving
            out.write(ByteBuffer.allocate(4).putInt(CastFraming.MAX_MESSAGE_BYTES + 1).array());
            out.flush();
        }
    }

    public void dropConnection() throws IOException {
        SSLSocket current = socket;
        if (current != null) {
            current.close();
        }
    }

    // ---- observation ----

    public int connections() {
        return connections.get();
    }

    public int pings() {
        return pings.get();
    }

    public int pongs() {
        return pongs.get();
    }

    public Set<String> virtualConnections() {
        return Set.copyOf(virtualConnections);
    }

    public List<CastIncoming> received(String namespace, String type) {
        return received.stream()
                .filter(message -> message.namespace().equals(namespace) && message.type().equals(type))
                .toList();
    }

    public Optional<CastIncoming> last(String namespace, String type) {
        List<CastIncoming> matching = received(namespace, type);
        return matching.isEmpty() ? Optional.empty() : Optional.of(matching.getLast());
    }

    public double volumeLevel() {
        return volumeLevel;
    }

    public boolean muted() {
        return muted;
    }

    public String runningAppId() {
        return app.appId();
    }

    // ---- protocol ----

    private void serve() {
        while (!closed) {
            try (SSLSocket accepted = (SSLSocket) serverSocket.accept()) {
                connections.incrementAndGet();
                socket = accepted;
                rawOut = accepted.getOutputStream();
                framing = new CastFraming(accepted.getInputStream(), rawOut);
                CastMessage message;
                while ((message = framing.read()) != null) {
                    handle(message);
                }
            } catch (IOException | RuntimeException e) {
                // The sender hung up, the test dropped the connection, or the fake is closing.
            } finally {
                virtualConnections.clear();
            }
        }
    }

    private void handle(CastMessage message) throws IOException {
        CastIncoming incoming = new CastIncoming(message.getNamespace(), message.getSourceId(),
                message.getDestinationId(), CastPayloads.parse(message.getPayloadUtf8()));
        String type = incoming.type();
        if (CastNamespaces.HEARTBEAT.equals(incoming.namespace())) {
            if ("PING".equals(type)) {
                pings.incrementAndGet();
                if (!silent) {
                    reply(incoming, CastPayloads.pong());
                }
            } else if ("PONG".equals(type)) {
                pongs.incrementAndGet();
            }
            return;
        }
        received.add(incoming);
        if (silent || ignoredTypes.contains(type)) {
            return;
        }
        switch (incoming.namespace()) {
            case CastNamespaces.CONNECTION -> {
                if ("CONNECT".equals(type)) {
                    virtualConnections.add(incoming.destinationId());
                } else if ("CLOSE".equals(type)) {
                    virtualConnections.remove(incoming.destinationId());
                }
            }
            case CastNamespaces.RECEIVER -> receiver(incoming, type, incoming.requestId());
            case CastNamespaces.MEDIA -> media(incoming, type, incoming.requestId());
            default -> {
            }
        }
    }

    private void receiver(CastIncoming in, String type, int requestId) throws IOException {
        JsonNode payload = in.payload();
        switch (type) {
            case "GET_STATUS" -> reply(in, receiverStatus(requestId));
            case "SET_VOLUME" -> {
                JsonNode volume = payload.path("volume");
                if (volume.has("level")) {
                    volumeLevel = volume.path("level").asDouble(volumeLevel);
                }
                if (volume.has("muted")) {
                    muted = volume.path("muted").asBoolean(muted);
                }
                reply(in, receiverStatus(requestId));
            }
            case "LAUNCH" -> {
                String appId = payload.path("appId").asString("");
                if (refusedApps.contains(appId)) {
                    reply(in, error("LAUNCH_ERROR", requestId, "NOT_FOUND"));
                    return;
                }
                int n = sessions.incrementAndGet();
                app = new App(appId, DEFAULT_MEDIA_RECEIVER.equals(appId) ? "Default Media Receiver" : "App " + appId,
                        "session-" + n, "transport-" + n, false, true);
                media = null;
                reply(in, receiverStatus(requestId));
            }
            case "STOP" -> {
                if (!app.sessionId().equals(payload.path("sessionId").asString(""))) {
                    reply(in, error("INVALID_REQUEST", requestId, "INVALID_SESSION_ID"));
                    return;
                }
                String stoppedTransport = app.transportId();
                app = backdrop();
                media = null;
                send(CastNamespaces.CONNECTION, stoppedTransport, in.sourceId(), CastPayloads.close());
                reply(in, receiverStatus(requestId));
            }
            default -> reply(in, error("INVALID_REQUEST", requestId, "INVALID_COMMAND"));
        }
    }

    private void media(CastIncoming in, String type, int requestId) throws IOException {
        App current = app;
        if (!current.speaksMedia() || !current.transportId().equals(in.destinationId())
                || !virtualConnections.contains(in.destinationId())) {
            return; // a real receiver drops messages for transports the sender has not connected to
        }
        JsonNode payload = in.payload();
        switch (type) {
            case "LOAD" -> {
                if (failNextLoad) {
                    failNextLoad = false;
                    reply(in, error("LOAD_FAILED", requestId, null));
                    return;
                }
                JsonNode loaded = payload.path("media");
                long id = media == null ? 1 : media.mediaSessionId() + 1;
                String title = loaded.path("metadata").has("title")
                        ? loaded.path("metadata").path("title").asString("") : null;
                media = new Media(id, loaded.path("contentId").asString(""), loaded.path("contentType").asString(""),
                        title, 596.5, "PLAYING", payload.path("currentTime").asDouble(0.0));
                reply(in, mediaStatus(requestId, true));
            }
            case "GET_STATUS" -> reply(in, mediaStatus(requestId, true));
            case "PAUSE" -> {
                if (media != null) {
                    media = media.with("PAUSED", media.currentTime());
                }
                reply(in, mediaStatus(requestId, false));
            }
            case "PLAY" -> {
                if (media != null) {
                    media = media.with("PLAYING", media.currentTime());
                }
                reply(in, mediaStatus(requestId, false));
            }
            case "STOP" -> {
                media = null;
                reply(in, mediaStatus(requestId, false));
            }
            default -> reply(in, error("INVALID_REQUEST", requestId, "INVALID_COMMAND"));
        }
    }

    private ObjectNode receiverStatus(int requestId) {
        ObjectNode payload = message("RECEIVER_STATUS", requestId);
        ObjectNode status = payload.putObject("status");
        App current = app;
        ObjectNode application = status.putArray("applications").addObject();
        application.put("appId", current.appId());
        application.put("displayName", current.displayName());
        application.put("isIdleScreen", current.idleScreen());
        application.put("sessionId", current.sessionId());
        application.put("statusText", current.idleScreen() ? "" : "Ready To Cast");
        application.put("transportId", current.transportId());
        ArrayNode namespaces = application.putArray("namespaces");
        if (current.speaksMedia()) {
            namespaces.addObject().put("name", CastNamespaces.MEDIA);
        }
        status.put("isActiveInput", true);
        status.put("isStandBy", false);
        ObjectNode volume = status.putObject("volume");
        volume.put("controlType", "attenuation");
        volume.put("level", volumeLevel);
        volume.put("muted", muted);
        volume.put("stepInterval", 0.05);
        return payload;
    }

    private ObjectNode mediaStatus(int requestId, boolean includeMedia) {
        ObjectNode payload = message("MEDIA_STATUS", requestId);
        ArrayNode status = payload.putArray("status");
        Media current = media;
        if (current != null) {
            ObjectNode entry = status.addObject();
            entry.put("mediaSessionId", current.mediaSessionId());
            entry.put("playbackRate", 1);
            entry.put("playerState", current.playerState());
            entry.put("currentTime", current.currentTime());
            entry.put("supportedMediaCommands", 274447);
            if ("IDLE".equals(current.playerState())) {
                entry.put("idleReason", "FINISHED");
            }
            if (includeMedia) {
                ObjectNode loaded = entry.putObject("media");
                loaded.put("contentId", current.contentId());
                loaded.put("contentType", current.contentType());
                loaded.put("streamType", "BUFFERED");
                loaded.put("duration", current.duration());
                ObjectNode metadata = loaded.putObject("metadata");
                metadata.put("metadataType", 0);
                if (current.title() != null) {
                    metadata.put("title", current.title());
                }
            }
        }
        return payload;
    }

    private static ObjectNode error(String type, int requestId, String reason) {
        ObjectNode payload = message(type, requestId);
        if (reason != null) {
            payload.put("reason", reason);
        }
        return payload;
    }

    private static ObjectNode message(String type, int requestId) {
        ObjectNode payload = (ObjectNode) CastPayloads.parse("{}");
        payload.put("type", type);
        payload.put("requestId", requestId);
        return payload;
    }

    private static App backdrop() {
        return new App(BACKDROP_APP_ID, "Backdrop", "backdrop-session", "backdrop-transport", true, false);
    }

    private void reply(CastIncoming to, ObjectNode payload) throws IOException {
        send(to.namespace(), to.destinationId(), to.sourceId(), payload);
    }

    private void send(String namespace, String sourceId, String destinationId, ObjectNode payload) throws IOException {
        CastFraming current = framing;
        if (current == null) {
            throw new IOException("No sender is connected to the fake receiver");
        }
        current.write(CastMessage.newBuilder()
                .setProtocolVersion(CastMessage.ProtocolVersion.CASTV2_1_0)
                .setSourceId(sourceId)
                .setDestinationId(destinationId)
                .setNamespace(namespace)
                .setPayloadType(CastMessage.PayloadType.STRING)
                .setPayloadUtf8(CastPayloads.toJson(payload))
                .build());
    }

    private static KeyManager[] selfSignedKeyManagers() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, new SecureRandom());
        KeyPair keyPair = generator.generateKeyPair();
        X500Name subject = new X500Name("CN=fake-cast-receiver");
        Instant now = Instant.now();
        X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(
                new JcaX509v3CertificateBuilder(subject, new BigInteger(64, new SecureRandom()),
                        Date.from(now.minus(Duration.ofDays(1))), Date.from(now.plus(Duration.ofDays(1))),
                        subject, keyPair.getPublic())
                        .build(new JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.getPrivate())));
        char[] password = "fake".toCharArray();
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry("receiver", keyPair.getPrivate(), password, new Certificate[]{certificate});
        KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(keyStore, password);
        return factory.getKeyManagers();
    }

    @Override
    public void close() throws IOException {
        closed = true;
        serverSocket.close();
        dropConnection();
    }
}
