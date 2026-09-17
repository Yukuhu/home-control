package dev.andre.homecontrol.adapters.webos;

import dev.andre.homecontrol.adapters.net.FakeWebSocketServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** An in-process LG TV: SSAP main socket, pointer input socket, the answers of a webOS 5 set. */
public class FakeSsapServer implements AutoCloseable {

    public enum Prompt { ACCEPT, DECLINE, IGNORE }

    public static final String CLIENT_KEY = "5f1c0d7e2b9a4c3d8e7f6a5b4c3d2e1f";
    static final String POINTER_PATH = "/resources/3c1f9a/netinput.pointer.sock";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> INSTALLED = Set.of("youtube.leanback.v4", "netflix", "amazon", "com.webos.app.home");

    private record Subscription(FakeWebSocketServer.Connection connection, String id) {
    }

    private final FakeWebSocketServer server;
    private final BlockingQueue<JsonNode> requests = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> buttons = new LinkedBlockingQueue<>();
    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();
    private final AtomicInteger registrations = new AtomicInteger();
    private final Set<String> ignored = ConcurrentHashMap.newKeySet();
    private final List<FakeWebSocketServer.Connection> mainConnections = new CopyOnWriteArrayList<>();
    private volatile Prompt prompt = Prompt.ACCEPT;
    private volatile String foregroundApp = "com.webos.app.home";
    private volatile int volume = 12;
    private volatile boolean muted;

    public FakeSsapServer(boolean tls) throws IOException {
        FakeWebSocketServer.Handler handler = new FakeWebSocketServer.Handler() {
            @Override
            public void onOpen(FakeWebSocketServer.Connection connection) {
                if (!POINTER_PATH.equals(connection.path())) {
                    mainConnections.add(connection);
                }
            }

            @Override
            public void onText(FakeWebSocketServer.Connection connection, String text) {
                if (POINTER_PATH.equals(connection.path())) {
                    buttons.add(text);
                } else {
                    onMain(connection, text);
                }
            }
        };
        server = tls ? FakeWebSocketServer.tls(handler) : FakeWebSocketServer.plain(handler);
    }

    /** Requests for {@code uri} are still recorded but never answered: a TV that hangs on one service. */
    public void ignoreRequests(String uri) {
        ignored.add(uri);
    }

    public void answerRequests(String uri) {
        ignored.remove(uri);
    }

    /** Sends {@code text} as-is on every main connection opened so far (closed ones fail silently). */
    public void sendRaw(String text) {
        mainConnections.forEach(connection -> connection.send(text));
    }

    public int port() {
        return server.port();
    }

    public int connections() {
        return server.connections();
    }

    public int registrations() {
        return registrations.get();
    }

    public void setPrompt(Prompt answer) {
        prompt = answer;
    }

    public void refuseConnections(boolean refuse) {
        server.refuseConnections(refuse);
    }

    public void dropConnections() {
        server.dropAll();
    }

    /** The next request or subscribe message for {@code uri}, skipping others; null after 5 s. */
    public JsonNode nextRequest(String uri) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        for (long left = deadline - System.nanoTime(); left > 0; left = deadline - System.nanoTime()) {
            JsonNode message = requests.poll(left, TimeUnit.NANOSECONDS);
            if (message != null && uri.equals(message.path("uri").asString(""))) {
                return message;
            }
        }
        return null;
    }

    public String nextButton() throws InterruptedException {
        return buttons.poll(5, TimeUnit.SECONDS);
    }

    public void changeForegroundApp(String appId) {
        foregroundApp = appId;
        push(SsapUris.FOREGROUND_APP, foregroundPayload());
    }

    public void pushVolume(int level, boolean mute) {
        volume = level;
        muted = mute;
        push(SsapUris.GET_VOLUME, volumePayload());
    }

    private void onMain(FakeWebSocketServer.Connection connection, String text) {
        JsonNode message = JSON.readTree(text);
        String type = message.path("type").asString("");
        String id = message.path("id").asString("");
        if (type.equals("register")) {
            register(connection, message.path("payload").path("client-key").asString(""));
            return;
        }
        if (!type.equals("request") && !type.equals("subscribe")) {
            return;
        }
        requests.add(message);
        String uri = message.path("uri").asString("");
        if (type.equals("subscribe")) {
            subscriptions.put(uri, new Subscription(connection, id));
        }
        if (ignored.contains(uri)) {
            return;
        }
        respond(connection, id, uri, message.path("payload"));
    }

    private void register(FakeWebSocketServer.Connection connection, String key) {
        registrations.incrementAndGet();
        if (CLIENT_KEY.equals(key)) {
            connection.send(registered());
            return;
        }
        connection.send("{\"type\":\"response\",\"id\":\"register_0\",\"payload\":{\"pairingType\":\"PROMPT\",\"returnValue\":true}}");
        switch (prompt) {
            case ACCEPT -> connection.send(registered());
            case DECLINE -> connection.send(
                    "{\"type\":\"error\",\"id\":\"register_0\",\"error\":\"403 User denied access\",\"payload\":{}}");
            case IGNORE -> {
            }
        }
    }

    private void respond(FakeWebSocketServer.Connection connection, String id, String uri, JsonNode payload) {
        switch (uri) {
            case SsapUris.FOREGROUND_APP -> connection.send(response(id, foregroundPayload()));
            case SsapUris.GET_VOLUME -> connection.send(response(id, volumePayload()));
            case SsapUris.POWER_STATE -> connection.send(
                    response(id, "{\"returnValue\":true,\"state\":\"Active\",\"subscribed\":true}"));
            case SsapUris.POINTER_INPUT_SOCKET -> connection.send(
                    response(id, "{\"returnValue\":true,\"socketPath\":\"" + server.url(POINTER_PATH) + "\"}"));
            case SsapUris.EXTERNAL_INPUTS -> connection.send(response(id, fixture("external-inputs.json")));
            case SsapUris.CONNECTION_INFO -> connection.send(response(id, fixture("connection-info.json")));
            case SsapUris.SYSTEM_INFO -> connection.send(
                    response(id, "{\"returnValue\":true,\"modelName\":\"OLED55C9PLA\"}"));
            case SsapUris.LAUNCH -> launch(connection, id, payload.path("id").asString(""));
            case SsapUris.OPEN -> {
                connection.send(response(id, "{\"returnValue\":true,\"id\":\"com.webos.app.browser\"}"));
                changeForegroundApp("com.webos.app.browser");
            }
            case SsapUris.SET_VOLUME -> {
                ok(connection, id);
                pushVolume(payload.path("volume").asInt(volume), muted);
            }
            case SsapUris.SET_MUTE -> {
                ok(connection, id);
                pushVolume(volume, payload.path("mute").asBoolean(muted));
            }
            case SsapUris.VOLUME_UP -> {
                ok(connection, id);
                pushVolume(volume + 1, muted);
            }
            case SsapUris.VOLUME_DOWN -> {
                ok(connection, id);
                pushVolume(volume - 1, muted);
            }
            case SsapUris.TURN_OFF, SsapUris.SWITCH_INPUT, SsapUris.MEDIA_STOP -> ok(connection, id);
            default -> connection.send("{\"type\":\"error\",\"id\":\"" + id
                    + "\",\"error\":\"404 no such service or method\",\"payload\":{}}");
        }
    }

    private void launch(FakeWebSocketServer.Connection connection, String id, String appId) {
        if (!INSTALLED.contains(appId)) {
            connection.send("{\"type\":\"error\",\"id\":\"" + id + "\",\"error\":\"500 Application error\","
                    + "\"payload\":{\"returnValue\":false,\"errorCode\":-101,\"errorText\":\"\\\"" + appId
                    + "\\\" was not found OR Unsupported Application Type\"}}");
            return;
        }
        connection.send(response(id, "{\"returnValue\":true,\"id\":\"" + appId + "\",\"sessionId\":\"c2Vzc2lvbg==\"}"));
        changeForegroundApp(appId);
    }

    private String foregroundPayload() {
        return "{\"subscribed\":true,\"appId\":\"" + foregroundApp + "\",\"returnValue\":true,\"windowId\":\"\",\"processId\":\"\"}";
    }

    private String volumePayload() {
        return "{\"returnValue\":true,\"subscribed\":true,\"volumeStatus\":{\"activeStatus\":true,\"adjustVolume\":true,"
                + "\"maxVolume\":100,\"muteStatus\":" + muted + ",\"volume\":" + volume
                + ",\"mode\":\"normal\",\"soundOutput\":\"tv_speaker\"},\"callerId\":\"secondscreen.client\"}";
    }

    private void push(String uri, String payload) {
        Subscription subscription = subscriptions.get(uri);
        if (subscription != null) {
            subscription.connection().send(response(subscription.id(), payload));
        }
    }

    private static void ok(FakeWebSocketServer.Connection connection, String id) {
        connection.send(response(id, "{\"returnValue\":true}"));
    }

    private static String registered() {
        return "{\"type\":\"registered\",\"id\":\"register_0\",\"payload\":{\"client-key\":\"" + CLIENT_KEY + "\"}}";
    }

    private static String response(String id, String payload) {
        return "{\"type\":\"response\",\"id\":\"" + id + "\",\"payload\":" + payload + "}";
    }

    private static String fixture(String name) {
        try {
            return Files.readString(Path.of("src/test/resources/fixtures/webos/" + name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        server.close();
    }
}
