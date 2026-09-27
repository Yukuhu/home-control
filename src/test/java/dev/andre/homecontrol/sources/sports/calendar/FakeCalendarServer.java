package dev.andre.homecontrol.sources.sports.calendar;

import dev.andre.homecontrol.testsupport.FakeHttpServer;
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

/** In-process calendar server: canned responses keyed by path, every request recorded. Same design as FakeTmdbServer. */
public final class FakeCalendarServer implements AutoCloseable {

    public record Recorded(String method, String path, Map<String, String> query, Map<String, String> headers) {
        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    private final FakeHttpServer server;

    public FakeCalendarServer() throws IOException {
        server = FakeHttpServer.start().fallback(Response.of(404, "text/plain", "not found"));
    }

    public URI url(String path) {
        return server.url(path);
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
        server.respond(ANY_METHOD, path, Response.of(status, contentType, body));
        return this;
    }

    public FakeCalendarServer redirect(String path, int status, String location) {
        server.respond(ANY_METHOD, path, Response.of(status, "text/plain", new byte[0]).withHeader("Location", location));
        return this;
    }

    public FakeCalendarServer delay(Duration duration) {
        server.delay(duration);
        return this;
    }

    public List<Recorded> requests(String path) {
        return server.requests(ANY_METHOD, path).stream()
                .map(request -> new Recorded(request.method(), request.path(), request.query(), request.headers()))
                .toList();
    }

    public int count(String path) {
        return server.count(ANY_METHOD, path);
    }

    @Override
    public void close() {
        server.close();
    }
}
