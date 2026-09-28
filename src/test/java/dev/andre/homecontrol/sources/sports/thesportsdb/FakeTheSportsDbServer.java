package dev.andre.homecontrol.sources.sports.thesportsdb;

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

import static dev.andre.homecontrol.testsupport.FakeHttpServer.ANY_METHOD;

/** In-process TheSportsDB: canned responses keyed by endpoint + query, every request recorded. Same design as FakeTmdbServer. */
public final class FakeTheSportsDbServer implements AutoCloseable {

    public static final String FREE_KEY = "123";
    public static final String PERSONAL_KEY = "9876543210";

    private static final String PREFIX = "/api/v1/json/";
    private static final List<String> KEYS = List.of(FREE_KEY, PERSONAL_KEY);

    public record Recorded(String key, String endpoint, Map<String, String> query, Map<String, String> headers) {
        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    private final FakeHttpServer server;

    public FakeTheSportsDbServer() throws IOException {
        server = FakeHttpServer.start();
        defaults();
    }

    /** Forgets every route, request and delay, and restores the fresh fake, for a fake shared across test classes. */
    public void reset() {
        server.reset();
        defaults();
    }

    private void defaults() {
        server.fallback(request -> KEYS.contains(keyAndEndpoint(request.path())[0])
                ? json(404, "{}".getBytes(StandardCharsets.UTF_8))
                : json(400, fixtureBytes("invalid-key.json")));
        byDefault("lookupleague.php", fixtureBytes("lookupleague-unknown.json"));
        byDefault("eventsday.php", fixtureBytes("eventsday-empty.json"));
    }

    public URI apiBase() {
        return server.url("/api/v1/json");
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
        return route(endpoint, query, json(status, fixtureBytes(fixture)));
    }

    public FakeTheSportsDbServer respondJson(String endpoint, Map<String, String> query, int status, String json) {
        return route(endpoint, query, json(status, json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8)));
    }

    public FakeTheSportsDbServer delay(Duration duration) {
        server.delay(duration);
        return this;
    }

    public List<Recorded> requests(String endpoint) {
        return server.requests().stream()
                .map(FakeTheSportsDbServer::recorded)
                .filter(recorded -> recorded.endpoint().equals(endpoint))
                .toList();
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

    private FakeTheSportsDbServer route(String endpoint, Map<String, String> query, Response response) {
        Map<String, String> expected = Map.copyOf(query);
        for (String key : KEYS) {
            server.respond(ANY_METHOD, PREFIX + key + "/" + endpoint, request -> request.query().equals(expected), response);
        }
        return this;
    }

    private void byDefault(String endpoint, byte[] body) {
        for (String key : KEYS) {
            server.respond(ANY_METHOD, PREFIX + key + "/" + endpoint, json(200, body));
        }
    }

    private static Response json(int status, byte[] body) {
        return Response.of(status, Response.JSON, body);
    }

    /** {key, endpoint} of a path under /api/v1/json/; empty strings for anything else. */
    private static String[] keyAndEndpoint(String path) {
        String remainder = path.startsWith(PREFIX) ? path.substring(PREFIX.length()) : "";
        int slash = remainder.indexOf('/');
        return slash < 0
                ? new String[]{remainder, ""}
                : new String[]{remainder.substring(0, slash), remainder.substring(slash + 1)};
    }

    private static Recorded recorded(Request request) {
        String[] keyAndEndpoint = keyAndEndpoint(request.path());
        return new Recorded(keyAndEndpoint[0], keyAndEndpoint[1], request.query(), request.headers());
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

    @Override
    public void close() {
        server.close();
    }
}
