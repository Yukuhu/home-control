package dev.andre.homecontrol.sources.sports.calendar;

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

/** In-process calendar server: canned responses keyed by path, every request recorded. Same design as FakeTmdbServer. */
public final class FakeCalendarServer implements AutoCloseable {

    public record Recorded(String method, String path, Map<String, String> query, Map<String, String> headers) {
        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    private record Canned(int status, String contentType, byte[] body, String location) {
    }

    private final HttpServer server;
    private final Map<String, Canned> routes = new ConcurrentHashMap<>();
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private volatile Duration globalDelay = Duration.ZERO;

    public FakeCalendarServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    public URI url(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    public FakeCalendarServer respond(String path, int status, String contentType, String body) {
        return respondBytes(path, status, contentType, body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8));
    }

    public FakeCalendarServer respondFixture(String path, String fixtureName) {
        try (InputStream in = FakeCalendarServer.class.getResourceAsStream("/fixtures/ics/" + fixtureName)) {
            if (in == null) {
                throw new IllegalArgumentException("No fixture " + fixtureName);
            }
            return respondBytes(path, 200, "text/calendar; charset=utf-8", in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public FakeCalendarServer respondBytes(String path, int status, String contentType, byte[] body) {
        routes.put(path, new Canned(status, contentType, body, null));
        return this;
    }

    public FakeCalendarServer redirect(String path, int status, String location) {
        routes.put(path, new Canned(status, "text/plain", new byte[0], location));
        return this;
    }

    public FakeCalendarServer delay(Duration duration) {
        this.globalDelay = duration;
        return this;
    }

    public List<Recorded> requests(String path) {
        return requests.stream().filter(r -> r.path().equals(path)).toList();
    }

    public int count(String path) {
        return requests(path).size();
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
            requests.add(new Recorded(exchange.getRequestMethod(), uri.getRawPath(), query, headers));

            Duration wait = globalDelay;
            if (!wait.isZero()) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
            }

            Canned canned = routes.get(uri.getRawPath());
            if (canned == null) {
                byte[] body = "not found".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain");
                exchange.sendResponseHeaders(404, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", canned.contentType());
            if (canned.location() != null) {
                exchange.getResponseHeaders().set("Location", canned.location());
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
