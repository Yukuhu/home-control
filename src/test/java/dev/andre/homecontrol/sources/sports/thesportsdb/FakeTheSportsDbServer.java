package dev.andre.homecontrol.sources.sports.thesportsdb;

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
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/** In-process TheSportsDB: canned responses keyed by endpoint + query, every request recorded. Same design as FakeTmdbServer. */
public final class FakeTheSportsDbServer implements AutoCloseable {

    public static final String FREE_KEY = "123";
    public static final String PERSONAL_KEY = "9876543210";

    public record Recorded(String key, String endpoint, Map<String, String> query, Map<String, String> headers) {
        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    private record Canned(int status, byte[] body) {
    }

    private final HttpServer server;
    private final Map<String, Map<Map<String, String>, Canned>> routes = new ConcurrentHashMap<>();
    private final Map<String, Canned> defaults = new ConcurrentHashMap<>();
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private volatile Duration globalDelay = Duration.ZERO;

    public FakeTheSportsDbServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        defaults.put("lookupleague.php", new Canned(200, fixtureBytes("lookupleague-unknown.json")));
        defaults.put("eventsday.php", new Canned(200, fixtureBytes("eventsday-empty.json")));
    }

    public URI apiBase() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/json");
    }

    public FakeTheSportsDbServer withStandardResponses() {
        respond("lookupleague.php", Map.of("id", "4331"), 200, "lookupleague-4331.json");
        respond("lookupleague.php", Map.of("id", "4328"), 200, "lookupleague-4328.json");
        respond("search_all_leagues.php", Map.of("c", "Germany", "s", "Soccer"), 200, "search_all_leagues-germany-soccer.json");
        respond("eventsday.php", Map.of("d", "2026-09-18", "l", "4331"), 200, "eventsday-2026-09-18-4331.json");
        respond("eventsday.php", Map.of("d", "2026-09-19", "l", "4331"), 200, "eventsday-2026-09-19-4331.json");
        respond("eventsday.php", Map.of("d", "2026-09-19", "l", "4328"), 200, "eventsday-2026-09-19-4328.json");
        return this;
    }

    public FakeTheSportsDbServer respond(String endpoint, Map<String, String> query, int status, String fixture) {
        routes.computeIfAbsent(endpoint, e -> new ConcurrentHashMap<>()).put(Map.copyOf(query), new Canned(status, fixtureBytes(fixture)));
        return this;
    }

    public FakeTheSportsDbServer respondJson(String endpoint, Map<String, String> query, int status, String json) {
        routes.computeIfAbsent(endpoint, e -> new ConcurrentHashMap<>())
                .put(Map.copyOf(query), new Canned(status, json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8)));
        return this;
    }

    public FakeTheSportsDbServer delay(Duration duration) {
        this.globalDelay = duration;
        return this;
    }

    public List<Recorded> requests(String endpoint) {
        return requests.stream().filter(r -> r.endpoint().equals(endpoint)).toList();
    }

    public int count(String endpoint) {
        return requests(endpoint).size();
    }

    public Recorded last(String endpoint) {
        List<Recorded> matching = requests(endpoint);
        if (matching.isEmpty()) {
            throw new AssertionError("No " + endpoint + " request received");
        }
        return matching.getLast();
    }

    private static byte[] fixtureBytes(String name) {
        try (InputStream in = FakeTheSportsDbServer.class.getResourceAsStream("/fixtures/thesportsdb/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("No fixture " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            URI uri = exchange.getRequestURI();
            String path = uri.getRawPath();
            String prefix = "/api/v1/json/";
            String remainder = path.startsWith(prefix) ? path.substring(prefix.length()) : "";
            int slash = remainder.indexOf('/');
            String key = slash < 0 ? remainder : remainder.substring(0, slash);
            String endpoint = slash < 0 ? "" : remainder.substring(slash + 1);

            Map<String, String> query = new LinkedHashMap<>();
            if (uri.getRawQuery() != null) {
                for (String pair : uri.getRawQuery().split("&")) {
                    int eq = pair.indexOf('=');
                    String name = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
                    String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                    query.put(name, value);
                }
            }
            Map<String, String> headers = new TreeMap<>();
            exchange.getRequestHeaders().forEach((name, values) ->
                    headers.put(name.toLowerCase(Locale.ROOT), String.join(",", values)));
            exchange.getRequestBody().readAllBytes();
            requests.add(new Recorded(key, endpoint, query, headers));

            Duration wait = globalDelay;
            if (!wait.isZero()) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
            }

            if (!FREE_KEY.equals(key) && !PERSONAL_KEY.equals(key)) {
                respond(exchange, 400, fixtureBytes("invalid-key.json"));
                return;
            }

            Map<Map<String, String>, Canned> byQuery = routes.get(endpoint);
            Canned canned = byQuery == null ? null : byQuery.get(query);
            if (canned == null) {
                canned = defaults.get(endpoint);
            }
            if (canned == null) {
                respond(exchange, 404, "{}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            respond(exchange, canned.status(), canned.body());
        }
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        if (body.length == 0) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
