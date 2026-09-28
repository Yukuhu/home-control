package dev.andre.homecontrol.sources.tmdb;

import dev.andre.homecontrol.testsupport.FakeHttpServer;
import dev.andre.homecontrol.testsupport.Request;
import dev.andre.homecontrol.testsupport.Response;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

    private final FakeHttpServer server;

    public FakeTmdbServer() throws IOException {
        server = FakeHttpServer.start();
        defaults();
    }

    /** Forgets every route, request and delay, and restores the fresh fake, for a fake shared across test classes. */
    public void reset() {
        server.reset();
        defaults();
    }

    private void defaults() {
        server.fallback(Response.of(404, Response.JSON, fixture("not-found.json")));
    }

    public URI url() {
        return server.url();
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
        return respondBytes(method, path, status, Response.JSON,
                fixture == null ? new byte[0] : fixture(fixture).getBytes(StandardCharsets.UTF_8));
    }

    public FakeTmdbServer respondJson(String method, String path, int status, String json) {
        return respondBytes(method, path, status, Response.JSON,
                json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8));
    }

    public FakeTmdbServer respondBytes(String method, String path, int status, String contentType, byte[] body) {
        server.respond(method, path, Response.of(status, contentType, body));
        return this;
    }

    /** Every response (until changed) sleeps this long before answering — for timeout tests. */
    public FakeTmdbServer delay(Duration duration) {
        server.delay(duration);
        return this;
    }

    public FakeTmdbServer redirect(String path, String location) {
        server.respond("GET", path, Response.of(302, "text/plain", new byte[0]).withHeader("Location", location));
        return this;
    }

    public List<Recorded> requests(String method, String path) {
        return server.requests(method, path).stream().map(FakeTmdbServer::recorded).toList();
    }

    public Recorded last(String method, String path) {
        List<Recorded> matching = requests(method, path);
        if (matching.isEmpty()) {
            throw new AssertionError("No " + method + " " + path + " received; got " + requests());
        }
        return matching.getLast();
    }

    public int count(String method, String path) {
        return server.count(method, path);
    }

    public List<Recorded> requests() {
        return server.requests().stream().map(FakeTmdbServer::recorded).toList();
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

    private static Recorded recorded(Request request) {
        return new Recorded(request.method(), request.path(), request.query(), request.headers(), request.rawQuery());
    }

    @Override
    public void close() {
        server.close();
    }
}
