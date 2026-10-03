package dev.andre.homecontrol.sources.youtube;

import dev.andre.homecontrol.testsupport.FakeHttpServer;
import dev.andre.homecontrol.testsupport.Request;
import dev.andre.homecontrol.testsupport.Response;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.function.Predicate;

/** Google OAuth, YouTube Data API v3, Lounge and thumbnails in one in-process fake. */
public final class FakeGoogleServer implements AutoCloseable {

    public record Recorded(String method, String path, Map<String, String> query, Map<String, String> form,
                           Map<String, String> headers, String body, String rawQuery) {
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

    private final FakeHttpServer server;

    public FakeGoogleServer() throws IOException {
        server = FakeHttpServer.start();
        defaults();
    }

    /** Forgets every route, request and delay, and restores the fresh fake, for a fake shared across test classes. */
    public void reset() {
        server.reset();
        defaults();
    }

    private void defaults() {
        server.fallback(Response.of(404, "application/json; charset=UTF-8",
                "{\"error\":{\"code\":404,\"message\":\"no fake route\",\"errors\":[]}}"));
    }

    public URI base() {
        return server.url();
    }

    public YouTubeProperties properties() {
        return properties(true);
    }

    /** {@code allowLoopback} false is the production default, which refuses this fake. */
    public YouTubeProperties properties(boolean allowLoopback) {
        URI base = base();
        return new YouTubeProperties(true, URI.create(base + "/oauth"), URI.create(base + "/youtube/v3"),
                URI.create(base + "/lounge"), URI.create(base + "/thumbs"), Duration.ofSeconds(2),
                Duration.ofSeconds(5), 10000, 20, 30, 30, 5,
                Duration.ofHours(24), 20, Duration.ofMinutes(60), Duration.ofMinutes(15), Duration.ofHours(6), allowLoopback);
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
        server.respond(method, path, request -> when.test(recorded(request)), Arrays.stream(answers)
                .map(answer -> Response.of(answer.status(), answer.contentType(), answer.body()))
                .toArray(Response[]::new));
        return this;
    }

    /** Records each request {@code when} accepts at once, and answers it with {@code answer} once released. */
    public FakeGoogleServer holdWhen(String method, String path, Predicate<Recorded> when, CountDownLatch release,
                                     Canned answer) {
        server.hold(method, path, request -> when.test(recorded(request)), release,
                Response.of(answer.status(), answer.contentType(), answer.body()));
        return this;
    }

    public List<Recorded> requests() {
        return server.requests().stream().map(FakeGoogleServer::recorded).toList();
    }

    public List<Recorded> requests(String path) {
        return requests().stream().filter(r -> r.path().equals(path)).toList();
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

    /** Channel, three subscriptions on two pages, their uploads playlists and their newest videos. */
    public FakeGoogleServer youtubeLibrary() {
        respondWhen("GET", "/youtube/v3/channels", r -> "true".equals(r.query().get("mine")),
                Canned.fixture(200, "channels-mine.json"));
        respondWhen("GET", "/youtube/v3/channels", r -> "contentDetails".equals(r.query().get("part")),
                Canned.fixture(200, "channels-uploads.json"));
        respondWhen("GET", "/youtube/v3/subscriptions", r -> !r.query().containsKey("pageToken"),
                Canned.fixture(200, "subscriptions-page-1.json"));
        respondWhen("GET", "/youtube/v3/subscriptions", r -> "CAIQAA".equals(r.query().get("pageToken")),
                Canned.fixture(200, "subscriptions-page-2.json"));
        playlist("UUsXVk37bltHxD1rDPwtNM8Q", "playlist-items-uploads-kurzgesagt.json");
        playlist("UUSMOQeBJ2RAnuFungnQOxLg", "playlist-items-uploads-blender.json");
        playlist("UULA_DiR1FfKNvjuUpBHmylQ", "playlist-items-uploads-nasa.json");
        respond("GET", "/youtube/v3/videos", Canned.fixture(200, "videos-by-id.json"));
        return this;
    }

    public FakeGoogleServer playlist(String playlistId, String fixture) {
        return respondWhen("GET", "/youtube/v3/playlistItems", r -> playlistId.equals(r.query().get("playlistId")),
                Canned.fixture(200, fixture));
    }

    /** Lounge: token for the fixture screen, a bind answer, setPlaylist accepted. */
    public FakeGoogleServer loungeAccepts() {
        respond("POST", "/lounge/pairing/get_lounge_token_batch", Canned.fixture(200, "lounge-token-batch.json"));
        respondWhen("POST", "/lounge/bc/bind", r -> "1".equals(r.query().get("RID")),
                new Canned(200, "text/plain; charset=utf-8", fixture("lounge-bind.txt").getBytes(StandardCharsets.UTF_8)));
        respondWhen("POST", "/lounge/bc/bind", r -> "2".equals(r.query().get("RID")),
                new Canned(200, "text/plain; charset=utf-8", "7\n[[5,[]]\n".getBytes(StandardCharsets.UTF_8)));
        return this;
    }

    /** A 1×1 JPEG-looking body for any thumbnail. */
    public FakeGoogleServer thumbnails() {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0, (byte) 0xFF, (byte) 0xD9};
        return respondWhen("GET", "/thumbs/vi/aqz-KE-bpKQ/mqdefault.jpg", r -> true, new Canned(200, "image/jpeg", jpeg));
    }

    /** The Google APIs join a repeated name's values with commas, so this decode does too (unlike Request.decode). */
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

    private static Recorded recorded(Request request) {
        String contentType = request.headers().getOrDefault("content-type", "");
        return new Recorded(request.method(), request.uri().getPath(), decode(request.uri().getRawQuery()),
                contentType.startsWith("application/x-www-form-urlencoded") ? decode(request.body()) : Map.of(),
                request.headers(), request.body(), request.uri().getRawQuery());
    }

    @Override
    public void close() {
        server.close();
    }
}
