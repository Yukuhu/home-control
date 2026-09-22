package dev.andre.homecontrol.web;

import dev.andre.homecontrol.adapters.androidtv.AndroidTvSettings;
import dev.andre.homecontrol.adapters.androidtv.protocol.CertificateStore;
import dev.andre.homecontrol.adapters.androidtv.protocol.FakeRemoteServer;
import dev.andre.homecontrol.adapters.cast.protocol.FakeCastReceiver;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.sources.youtube.FakeGoogleServer;
import dev.andre.homecontrol.sources.youtube.YouTubeLoungeRouteExecutor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * YouTube through the whole application over real sockets: fake Google (OAuth device flow, the
 * Data API, thumbnails and the unofficial Lounge API) ↔ source ↔ resolver ↔ planner ↔ Android TV
 * and Cast adapters ↔ HTTP, with the login gate in front. Connect → authorize → gated rails →
 * quota display → on-demand search → app-link play on a Shield → best-effort Lounge play on a
 * Cast-only device → an explicit Lounge failure → disconnect, checking at every step that no
 * secret ever reaches a browser response or a log line.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class YouTubeEndToEndTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** Secrets that must never reach a browser response or a log line. */
    private static final List<String> SECRETS = List.of("GOCSPX-fixtureClientSecret", "1//0gFixture", "ya29.",
            "AH-1Ng2m", "AGdO5p8Fixture", "8A3F2E1D0C9B8A77", "fixture-gsessionid");

    static final FakeGoogleServer GOOGLE;

    static {
        try {
            GOOGLE = new FakeGoogleServer();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        GOOGLE.oauthApproves().youtubeLibrary().thumbnails();
        GOOGLE.respond("GET", "/youtube/v3/search", FakeGoogleServer.Canned.fixture(200, "search-videos.json"));
        // A poll interval of 5s (the fixture's default) would make the device-flow poll take too
        // long for a test; override just the device-code answer with a 1s interval. Later rules
        // win, so this overrides the one oauthApproves() just registered.
        String fastDeviceCode = FakeGoogleServer.fixture("oauth-device-code.json").replace("\"interval\": 5", "\"interval\": 1");
        GOOGLE.respond("POST", "/oauth/device/code", FakeGoogleServer.Canned.json(200, fastDeviceCode));
    }

    static Path dataDir;

    @AfterAll
    static void closeFakeGoogleAndTempDir() throws IOException {
        GOOGLE.close();
        if (dataDir != null) {
            deleteRecursively(dataDir);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @DynamicPropertySource
    static void isolatedAndFastAgainstTheFake(DynamicPropertyRegistry registry) throws IOException {
        dataDir = Files.createTempDirectory("youtube-e2e");
        registry.add("shield.data-dir", dataDir::toString);
        registry.add("home-control.youtube.oauth-base-url", () -> GOOGLE.base() + "/oauth");
        registry.add("home-control.youtube.api-base-url", () -> GOOGLE.base() + "/youtube/v3");
        registry.add("home-control.youtube.lounge-base-url", () -> GOOGLE.base() + "/lounge");
        registry.add("home-control.youtube.thumbnail-base-url", () -> GOOGLE.base() + "/thumbs");
        registry.add("home-control.content.rails.scheduler-enabled", () -> "false");
        registry.add("home-control.cast.command-timeout-seconds", () -> "3");
        registry.add("home-control.cast.load-timeout-seconds", () -> "5");
    }

    @LocalServerPort
    int port;

    @Autowired
    DeviceManager devices;

    @Autowired
    CertificateStore certificates;

    private final HttpClient browser = HttpClient.newBuilder()
            .cookieHandler(new CookieManager()).followRedirects(HttpClient.Redirect.NEVER).build();
    private final HttpClient stranger = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private final List<String> browserBodies = new ArrayList<>();

    private HttpResponse<String> send(HttpClient client, HttpRequest.Builder request) throws Exception {
        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (client == browser) {
            browserBodies.add(response.body());
        }
        return response;
    }

    private HttpRequest.Builder get(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    /** A page navigation: unauthenticated ones are redirected to /login instead of answered 401. */
    private HttpRequest.Builder page(String path) {
        return get(path).header("Accept", "text/html");
    }

    private HttpRequest.Builder post(String path, Map<String, String> form) {
        String body = form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Origin", "http://localhost:" + port)
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private static JsonNode json(HttpResponse<String> response) {
        return MAPPER.readTree(response.body());
    }

    @Test
    void youtubeFromConnectToCast(CapturedOutput output) throws Exception {
        try (FakeRemoteServer shieldRemote = new FakeRemoteServer();
             FakeCastReceiver kitchenCast = new FakeCastReceiver()) {

            certificates.loadOrCreate("shield");
            devices.adopt(AndroidTvSettings.device("shield", "Shield", "127.0.0.1", shieldRemote.port(), null, Instant.now()));

            kitchenCast.appSpeaks(YouTubeLoungeRouteExecutor.RECEIVER_APP_ID, YouTubeLoungeRouteExecutor.MDX_NAMESPACE);
            ObjectNode mdxReply = (ObjectNode) MAPPER.readTree(FakeGoogleServer.fixture("cast-mdx-session-status.json"));
            kitchenCast.answerCustom(YouTubeLoungeRouteExecutor.MDX_NAMESPACE, mdxReply);
            devices.adopt(new Device("kitchen", "Kitchen", DeviceKind.CAST, "127.0.0.1",
                    Map.of("cast", Map.of("port", String.valueOf(kitchenCast.port()))), Instant.now()));
            await().atMost(Duration.ofSeconds(5)).until(() -> devices.state("kitchen").connected());

            try {
                // 1. Device-only: the setup page opens without login and already lists YouTube,
                // the "TVs and Limited Input devices" client-type instructions and the Kitchen
                // Cast device's (off) YouTube Cast switch.
                HttpResponse<String> setupBeforeConnect = send(stranger, page("/setup"));
                assertThat(setupBeforeConnect.statusCode()).isEqualTo(200);
                assertThat(setupBeforeConnect.body()).contains("YouTube").contains("TVs and Limited Input")
                        .contains("YouTube on Cast devices (experimental)").contains("Kitchen");

                // 2. Connecting stores the OAuth client, sets the login password and starts the
                // device-code flow.
                HttpResponse<String> connected = send(browser, post("/setup/sources/youtube/connect", Map.of(
                        "clientId", "123456789012-abc123def456.apps.googleusercontent.com",
                        "clientSecret", "GOCSPX-fixtureClientSecret",
                        "loginPassword", "correct-horse-1", "loginPasswordConfirmation", "correct-horse-1")));
                assertThat(connected.statusCode()).isEqualTo(302);
                assertThat(connected.headers().firstValue("Set-Cookie")).isPresent();
                assertThat(GOOGLE.requests("/oauth/device/code")).isNotEmpty();

                // 3. Poll the authorization fragment until the background poller reports CONNECTED.
                List<String> earlierAuthorizationBodies = new ArrayList<>();
                HttpResponse<String>[] authorizationHolder = new HttpResponse[1];
                await().atMost(Duration.ofSeconds(10)).until(() -> {
                    authorizationHolder[0] = send(browser, get("/setup/sources/youtube/authorization"));
                    if ("true".equals(authorizationHolder[0].headers().firstValue("HX-Refresh").orElse(null))) {
                        return true;
                    }
                    earlierAuthorizationBodies.add(authorizationHolder[0].body());
                    return false;
                });
                assertThat(String.join("\n", earlierAuthorizationBodies)).contains("GQVQ-JKEC");
                assertThat(GOOGLE.requests("/oauth/token")).anyMatch(r ->
                        "urn:ietf:params:oauth:grant-type:device_code".equals(r.form().get("grant_type")));
                await().atMost(Duration.ofSeconds(5)).until(() -> GOOGLE.requests("/youtube/v3/channels").stream()
                        .anyMatch(r -> "true".equals(r.query().get("mine"))));

                // 4. Everything is gated for a client without the session cookie.
                assertThat(send(stranger, get("/sources/youtube/rails/subscriptions")).statusCode()).isEqualTo(401);
                assertThat(send(stranger, page("/")).statusCode()).isEqualTo(302);

                // 5. The connected channel and quota now show on the setup page; the background
                // poller may not have flipped to CONNECTED the instant step 3's await returned, so
                // poll here too instead of asserting once.
                HttpResponse<String>[] setupHolder = new HttpResponse[1];
                await().atMost(Duration.ofSeconds(5)).until(() -> {
                    setupHolder[0] = send(browser, page("/setup"));
                    return setupHolder[0].body().contains("Connected as Andre at Home");
                });
                assertThat(setupHolder[0].body()).contains("of 10000 units");

                // 6. Refresh the subscriptions rail; poll until it is READY (D1's rail cache).
                assertThat(send(browser, post("/sources/youtube/rails/subscriptions/refresh", Map.of())).statusCode()).isEqualTo(202);
                HttpResponse<String>[] railHolder = new HttpResponse[1];
                await().atMost(Duration.ofSeconds(10)).until(() -> {
                    railHolder[0] = send(browser, get("/sources/youtube/rails/subscriptions"));
                    return railHolder[0].statusCode() == 200 && "READY".equals(json(railHolder[0]).path("status").asString(""));
                });
                JsonNode rail = json(railHolder[0]);
                List<String> itemIds = new ArrayList<>();
                rail.path("items").forEach(item -> itemIds.add(item.path("id").asString("")));
                assertThat(itemIds).containsExactly("Kz1aT5nM3pQ", "Pm6Jd3Fg0kU", "aqz-KE-bpKQ", "Hh7Lq2Wv9sE");
                assertThat(rail.toString()).contains("/sources/youtube/thumbnails/Kz1aT5nM3pQ");

                // 7. Thumbnails are proxied through Home Control, never straight to Google.
                HttpResponse<String> thumbnail = send(browser, get("/sources/youtube/thumbnails/aqz-KE-bpKQ"));
                assertThat(thumbnail.statusCode()).isEqualTo(200);
                assertThat(thumbnail.headers().firstValue("Content-Type")).hasValue("image/jpeg");

                // 8. Typing does not use quota; the on-demand button does, once, then is cached.
                // The on-demand search has side effects (it spends quota), so it is a POST — a
                // stranger's cross-site request must be refused before it ever reaches YouTube.
                HttpResponse<String> unifiedSearch = send(browser, get("/search/results?q=bunny"));
                assertThat(unifiedSearch.body()).contains("Search YouTube").contains("20 of 20 YouTube searches left today");
                assertThat(GOOGLE.count("/youtube/v3/search")).isZero();
                HttpRequest.Builder crossSite = post("/search/results/youtube", Map.of("q", "bunny"))
                        .setHeader("Origin", "http://evil.example");
                assertThat(send(browser, crossSite).statusCode()).isEqualTo(403);
                assertThat(GOOGLE.count("/youtube/v3/search")).isZero();
                HttpResponse<String> onDemandSearch = send(browser, post("/search/results/youtube", Map.of("q", "bunny")));
                assertThat(onDemandSearch.body()).contains("data-item=\"aqz-KE-bpKQ\"");
                assertThat(GOOGLE.count("/youtube/v3/search")).isEqualTo(1);
                send(browser, post("/search/results/youtube", Map.of("q", "bunny")));
                assertThat(GOOGLE.count("/youtube/v3/search")).isEqualTo(1);

                // 9. Shield: the app-link route, opened in the real YouTube app.
                HttpResponse<String> onShield = send(browser, post("/devices/shield/play-attempt",
                        Map.of("source", "youtube", "item", "aqz-KE-bpKQ")));
                assertThat(onShield.statusCode()).isEqualTo(200);
                JsonNode shieldResult = json(onShield);
                assertThat(shieldResult.path("played").asBoolean(false)).isTrue();
                assertThat(shieldResult.path("route").path("key").asString("")).isEqualTo("app-link");
                assertThat(shieldResult.path("route").path("description").asString("")).isEqualTo("Open in the YouTube app");
                assertThat(shieldRemote.nextAppLink()).isEqualTo("https://www.youtube.com/watch?v=aqz-KE-bpKQ");

                // 10. Kitchen (Cast-only, YouTube Cast still off): no route.
                HttpResponse<String> kitchenPreviewBefore = send(browser, get("/devices/kitchen/route-preview?source=youtube&item=aqz-KE-bpKQ"));
                JsonNode previewBefore = json(kitchenPreviewBefore);
                assertThat(previewBefore.path("playable").asBoolean(true)).isFalse();
                assertThat(previewBefore.path("reason").asString("")).contains("this device cannot open app links");

                // 11. Switch YouTube Cast on for Kitchen; the Lounge route now appears.
                assertThat(send(browser, post("/setup/sources/youtube/lounge", Map.of("device", "kitchen", "enabled", "true")))
                        .statusCode()).isEqualTo(302);
                HttpResponse<String> kitchenPreviewAfter = send(browser, get("/devices/kitchen/route-preview?source=youtube&item=aqz-KE-bpKQ"));
                JsonNode previewAfter = json(kitchenPreviewAfter);
                assertThat(previewAfter.path("route").path("key").asString("")).isEqualTo("youtube-lounge");
                assertThat(previewAfter.path("route").path("description").asString(""))
                        .isEqualTo("Cast with the YouTube receiver (best effort)");

                // 12. Kitchen: best-effort Lounge play through the real Cast socket.
                GOOGLE.loungeAccepts();
                HttpResponse<String> onKitchen = send(browser, post("/devices/kitchen/play-attempt",
                        Map.of("source", "youtube", "item", "aqz-KE-bpKQ")));
                assertThat(onKitchen.statusCode()).isEqualTo(200);
                assertThat(json(onKitchen).path("played").asBoolean(false)).isTrue();
                assertThat(kitchenCast.runningAppId()).isEqualTo(YouTubeLoungeRouteExecutor.RECEIVER_APP_ID);
                assertThat(kitchenCast.received(YouTubeLoungeRouteExecutor.MDX_NAMESPACE, "getMdxSessionStatus")).isNotEmpty();
                List<FakeGoogleServer.Recorded> binds = GOOGLE.requests("/lounge/bc/bind");
                assertThat(binds).hasSize(2);
                assertThat(binds.get(1).form()).containsEntry("req0_videoId", "aqz-KE-bpKQ");

                // 13. Google rejects the lounge token: an explicit, honest failure, not a retry.
                GOOGLE.respondWhen("POST", "/lounge/bc/bind", r -> "1".equals(r.query().get("RID")),
                        FakeGoogleServer.Canned.json(401, "{}"));
                HttpResponse<String> failedKitchen = send(browser, post("/devices/kitchen/play-attempt",
                        Map.of("source", "youtube", "item", "aqz-KE-bpKQ")));
                assertThat(failedKitchen.statusCode()).isEqualTo(502);
                assertThat(json(failedKitchen).path("message").asString("")).isEqualTo("Kitchen: YouTube Cast (best effort, "
                        + "unofficial API) failed: bind: YouTube rejected the lounge token. Use the YouTube app route, "
                        + "or switch YouTube Cast off for this device in Setup.");

                // 14. Quota reflects exactly the calls made: 2 channels.list + 2 subscriptions.list
                // + 3 playlistItems.list (7 units) + 1 search.list (100 units) = 107 units; 1 search used.
                assertThat(send(browser, page("/setup")).body()).contains("107 of 10000 units").contains("1 of 20 searches");

                // 15. Disconnecting revokes the grant and removes only the YouTube secrets, so a
                // device-only deployment (no other secret) no longer requires login; the Lounge
                // pairing for Kitchen survives.
                assertThat(send(browser, post("/setup/sources/youtube/disconnect", Map.of())).statusCode()).isEqualTo(302);
                assertThat(GOOGLE.requests("/oauth/revoke")).anyMatch(
                        r -> "1//0gFixtureRefreshTokenGranted-0001".equals(r.form().get("token")));
                HttpResponse<String> setupAfterDisconnect = send(browser, page("/setup"));
                assertThat(setupAfterDisconnect.body()).contains("OAuth client ID");
                assertThat(send(stranger, page("/")).statusCode()).isEqualTo(200);
                assertThat(send(browser, page("/setup")).body()).contains("Kitchen: YouTube Cast on");

                // 16. No secret ever reached the browser, in any response, or the application log.
                for (String secret : SECRETS) {
                    assertThat(browserBodies).as("browser body carrying " + secret).noneMatch(body -> body.contains(secret));
                    assertThat(output.getAll()).as("log carrying " + secret).doesNotContain(secret);
                }
            } finally {
                devices.forget("shield");
                devices.forget("kitchen");
            }
        }
    }
}
