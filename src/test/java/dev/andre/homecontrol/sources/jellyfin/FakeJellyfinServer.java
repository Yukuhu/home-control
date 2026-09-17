package dev.andre.homecontrol.sources.jellyfin;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/** In-process Jellyfin: canned responses keyed by "METHOD /path", every request recorded. Unknown routes → 404. */
public final class FakeJellyfinServer implements AutoCloseable {

    public static final String SERVER_ID = "4e1a2b3c4d5e4f60718293a4b5c6d7e8";
    public static final String USER_ID = "a1b2c3d4e5f60718293a4b5c6d7e8f90";
    public static final String ACCESS_TOKEN = "6c1f0e5a9b8d4c7e8f2a3b4c5d6e7f80";

    public record Recorded(String method, String path, Map<String, String> query, Map<String, String> headers, String body) {
        public String header(String name) {
            return headers.get(name.toLowerCase(java.util.Locale.ROOT));
        }
    }

    private record Canned(int status, String contentType, byte[] body) {
    }

    private final HttpServer server;
    private final Map<String, Canned> routes = new ConcurrentHashMap<>();
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();

    public FakeJellyfinServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    public URI url() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    /** System info, AuthenticateByName and the user — enough to connect in password mode. */
    public FakeJellyfinServer withConnectableServer() {
        return respond("GET", "/System/Info/Public", 200, "system-info-public.json")
                .respond("POST", "/Users/AuthenticateByName", 200, "authenticate-by-name.json")
                .respond("GET", "/Users/" + USER_ID, 200, "user.json")
                .respondJson("POST", "/Sessions/Logout", 204, null);
    }

    public FakeJellyfinServer respond(String method, String path, int status, String fixture) {
        return respondBytes(method, path, status, "application/json; charset=utf-8",
                fixture == null ? new byte[0] : fixture(fixture).getBytes(StandardCharsets.UTF_8));
    }

    public FakeJellyfinServer respondJson(String method, String path, int status, String json) {
        return respondBytes(method, path, status, "application/json; charset=utf-8",
                json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8));
    }

    public FakeJellyfinServer respondBytes(String method, String path, int status, String contentType, byte[] body) {
        routes.put(method + " " + path, new Canned(status, contentType, body));
        return this;
    }

    public List<Recorded> requests(String method, String path) {
        return requests.stream().filter(r -> r.method().equals(method) && r.path().equals(path)).toList();
    }

    public Recorded last(String method, String path) {
        List<Recorded> matching = requests(method, path);
        if (matching.isEmpty()) {
            throw new AssertionError("No " + method + " " + path + " received; got " + requests);
        }
        return matching.getLast();
    }

    public List<Recorded> requests() {
        return List.copyOf(requests);
    }

    public static String fixture(String name) {
        try (InputStream in = FakeJellyfinServer.class.getResourceAsStream("/fixtures/jellyfin/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("No fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            URI uri = exchange.getRequestURI();
            Map<String, String> query = new LinkedHashMap<>();
            if (uri.getRawQuery() != null) {
                for (String pair : uri.getRawQuery().split("&")) {
                    int eq = pair.indexOf('=');
                    String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
                    String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                    query.put(key, value);
                }
            }
            Map<String, String> headers = new TreeMap<>();
            exchange.getRequestHeaders().forEach((name, values) ->
                    headers.put(name.toLowerCase(java.util.Locale.ROOT), String.join(",", values)));
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new Recorded(exchange.getRequestMethod(), uri.getRawPath(), query, headers, body));

            Canned canned = routes.get(exchange.getRequestMethod() + " " + uri.getRawPath());
            if (canned == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", canned.contentType());
            if (canned.status() >= 300 && canned.status() < 400) {
                exchange.getResponseHeaders().set("Location", "http://elsewhere.invalid/");
            }
            if (canned.body().length == 0) {
                exchange.sendResponseHeaders(canned.status(), -1);
                return;
            }
            exchange.sendResponseHeaders(canned.status(), canned.body().length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(canned.body());
            }
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
