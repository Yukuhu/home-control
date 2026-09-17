package dev.andre.homecontrol.adapters.tizen;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.andre.homecontrol.adapters.net.FakeWebSocketServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * An in-process Samsung TV: the TLS remote-control WebSocket, the REST API and DIAL (REST and DIAL
 * share one HTTP port here; point both {@code restPort} and {@code dialPort} at {@link #httpPort()}).
 */
public class FakeTizenServer implements AutoCloseable {

    public enum Authorization { ALLOW, DENY, IGNORE }

    public static final String TOKEN = "73184052";
    public static final String YOUTUBE = "111299001912";
    public static final String NETFLIX = "3201907018807";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final FakeWebSocketServer remote;
    private final HttpServer http;
    private final BlockingQueue<String> keys = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> commands = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> launches = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> dialBodies = new LinkedBlockingQueue<>();
    private final List<String> queries = new CopyOnWriteArrayList<>();
    private final List<FakeWebSocketServer.Connection> openConnections = new CopyOnWriteArrayList<>();
    private final Map<String, Boolean> visible = new ConcurrentHashMap<>(Map.of(YOUTUBE, false, NETFLIX, false));
    private volatile Authorization authorization = Authorization.ALLOW;
    private volatile String powerState = "on";
    private volatile boolean restAvailable = true;
    private volatile boolean dialAvailable = true;
    private volatile boolean issueTokens = true;
    private volatile int deviceInfoPadding;

    public FakeTizenServer() throws IOException {
        remote = FakeWebSocketServer.tls(new FakeWebSocketServer.Handler() {
            @Override
            public void onOpen(FakeWebSocketServer.Connection connection) {
                open(connection);
            }

            @Override
            public void onText(FakeWebSocketServer.Connection connection, String text) {
                message(connection, text);
            }
        });
        http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        http.createContext("/api/v2/", this::rest);
        http.createContext("/ws/apps/", this::dial);
        http.start();
    }

    public int port() {
        return remote.port();
    }

    public int httpPort() {
        return http.getAddress().getPort();
    }

    public int connections() {
        return remote.connections();
    }

    public List<String> queries() {
        return List.copyOf(queries);
    }

    public void setAuthorization(Authorization answer) {
        authorization = answer;
    }

    public void setPowerState(String state) {
        powerState = state;
    }

    public void setRestAvailable(boolean available) {
        restAvailable = available;
    }

    public void setDialAvailable(boolean available) {
        dialAvailable = available;
    }

    /** Older firmware accepts clients without ever issuing a token. */
    public void setIssueTokens(boolean issue) {
        issueTokens = issue;
    }

    /** Trailing whitespace after the device info JSON: still valid JSON, but as large as asked. */
    public void setDeviceInfoPadding(int bytes) {
        deviceInfoPadding = bytes;
    }

    public void setVisible(String appId, boolean isVisible) {
        visible.put(appId, isVisible);
    }

    public void dropConnections() {
        remote.dropAll();
    }

    /** Standby without network standby: REST and the WebSocket are both gone. */
    public void switchOff() {
        restAvailable = false;
        remote.refuseConnections(true);
    }

    public void switchOn() {
        restAvailable = true;
        remote.refuseConnections(false);
    }

    public String nextKey() throws InterruptedException {
        return keys.poll(5, TimeUnit.SECONDS);
    }

    /** {@code "<Cmd> <DataOfCmd>"}, e.g. {@code "Press KEY_UP"}. */
    public String nextCommand() throws InterruptedException {
        return commands.poll(5, TimeUnit.SECONDS);
    }

    public String nextLaunch() throws InterruptedException {
        return launches.poll(5, TimeUnit.SECONDS);
    }

    /** {@code "<Content-Type>|<body>"}. */
    public String nextDialBody() throws InterruptedException {
        return dialBodies.poll(5, TimeUnit.SECONDS);
    }

    /** Sends {@code text} as-is on every connection opened so far (closed ones fail silently). */
    public void sendRaw(String text) {
        openConnections.forEach(connection -> connection.send(text));
    }

    private void open(FakeWebSocketServer.Connection connection) {
        openConnections.add(connection);
        queries.add(connection.query() == null ? "" : connection.query());
        // Real sets chatter before answering; the client must skip this.
        connection.send("{\"event\":\"ed.edenTV.update\",\"data\":{\"update_type\":\"ed.edenApp.update\"}}");
        if (TOKEN.equals(queryParameter(connection.query(), "token"))) {
            connection.send(connectEvent(false));
            return;
        }
        switch (authorization) {
            case ALLOW -> connection.send(connectEvent(issueTokens));
            case DENY -> {
                connection.send("{\"event\":\"ms.channel.unauthorized\"}");
                connection.closeNormally();
            }
            case IGNORE -> {
            }
        }
    }

    private void message(FakeWebSocketServer.Connection connection, String text) {
        JsonNode message = JSON.readTree(text);
        JsonNode params = message.path("params");
        switch (message.path("method").asString("")) {
            case "ms.remote.control" -> {
                keys.add(params.path("DataOfCmd").asString(""));
                commands.add(params.path("Cmd").asString("") + " " + params.path("DataOfCmd").asString(""));
            }
            case "ms.channel.emit" -> {
                String event = params.path("event").asString("");
                if (event.equals("ed.apps.launch")) {
                    String appId = params.path("data").path("appId").asString("");
                    launches.add(appId);
                    showOnly(appId);
                    connection.send("{\"data\":200,\"event\":\"ed.apps.launch\",\"from\":\"host\"}");
                } else if (event.equals("ed.installedApp.get")) {
                    connection.send(fixture("installed-apps.json"));
                }
            }
            default -> {
            }
        }
    }

    private void rest(HttpExchange exchange) throws IOException {
        if (!restAvailable) {
            exchange.close();
            return;
        }
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/api/v2/")) {
            respond(exchange, 200, fixture("device-info.json")
                    .replace("\"PowerState\":\"on\"", "\"PowerState\":\"" + powerState + "\"")
                    + " ".repeat(deviceInfoPadding));
        } else if (path.startsWith("/api/v2/applications/")) {
            String appId = path.substring("/api/v2/applications/".length());
            Boolean isVisible = visible.get(appId);
            if (isVisible == null) {
                respond(exchange, 404, "{\"code\":404,\"message\":\"Not Found\",\"status\":404}");
            } else {
                respond(exchange, 200, "{\"id\":\"" + appId + "\",\"name\":\"" + (appId.equals(YOUTUBE) ? "YouTube" : "Netflix")
                        + "\",\"running\":" + isVisible + ",\"version\":\"1.0.0\",\"visible\":" + isVisible + "}");
            }
        } else {
            respond(exchange, 404, "");
        }
    }

    private void dial(HttpExchange exchange) throws IOException {
        if (!dialAvailable || !exchange.getRequestMethod().equals("POST")
                || !exchange.getRequestURI().getPath().equals("/ws/apps/YouTube")) {
            respond(exchange, 404, "");
            return;
        }
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        dialBodies.add(contentType + "|" + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        showOnly(YOUTUBE);
        exchange.getResponseHeaders().add("LOCATION", "http://127.0.0.1:" + httpPort() + "/ws/apps/YouTube/run");
        respond(exchange, 201, "");
    }

    private void showOnly(String appId) {
        visible.replaceAll((id, ignored) -> false);
        visible.put(appId, true);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }

    private static String connectEvent(boolean withToken) {
        return "{\"data\":{\"clients\":[{\"attributes\":{\"name\":\"SG9tZSBDb250cm9s\"},\"connectTime\":1726480800000,"
                + "\"deviceName\":\"SG9tZSBDb250cm9s\",\"id\":\"c1a2b3\",\"isHost\":false}],\"id\":\"c1a2b3\""
                + (withToken ? ",\"token\":\"" + TOKEN + "\"" : "") + "},\"event\":\"ms.channel.connect\"}";
    }

    private static String queryParameter(String query, String name) {
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0 && pair.substring(0, equals).equals(name)) {
                return URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String fixture(String name) {
        try {
            return Files.readString(Path.of("src/test/resources/fixtures/tizen/" + name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        remote.close();
        http.stop(0);
    }
}
