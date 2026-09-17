package dev.andre.homecontrol.sources.youtube;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Predicate;

/** Google OAuth, YouTube Data API v3, Lounge and thumbnails in one in-process fake. */
public final class FakeGoogleServer implements AutoCloseable {

    public record Recorded(String method, String path, Map<String, String> query, Map<String, String> form,
                           Map<String, String> headers, String body) {
        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    public record Canned(int status, String contentType, byte[] body) {
        public static Canned json(int status, String json) {
            return new Canned(status, "application/json; charset=UTF-8", json.getBytes(StandardCharsets.UTF_8));
        }

        public static Canned fixture(int status, String name) {
            return json(status, FakeGoogleServer.fixture(name));
        }
    }

    private record Rule(String method, String path, Predicate<Recorded> when, Deque<Canned> answers) {
    }

    private final HttpServer server;
    private final List<Rule> rules = new CopyOnWriteArrayList<>();
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();

    public FakeGoogleServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    public URI base() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    public YouTubeProperties properties() {
        URI base = base();
        return new YouTubeProperties(true, URI.create(base + "/oauth"), URI.create(base + "/youtube/v3"),
                URI.create(base + "/lounge"), URI.create(base + "/thumbs"), 2, 5, 10000, 20, 30, 30, 5,
                Duration.ofHours(24), 20, Duration.ofMinutes(60), Duration.ofMinutes(15), Duration.ofHours(6));
    }

    public static String fixture(String name) {
        try (InputStream in = FakeGoogleServer.class.getResourceAsStream("/fixtures/youtube/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("No fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Answers {@code method path} (path without query) with the given responses in order; the last one
     * repeats. Later rules win over earlier ones, so a test can override a default.
     */
    public FakeGoogleServer respond(String method, String path, Canned... answers) {
        return respondWhen(method, path, request -> true, answers);
    }

    public FakeGoogleServer respondWhen(String method, String path, Predicate<Recorded> when, Canned... answers) {
        rules.addFirst(new Rule(method, path, when, new ArrayDeque<>(List.of(answers))));
        return this;
    }

    public List<Recorded> requests() {
        return List.copyOf(requests);
    }

    public List<Recorded> requests(String path) {
        return requests.stream().filter(r -> r.path().equals(path)).toList();
    }

    public int count(String path) {
        return requests(path).size();
    }

    /** OAuth defaults: device code, then pending once, then granted; refresh granted; revoke 200. */
    public FakeGoogleServer oauthApproves() {
        respond("POST", "/oauth/device/code", Canned.fixture(200, "oauth-device-code.json"));
        respond("POST", "/oauth/token", Canned.fixture(428, "oauth-token-pending.json"),
                Canned.fixture(200, "oauth-token-granted.json"));
        respondWhen("POST", "/oauth/token", r -> "refresh_token".equals(r.form().get("grant_type")),
                Canned.fixture(200, "oauth-refresh-granted.json"));
        respond("POST", "/oauth/revoke", Canned.json(200, "{}"));
        return this;
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), String.join(",", v)));
        String contentType = headers.getOrDefault("content-type", "");
        Recorded recorded = new Recorded(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                decode(exchange.getRequestURI().getRawQuery()),
                contentType.startsWith("application/x-www-form-urlencoded") ? decode(body) : Map.of(),
                headers, body);
        requests.add(recorded);
        Canned answer = rules.stream()
                .filter(rule -> rule.method().equals(recorded.method()) && rule.path().equals(recorded.path())
                        && rule.when().test(recorded))
                .findFirst()
                .map(rule -> {
                    synchronized (rule.answers()) {
                        return rule.answers().size() > 1 ? rule.answers().pollFirst() : rule.answers().peekFirst();
                    }
                })
                .orElse(Canned.json(404, "{\"error\":{\"code\":404,\"message\":\"no fake route\",\"errors\":[]}}"));
        exchange.getResponseHeaders().add("Content-Type", answer.contentType());
        exchange.sendResponseHeaders(answer.status(), answer.body().length == 0 ? -1 : answer.body().length);
        if (answer.body().length > 0) {
            exchange.getResponseBody().write(answer.body());
        }
        exchange.close();
    }

    static Map<String, String> decode(String raw) {
        Map<String, String> values = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return values;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            values.merge(key, value, (a, b) -> a + "," + b);
        }
        return values;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
