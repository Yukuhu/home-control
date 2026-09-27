package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.testsupport.FakeHttpServer;
import dev.andre.homecontrol.testsupport.Request;
import dev.andre.homecontrol.testsupport.Response;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** In-process Jellyfin: canned responses keyed by "METHOD /path", every request recorded. Unknown routes → 404. */
public final class FakeJellyfinServer implements AutoCloseable {

    public static final String SERVER_ID = "4e1a2b3c4d5e4f60718293a4b5c6d7e8";
    public static final String USER_ID = "a1b2c3d4e5f60718293a4b5c6d7e8f90";
    public static final String ACCESS_TOKEN = "6c1f0e5a9b8d4c7e8f2a3b4c5d6e7f80";

    public record Recorded(String method, String path, Map<String, String> query, Map<String, String> headers, String body) {
        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    private final FakeHttpServer server;

    public FakeJellyfinServer() throws IOException {
        server = FakeHttpServer.start();
    }

    public URI url() {
        return server.url();
    }

    /** System info, AuthenticateByName and the user — enough to connect in password mode. */
    public FakeJellyfinServer withConnectableServer() {
        return respond("GET", "/System/Info/Public", 200, "system-info-public.json")
                .respond("POST", "/Users/AuthenticateByName", 200, "authenticate-by-name.json")
                .respond("GET", "/Users/" + USER_ID, 200, "user.json")
                .respondJson("POST", "/Sessions/Logout", 204, null);
    }

    public FakeJellyfinServer respond(String method, String path, int status, String fixture) {
        return respondBytes(method, path, status, Response.JSON,
                fixture == null ? new byte[0] : fixture(fixture).getBytes(StandardCharsets.UTF_8));
    }

    public FakeJellyfinServer respondJson(String method, String path, int status, String json) {
        return respondBytes(method, path, status, Response.JSON,
                json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8));
    }

    /** A redirect status also sends a Location off the server, which the client must not follow. */
    public FakeJellyfinServer respondBytes(String method, String path, int status, String contentType, byte[] body) {
        Response response = Response.of(status, contentType, body);
        server.respond(method, path, status >= 300 && status < 400
                ? response.withHeader("Location", "http://elsewhere.invalid/") : response);
        return this;
    }

    public List<Recorded> requests(String method, String path) {
        return server.requests(method, path).stream().map(FakeJellyfinServer::recorded).toList();
    }

    public Recorded last(String method, String path) {
        List<Recorded> matching = requests(method, path);
        if (matching.isEmpty()) {
            throw new AssertionError("No " + method + " " + path + " received; got " + requests());
        }
        return matching.getLast();
    }

    public List<Recorded> requests() {
        return server.requests().stream().map(FakeJellyfinServer::recorded).toList();
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

    private static Recorded recorded(Request request) {
        return new Recorded(request.method(), request.path(), request.query(), request.headers(), request.body());
    }

    @Override
    public void close() {
        server.close();
    }
}
