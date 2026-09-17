package dev.andre.homecontrol.sources.tmdb;

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

/** In-process TMDB: canned responses keyed by "METHOD /path", every request recorded. Unknown routes → 404. */
public final class FakeTmdbServer implements AutoCloseable {

    public static final String READ_TOKEN =
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJob21lLWNvbnRyb2wtdGVzdCJ9.c2lnbmF0dXJlLW9mLXRoZS10ZXN0LXRva2Vu";
    public static final String API_KEY = "0123456789abcdef0123456789abcdef";

    public record Recorded(String method, String path, Map<String, String> query, Map<String, String> headers, String rawQuery) {
        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    private record Canned(int status, String contentType, byte[] body, Duration delay, String redirect) {
    }

    private final HttpServer server;
    private final Map<String, Canned> routes = new ConcurrentHashMap<>();
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private volatile Duration globalDelay = Duration.ZERO;

    public FakeTmdbServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    public URI url() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    public URI apiBase() {
        return URI.create(url() + "/3");
    }

    public FakeTmdbServer withStandardResponses() {
        respond("GET", "/3/authentication", 200, "authentication.json");
        respond("GET", "/3/configuration", 200, "configuration.json");
        respond("GET", "/3/search/multi", 200, "search-multi.json");
        respond("GET", "/3/trending/all/week", 200, "trending-all-week.json");
        respond("GET", "/3/movie/603", 200, "details-movie-603.json");
        respond("GET", "/3/tv/66732", 200, "details-tv-66732.json");
        respond("GET", "/3/movie/603/watch/providers", 200, "providers-movie-603.json");
        respond("GET", "/3/movie/550/watch/providers", 200, "providers-movie-550.json");
        respond("GET", "/3/tv/66732/watch/providers", 200, "providers-tv-66732.json");
        respond("GET", "/3/tv/76479/watch/providers", 200, "providers-tv-76479.json");
        respond("GET", "/3/tv/94997/watch/providers", 200, "providers-tv-94997.json");
        return this;
    }

    public FakeTmdbServer respond(String method, String path, int status, String fixture) {
        return respondBytes(method, path, status, "application/json; charset=utf-8",
                fixture == null ? new byte[0] : fixture(fixture).getBytes(StandardCharsets.UTF_8));
    }

    public FakeTmdbServer respondJson(String method, String path, int status, String json) {
        return respondBytes(method, path, status, "application/json; charset=utf-8",
                json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8));
    }

    public FakeTmdbServer respondBytes(String method, String path, int status, String contentType, byte[] body) {
        routes.put(method + " " + path, new Canned(status, contentType, body, Duration.ZERO, null));
        return this;
    }

    /** Every response (until changed) sleeps this long before answering — for timeout tests. */
    public FakeTmdbServer delay(Duration duration) {
        this.globalDelay = duration;
        return this;
    }

    public FakeTmdbServer redirect(String path, String location) {
        routes.put("GET " + path, new Canned(302, "text/plain", new byte[0], Duration.ZERO, location));
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

    public int count(String method, String path) {
        return requests(method, path).size();
    }

    public List<Recorded> requests() {
        return List.copyOf(requests);
    }

    public static String fixture(String name) {
        try (InputStream in = FakeTmdbServer.class.getResourceAsStream("/fixtures/tmdb/" + name)) {
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
                    headers.put(name.toLowerCase(Locale.ROOT), String.join(",", values)));
            exchange.getRequestBody().readAllBytes();
            requests.add(new Recorded(exchange.getRequestMethod(), uri.getRawPath(), query, headers,
                    uri.getRawQuery() == null ? "" : uri.getRawQuery()));

            Duration wait = globalDelay;
            if (!wait.isZero()) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            Canned canned = routes.get(exchange.getRequestMethod() + " " + uri.getRawPath());
            if (canned == null) {
                byte[] body = fixture("not-found.json").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(404, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", canned.contentType());
            if (canned.redirect() != null) {
                exchange.getResponseHeaders().set("Location", canned.redirect());
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
