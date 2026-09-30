package dev.andre.homecontrol.web;

import dev.andre.homecontrol.adapters.androidtv.AndroidTvSettings;
import dev.andre.homecontrol.adapters.androidtv.CertificateStore;
import dev.andre.homecontrol.adapters.androidtv.protocol.FakeRemoteServer;
import dev.andre.homecontrol.core.DeviceEnrollment;
import dev.andre.homecontrol.core.DeviceQueries;
import dev.andre.homecontrol.core.content.ContentSources;
import dev.andre.homecontrol.sources.tmdb.FakeTmdbServer;
import dev.andre.homecontrol.testsupport.FullAppTest;
import dev.andre.homecontrol.testsupport.RailHtml;
import dev.andre.homecontrol.testsupport.SharedFakes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Streaming launchers (epic G) through the whole application over real sockets: fake TMDB ↔ the
 * TMDB source ↔ pinned shortcuts ↔ resolver ↔ planner ↔ a fake Android TV (Shield) over the real
 * Remote v2 protocol, with the login gate in front. Connect → set language/region/services → the
 * trending rail (only titles on the household's own services) → preview and play a trending item
 * ("open the app, not the title") → pin its real Netflix link to upgrade it → pin a Prime Video
 * share link directly → an ad-hoc web link → search → disconnect and reconnect with an API key —
 * checking at every step the exact app-link string the Shield receives and that no credential
 * ever reaches a browser response or the stored non-secret settings.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StreamingLaunchersEndToEndTest extends FullAppTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    static final String LOGIN = "household password";
    static final String GTI = "amzn1.dv.gti.8eb3c4a1-1b2c-4d5e-9f60-718293a4b5c6";
    static final FakeTmdbServer TMDB = SharedFakes.tmdb();

    @BeforeAll
    static void routes() {
        TMDB.withStandardResponses();
    }

    @LocalServerPort
    int port;

    @Autowired
    DeviceQueries devices;

    @Autowired
    DeviceEnrollment enrollment;

    @Autowired
    CertificateStore certificates;

    @Autowired
    ContentSources sources;

    /** Shared across both @Order methods so the second reuses the first's logged-in session. */
    private static final HttpClient browser = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
    private static final HttpClient stranger = HttpClient.newHttpClient();
    private static final List<String> browserBodies = new ArrayList<>();

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
        return postPairs(path, form.entrySet().stream()
                .map(e -> Map.entry(e.getKey(), e.getValue())).toList());
    }

    /** Allows a repeated form key, e.g. several {@code providers} checkboxes. */
    private HttpRequest.Builder postPairs(String path, List<Map.Entry<String, String>> pairs) {
        String body = pairs.stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private HttpRequest.Builder getJson(String path) {
        return get(path).header("Accept", "application/json");
    }

    private static JsonNode json(HttpResponse<String> response) {
        return MAPPER.readTree(response.body());
    }

    // browserBodies is the recorded actual value; java:S3415 mistakes the static final list for an expected constant.
    @SuppressWarnings("java:S3415")
    private static void assertNoBrowserBodyCarriesTheCredential() {
        assertThat(browserBodies).noneMatch(body ->
                body.contains(FakeTmdbServer.READ_TOKEN) || body.contains("api_key") || body.contains("Bearer"));
    }

    // One journey (connect, launch, upgrade, pin, disconnect); each step needs the state the previous one left.
    @SuppressWarnings("java:S5961")
    @Test
    @Order(1)
    void launchTrendingTitlesAndUpgradeThemWithPinnedLinks() throws Exception {
        try (FakeRemoteServer shieldRemote = new FakeRemoteServer()) {
            certificates.loadOrCreate("shield-e2e");
            enrollment.adopt(AndroidTvSettings.device("shield-e2e", "Shield", "127.0.0.1", shieldRemote.port(), null, Instant.now()));
            await().atMost(Duration.ofSeconds(5)).until(() -> devices.state("shield-e2e").connected());

            try {
                // 2. Device-only: the setup page opens without login.
                assertThat(send(stranger, page("/setup")).statusCode()).isEqualTo(200);

                // 3. Connecting TMDB stores the credential, sets the login password and logs this browser in.
                HttpResponse<String> connected = send(browser, post("/setup/sources/tmdb", Map.of(
                        "credential", FakeTmdbServer.READ_TOKEN, "loginPassword", LOGIN, "loginPasswordConfirmation", LOGIN)));
                assertThat(connected.statusCode()).isEqualTo(302);
                assertThat(TMDB.last("GET", "/3/authentication").header("authorization")).isEqualTo("Bearer " + FakeTmdbServer.READ_TOKEN);
                HttpResponse<String> setupAfterConnect = send(browser, page("/setup"));
                assertThat(setupAfterConnect.body()).contains("Connected (read access token)").contains("This product uses the TMDB API");
                assertThat(send(stranger, get("/rails")).statusCode()).isEqualTo(401);

                // 4. Language, region and the household's streaming services.
                assertThat(send(browser, postPairs("/setup/sources/preferences/locale", List.of(
                        Map.entry("locale", "de-DE"), Map.entry("region", "DE"),
                        Map.entry("providers", "netflix"), Map.entry("providers", "primevideo")))).statusCode())
                        .isEqualTo(302);

                // 5. Refresh the trending rail; poll until it is READY (D1's rail cache).
                assertThat(send(browser, post("/rails/tmdb/trending/refresh", Map.of())).statusCode()).isEqualTo(200);
                HttpResponse<String>[] railHolder = new HttpResponse[1];
                await().atMost(Duration.ofSeconds(10)).until(() -> {
                    railHolder[0] = send(browser, get("/rails/tmdb/trending"));
                    return "READY".equals(RailHtml.status(railHolder[0].body()));
                });
                String railBody = railHolder[0].body();
                assertThat(railBody).contains("Stranger Things").contains("On Netflix · 2016")
                        .contains("The Boys").contains("On Prime Video · 2019")
                        .contains("https://image.tmdb.org/t/p/w342/49WJfeN0moxb9IPfGn8AIqMGskD.jpg");
                assertThat(railBody).doesNotContain("Matrix").doesNotContain("Fight Club")
                        .doesNotContain("House of the Dragon").doesNotContain("playables").doesNotContain("AppLink");

                // 6. Preview: the trending item only opens Netflix's app home, so a pin is offered.
                HttpResponse<String> preview = send(browser, getJson("/devices/shield-e2e/route-preview?source=tmdb&item=tv-66732"));
                JsonNode previewJson = json(preview);
                assertThat(previewJson.path("route").path("description").asString(""))
                        .isEqualTo("Open the Netflix app (not this title)");
                assertThat(previewJson.path("pin").path("upgradeOf").asString("")).isEqualTo("tmdb/tv-66732");
                assertThat(previewJson.path("pin").path("serviceName").asString("")).isEqualTo("Netflix");

                // 7. Playing it opens the Netflix app home on the Shield.
                HttpResponse<String> played = send(browser, post("/devices/shield-e2e/play-attempt",
                        Map.of("source", "tmdb", "item", "tv-66732")));
                assertThat(played.statusCode()).isEqualTo(200);
                assertThat(json(played).path("played").asBoolean(false)).isTrue();
                assertThat(shieldRemote.nextAppLink()).isEqualTo("https://www.netflix.com/browse");

                // 8. Paste the real Netflix title link to upgrade it.
                HttpResponse<String> upgraded = send(browser, post("/setup/sources/pinned/upgrade", Map.of(
                        "url", "https://www.netflix.com/de/title/80057281?s=a&trkid=13747225", "upgradeOf", "tmdb/tv-66732")));
                assertThat(upgraded.statusCode()).isEqualTo(200);
                assertThat(json(upgraded).path("title").asString("")).isEqualTo("Stranger Things");

                // 9. Preview and play again: now it opens the title directly, and no pin offer remains.
                JsonNode previewAfterUpgrade = json(send(browser, getJson("/devices/shield-e2e/route-preview?source=tmdb&item=tv-66732")));
                assertThat(previewAfterUpgrade.path("route").path("description").asString("")).isEqualTo("Open in the Netflix app");
                assertThat(previewAfterUpgrade.path("pin").isMissingNode() || previewAfterUpgrade.path("pin").isNull()).isTrue();
                send(browser, post("/devices/shield-e2e/play-attempt", Map.of("source", "tmdb", "item", "tv-66732")));
                assertThat(shieldRemote.nextAppLink()).isEqualTo("https://www.netflix.com/title/80057281");

                // 10. Pin a Prime Video share link directly, without upgrading a TMDB item this time.
                // The pinned rail refreshes on its own through the ContentChangedEvent.
                assertThat(send(browser, post("/setup/sources/pinned", Map.of(
                        "url", "https://www.primevideo.com/region/eu/detail/" + GTI + "/ref=atv_dp_share_cu_r",
                        "title", "The Boys"))).statusCode()).isEqualTo(302);
                HttpResponse<String>[] pinnedHolder = new HttpResponse[1];
                await().atMost(Duration.ofSeconds(10)).until(() -> {
                    pinnedHolder[0] = send(browser, get("/rails/pinned/pinned"));
                    return "READY".equals(RailHtml.status(pinnedHolder[0].body()))
                            && pinnedHolder[0].body().contains("Stranger Things") && pinnedHolder[0].body().contains("The Boys");
                });
                String primePinId = RailHtml.tiles(pinnedHolder[0].body()).stream()
                        .filter(tile -> "The Boys".equals(tile.get("data-title"))).map(tile -> tile.get("data-item"))
                        .findFirst().orElse(null);
                assertThat(primePinId).isNotBlank();

                // 11. Playing the pinned Prime Video item opens its title directly.
                HttpResponse<String> playedPinned = send(browser, post("/devices/shield-e2e/play-attempt",
                        Map.of("source", "pinned", "item", primePinId)));
                assertThat(playedPinned.statusCode()).isEqualTo(200);
                assertThat(shieldRemote.nextAppLink()).isEqualTo("https://app.primevideo.com/detail?gti=" + GTI);

                // 12. Ad-hoc "open a link on this device" form (sub-project A).
                HttpResponse<String> adHoc = send(browser, post("/devices/shield-e2e/play",
                        Map.of("uri", "https://www.amazon.de/gp/video/detail/B0B8TJ4WQS/ref=atv_dp")));
                assertThat(adHoc.statusCode()).isEqualTo(200);
                assertThat(adHoc.body()).isEqualTo("Open in the Prime Video app");
                assertThat(shieldRemote.nextAppLink()).isEqualTo("https://www.amazon.de/gp/video/detail/B0B8TJ4WQS");

                // 13. Unified search reaches TMDB with the configured language.
                HttpResponse<String> search = send(browser, get("/search/results?q=matrix"));
                assertThat(search.body()).contains("Matrix").contains("tmdb");
                assertThat(TMDB.last("GET", "/3/search/multi").query()).containsEntry("language", "de-DE");

                // 14. Pinned shortcuts and secrets on disk.
                JsonNode pinnedFile = MAPPER.readTree(Files.readAllBytes(dataDir().resolve("pinned.json")));
                assertThat(pinnedFile.path("pins")).hasSize(2);
                JsonNode strangerThingsPin = null;
                for (JsonNode pin : pinnedFile.path("pins")) {
                    if ("tmdb/tv-66732".equals(pin.path("upgradeOf").asString(""))) {
                        strangerThingsPin = pin;
                    }
                }
                assertThat(strangerThingsPin).isNotNull();
                assertThat(strangerThingsPin.path("url").asString("")).isEqualTo("https://www.netflix.com/title/80057281");
                String secretsText = Files.readString(dataDir().resolve("secrets.json"));
                assertThat(secretsText).doesNotContain(FakeTmdbServer.READ_TOKEN);

                // 15. Nothing the browser received ever carried the credential.
                assertNoBrowserBodyCarriesTheCredential();

                // 16. Disconnect: the login stays, and the pinned rail (no secret) still answers.
                assertThat(send(browser, post("/setup/sources/tmdb/disconnect", Map.of())).statusCode()).isEqualTo(302);
                assertThat(sources.find("tmdb").orElseThrow().available()).isFalse();
                assertThat(send(browser, get("/rails/pinned/pinned")).statusCode()).isEqualTo(200);
                assertThat(send(stranger, page("/setup")).statusCode()).isEqualTo(302);
            } finally {
                enrollment.forget("shield-e2e");
            }
        }
    }

    @Test
    @Order(2)
    void reconnectingWithAnApiKeyUsesTheQueryParameter() throws Exception {
        // Disconnecting TMDB at the end of the previous test kept the login, and this browser is still logged in,
        // so reconnecting stores the credential under it; the login password fields are not needed.
        HttpResponse<String> reconnected = send(browser, post("/setup/sources/tmdb", Map.of(
                "credential", FakeTmdbServer.API_KEY, "loginPassword", LOGIN, "loginPasswordConfirmation", LOGIN)));
        assertThat(reconnected.statusCode()).isEqualTo(302);
        FakeTmdbServer.Recorded lastAuth = TMDB.last("GET", "/3/authentication");
        assertThat(lastAuth.query()).containsEntry("api_key", FakeTmdbServer.API_KEY);
        assertThat(lastAuth.header("authorization")).isNull();

        assertThat(sources.find("tmdb").orElseThrow().available()).isTrue();
    }
}
