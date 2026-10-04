package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.core.content.ContentSourceException;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class JellyfinClientTest {

    private final JellyfinClient client = new JellyfinClient(new JellyfinProperties(true, Duration.ofSeconds(2),
            Duration.ofSeconds(5), 20, Duration.ofSeconds(30)), "0.8.0");
    private final JsonMapper mapper = JsonMapper.builder().build();
    private FakeJellyfinServer fake;

    @AfterEach
    void closeFake() {
        if (fake != null) {
            fake.close();
        }
    }

    @Test
    void sendsTheMediaBrowserAuthorizationHeader() throws IOException {
        fake = new FakeJellyfinServer().respond("GET", "/Users/" + FakeJellyfinServer.USER_ID, 200, "user.json");

        client.get(new JellyfinConnection(fake.url(), "tok-1", "dev-1", FakeJellyfinServer.USER_ID),
                "/Users/" + FakeJellyfinServer.USER_ID, Map.of());

        FakeJellyfinServer.Recorded recorded = fake.last("GET", "/Users/" + FakeJellyfinServer.USER_ID);
        assertThat(recorded.header("authorization")).isEqualTo(
                "MediaBrowser Client=\"Home+Control\", Device=\"Home+Control\", DeviceId=\"dev-1\", Version=\"0.8.0\", Token=\"tok-1\"");
        assertThat(recorded.header("accept")).isEqualTo("application/json");
    }

    @Test
    void artworkThatFillsEveryImageSlotLeavesTheApiFree() throws IOException {
        String itemId = "b1c2d3e4f5061728394a5b6c7d8e9f01";
        CountDownLatch release = new CountDownLatch(1);
        fake = new FakeJellyfinServer().holdImage(itemId, release)
                .respond("GET", "/System/Info/Public", 200, "system-info-public.json");
        try (ExecutorService posters = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                for (int i = 0; i < JellyfinClient.MAX_CONCURRENT; i++) {
                    posters.submit(() -> client.image(fake.url(), itemId, "Primary", null, 480));
                }
                await().until(() -> fake.requests("GET", "/Items/" + itemId + "/Images/Primary").size()
                        == JellyfinClient.MAX_CONCURRENT);

                long started = System.nanoTime();
                JsonNode info = client.publicInfo(fake.url());

                assertThat(info.path("ServerName").asString()).isEqualTo("nas");
                assertThat(Duration.ofNanos(System.nanoTime() - started)).as("did not wait for an image slot")
                        .isLessThan(Duration.ofSeconds(2));
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void readsPublicSystemInfo() throws IOException {
        fake = new FakeJellyfinServer().respond("GET", "/System/Info/Public", 200, "system-info-public.json");

        JsonNode info = client.publicInfo(fake.url());

        assertThat(info.path("Id").asString()).isEqualTo("4e1a2b3c4d5e4f60718293a4b5c6d7e8");
        assertThat(info.path("ServerName").asString()).isEqualTo("nas");
        FakeJellyfinServer.Recorded recorded = fake.last("GET", "/System/Info/Public");
        assertThat(recorded.header("authorization")).doesNotContain("Token=");
    }

    @Test
    void aRedirectingServerIsToldToUseItsFinalAddress() throws IOException {
        // A Base URL, or a proxy that sends http to https: it is Jellyfin, at another address.
        fake = new FakeJellyfinServer().respondJson("GET", "/System/Info/Public", 302, null);
        var redirecting = fake.url();

        assertThatThrownBy(() -> client.publicInfo(redirecting))
                .isInstanceOf(JellyfinException.class)
                .hasMessage("Jellyfin at " + redirecting + " redirected elsewhere; enter the final server address");
    }

    @Test
    void refusesAServerThatIsNotJellyfin() throws IOException {
        fake = new FakeJellyfinServer().respondJson("GET", "/System/Info/Public", 200, "{\"hello\":\"world\"}");
        var notJellyfinUrl = fake.url();
        assertThatThrownBy(() -> client.publicInfo(notJellyfinUrl))
                .isInstanceOf(JellyfinException.class)
                .extracting(e -> ((JellyfinException) e).kind())
                .isEqualTo(ContentSourceException.Kind.BAD_RESPONSE);
        assertThatThrownBy(() -> client.publicInfo(fake.url()))
                .hasMessage(fake.url() + " answered, but it is not a Jellyfin server");
        fake.close();

        fake = new FakeJellyfinServer().respondBytes("GET", "/System/Info/Public", 200, "text/html",
                "<html>".getBytes());
        var htmlPageUrl = fake.url();
        assertThatThrownBy(() -> client.publicInfo(htmlPageUrl))
                .isInstanceOf(JellyfinException.class)
                .extracting(e -> ((JellyfinException) e).kind())
                .isEqualTo(ContentSourceException.Kind.BAD_RESPONSE);
        fake.close();

        fake = new FakeJellyfinServer();
        var silentServerUrl = fake.url();
        assertThatThrownBy(() -> client.publicInfo(silentServerUrl))
                .isInstanceOf(JellyfinException.class)
                .extracting(e -> ((JellyfinException) e).kind())
                .isEqualTo(ContentSourceException.Kind.BAD_RESPONSE);
    }

    @Test
    void refusesJellyfinOlderThan10_9() throws IOException {
        fake = new FakeJellyfinServer().respond("GET", "/System/Info/Public", 200, "system-info-public-old.json");

        var oldServerUrl = fake.url();
        assertThatThrownBy(() -> client.publicInfo(oldServerUrl))
                .isInstanceOf(JellyfinException.class)
                .extracting(e -> ((JellyfinException) e).kind())
                .isEqualTo(ContentSourceException.Kind.BAD_RESPONSE);
        assertThatThrownBy(() -> client.publicInfo(fake.url()))
                .hasMessageContaining("10.8.13")
                .hasMessageContaining("10.9");
    }

    @Test
    void authenticatesByNameWithTheDocumentedBody() throws IOException {
        fake = new FakeJellyfinServer().respond("POST", "/Users/AuthenticateByName", 200, "authenticate-by-name.json");

        JsonNode result = client.authenticateByName(fake.url(), "dev-1", "andre", "pa ss\"word");

        FakeJellyfinServer.Recorded recorded = fake.last("POST", "/Users/AuthenticateByName");
        JsonNode body = mapper.readTree(recorded.body());
        assertThat(body.path("Username").asString()).isEqualTo("andre");
        assertThat(body.path("Pw").asString()).isEqualTo("pa ss\"word");
        assertThat(recorded.header("content-type")).startsWith("application/json");
        assertThat(recorded.header("authorization")).doesNotContain("Token=");
        assertThat(result.path("AccessToken").asString()).isNotBlank();
    }

    @Test
    void aRejectedLoginIsNamed() throws IOException {
        fake = new FakeJellyfinServer().respondJson("POST", "/Users/AuthenticateByName", 401, "{}");

        var serverUrl = fake.url();
        assertThatThrownBy(() -> client.authenticateByName(serverUrl, "dev-1", "andre", "wrong"))
                .isInstanceOf(JellyfinException.class)
                .extracting(e -> ((JellyfinException) e).kind())
                .isEqualTo(ContentSourceException.Kind.UNAUTHORIZED);
        assertThatThrownBy(() -> client.authenticateByName(fake.url(), "dev-1", "andre", "wrong"))
                .hasMessage("Jellyfin rejected the user name or password");
    }

    @Test
    void mapsStatusesToNamedErrors() throws IOException {
        fake = new FakeJellyfinServer();
        fake.respondJson("GET", "/a", 401, "{}");
        fake.respondJson("GET", "/b", 404, "{}");
        fake.respondJson("GET", "/c", 500, "{}");
        fake.respondJson("GET", "/d", 302, "{}");
        fake.respondJson("GET", "/e", 204, null);
        JellyfinConnection connection = new JellyfinConnection(fake.url(), "tok", "dev", "user");

        assertThatThrownBy(() -> client.get(connection, "/a", Map.of()))
                .isInstanceOf(JellyfinException.class)
                .extracting(e -> ((JellyfinException) e).kind())
                .isEqualTo(ContentSourceException.Kind.UNAUTHORIZED);
        assertThatThrownBy(() -> client.get(connection, "/b", Map.of()))
                .isInstanceOf(JellyfinException.class)
                .extracting(e -> ((JellyfinException) e).kind())
                .isEqualTo(ContentSourceException.Kind.NOT_FOUND);
        assertThatThrownBy(() -> client.get(connection, "/c", Map.of()))
                .isInstanceOf(JellyfinException.class)
                .extracting(e -> ((JellyfinException) e).kind())
                .isEqualTo(ContentSourceException.Kind.SERVER_ERROR);
        assertThatThrownBy(() -> client.get(connection, "/d", Map.of()))
                .isInstanceOf(JellyfinException.class)
                .extracting(e -> ((JellyfinException) e).kind())
                .isEqualTo(ContentSourceException.Kind.BAD_RESPONSE);
        assertThat(fake.requests("GET", "/d")).hasSize(1);

        JsonNode node = client.get(connection, "/e", Map.of());
        assertThat(node.isMissingNode()).isTrue();
    }

    @Test
    void namesAnUnreachableServerWithoutLeakingTheToken() throws IOException {
        URI dead;
        try (ServerSocket socket = new ServerSocket(0)) {
            dead = URI.create("http://127.0.0.1:" + socket.getLocalPort());
        }
        JellyfinConnection connection = new JellyfinConnection(dead, "secret-token-xyz", "dev", "user");

        assertThatThrownBy(() -> client.get(connection, "/x", Map.of()))
                .isInstanceOf(JellyfinException.class)
                .extracting(e -> ((JellyfinException) e).kind())
                .isEqualTo(ContentSourceException.Kind.UNREACHABLE);
        assertThatThrownBy(() -> client.get(connection, "/x", Map.of()))
                .hasMessageStartingWith("Could not reach Jellyfin at 127.0.0.1 (")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("secret-token-xyz"));
    }

    @Test
    void namesAServerGivenByNameWithoutLeakingTheToken() throws IOException {
        URI dead;
        try (ServerSocket socket = new ServerSocket(0)) {
            dead = URI.create("http://localhost:" + socket.getLocalPort());
        }
        JellyfinConnection connection = new JellyfinConnection(dead, "secret-token-xyz", "dev", "user");

        assertThatThrownBy(() -> client.get(connection, "/x", Map.of()))
                .isInstanceOf(JellyfinException.class)
                .hasMessageStartingWith("Could not reach Jellyfin at localhost (")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("secret-token-xyz"))
                .hasNoCause();
    }

    @Test
    void normalizesServerUrls() {
        assertThat(JellyfinClient.normalizeServerUrl("http://nas:8096/")).isEqualTo(URI.create("http://nas:8096"));
        assertThat(JellyfinClient.normalizeServerUrl("https://h.example/jellyfin/")).isEqualTo(URI.create("https://h.example/jellyfin"));
        assertThat(JellyfinClient.normalizeServerUrl(" http://10.0.0.2:8096 ")).isEqualTo(URI.create("http://10.0.0.2:8096"));

        for (String invalid : new String[] {"nas:8096", "ftp://nas", "http://nas/?a=1", "http://nas/#x", "http://user@nas", "", null}) {
            assertThatThrownBy(() -> JellyfinClient.normalizeServerUrl(invalid))
                    .isInstanceOf(JellyfinException.class)
                    .extracting(e -> ((JellyfinException) e).kind())
                    .isEqualTo(ContentSourceException.Kind.INVALID_INPUT);
        }
    }

    @Test
    void aResponseBiggerThanTheCapIsRejectedWithoutBufferingItAll() throws IOException {
        fake = new FakeJellyfinServer().respondBytes("GET", "/big", 200, "application/json; charset=utf-8",
                new byte[JellyfinClient.MAX_JSON_BYTES + 1]);
        JellyfinConnection connection = new JellyfinConnection(fake.url(), "tok", "dev", "user");

        assertThatThrownBy(() -> client.get(connection, "/big", Map.of()))
                .isInstanceOf(JellyfinException.class)
                .extracting(e -> ((JellyfinException) e).kind())
                .isEqualTo(ContentSourceException.Kind.TOO_LARGE);
        assertThatThrownBy(() -> client.get(connection, "/big", Map.of()))
                .hasMessage("Jellyfin at 127.0.0.1 sent more than 2 MB");
    }

    @Test
    void fetchesImagesAnonymously() throws IOException {
        String itemId = "b1c2d3e4f5061728394a5b6c7d8e9f01";
        fake = new FakeJellyfinServer().respondBytes("GET", "/Items/" + itemId + "/Images/Primary", 200,
                "image/jpeg", new byte[] {1, 2, 3});

        JellyfinClient.Image image = client.image(fake.url(), itemId, "Primary", "c0ffeec0ffee", 480).orElseThrow();

        assertThat(image.contentType()).isEqualTo("image/jpeg");
        assertThat(image.bytes()).containsExactly(1, 2, 3);
        FakeJellyfinServer.Recorded recorded = fake.last("GET", "/Items/" + itemId + "/Images/Primary");
        assertThat(recorded.header("authorization")).isNull();
        assertThat(recorded.query()).isEqualTo(Map.of("maxWidth", "480", "quality", "90", "tag", "c0ffeec0ffee"));
        fake.close();

        fake = new FakeJellyfinServer();
        assertThat(client.image(fake.url(), itemId, "Primary", null, 480)).isEmpty();
        fake.close();

        fake = new FakeJellyfinServer().respondBytes("GET", "/Items/" + itemId + "/Images/Primary", 200,
                "text/html", "<html>".getBytes());
        var htmlImageServerUrl = fake.url();
        assertThatThrownBy(() -> client.image(htmlImageServerUrl, itemId, "Primary", null, 480))
                .isInstanceOf(JellyfinException.class)
                .extracting(e -> ((JellyfinException) e).kind())
                .isEqualTo(ContentSourceException.Kind.BAD_RESPONSE);
        fake.close();

        // A raster-only allowlist: an SVG served from our own origin could carry a script.
        fake = new FakeJellyfinServer().respondBytes("GET", "/Items/" + itemId + "/Images/Primary", 200,
                "image/svg+xml", "<svg onload=\"alert(1)\"></svg>".getBytes());
        var svgImageServerUrl = fake.url();
        assertThatThrownBy(() -> client.image(svgImageServerUrl, itemId, "Primary", null, 480))
                .isInstanceOf(JellyfinException.class)
                .extracting(e -> ((JellyfinException) e).kind())
                .isEqualTo(ContentSourceException.Kind.BAD_RESPONSE);
        fake.close();

        fake = new FakeJellyfinServer().respondBytes("GET", "/Items/" + itemId + "/Images/Primary", 200,
                "image/jpeg", new byte[JellyfinClient.MAX_IMAGE_BYTES + 1]);
        var oversizedImageServerUrl = fake.url();
        assertThatThrownBy(() -> client.image(oversizedImageServerUrl, itemId, "Primary", null, 480))
                .isInstanceOf(JellyfinException.class)
                .extracting(e -> ((JellyfinException) e).kind())
                .isEqualTo(ContentSourceException.Kind.TOO_LARGE);
    }

    @Test
    void idsAreValidatedBeforeTheyBecomePathSegments() {
        assertThat(JellyfinClient.id("a1b2-C3")).isEqualTo("a1b2-C3");

        for (String invalid : new String[] {"../Users", "", "a/b"}) {
            assertThatThrownBy(() -> JellyfinClient.id(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    /** Jellyfin often runs on the same machine: loopback needs no setting. */
    @Test
    void aServerOnThisMachineIsReachedWithoutASetting() throws IOException {
        fake = new FakeJellyfinServer().withConnectableServer();

        assertThatCode(() -> client.publicInfo(fake.url())).doesNotThrowAnyException();
    }

    @Test
    void aLinkLocalServerIsBlocked() {
        URI linkLocal = URI.create("http://169.254.10.20:8096");

        assertThatThrownBy(() -> client.publicInfo(linkLocal))
                .isInstanceOfSatisfying(JellyfinException.class,
                        e -> assertThat(e.kind()).isEqualTo(ContentSourceException.Kind.BLOCKED))
                .hasMessage("Home Control does not connect to 169.254.10.20 (address not allowed)");
    }

    @Test
    void anImageMayBeLargerThanAnAnswer() throws IOException {
        byte[] poster = new byte[3 * 1024 * 1024];
        fake = new FakeJellyfinServer().respondBytes("GET", "/Items/abc123/Images/Primary", 200, "image/jpeg", poster);

        assertThat(client.image(fake.url(), "abc123", "Primary", null, 300))
                .hasValueSatisfying(image -> assertThat(image.bytes()).hasSize(poster.length));
    }

    @Test
    void anotherMediaServerIsNotJellyfin() throws IOException {
        fake = new FakeJellyfinServer().respondJson("GET", "/System/Info/Public", 200,
                "{\"Id\":\"emby-1\",\"ProductName\":\"Emby Server\",\"Version\":\"4.8.0\"}");

        assertThatThrownBy(() -> client.publicInfo(fake.url()))
                .isInstanceOf(JellyfinException.class)
                .hasMessage(fake.url() + " answered, but it is not a Jellyfin server");
    }

    @Test
    void aServerCheckThatFailsForAnotherReasonKeepsItsReason() throws IOException {
        fake = new FakeJellyfinServer().respondJson("GET", "/System/Info/Public", 401, "{}");

        assertThatThrownBy(() -> client.publicInfo(fake.url()))
                .isInstanceOfSatisfying(JellyfinException.class,
                        e -> assertThat(e.kind()).isEqualTo(ContentSourceException.Kind.UNAUTHORIZED))
                .hasMessageNotContaining("not a Jellyfin server");
    }

    @Test
    void anUnreadableOrOldMajorVersionIsTooOldAndANewerOneIsAccepted() throws IOException {
        fake = new FakeJellyfinServer().respondJson("GET", "/System/Info/Public", 200,
                "{\"Id\":\"s\",\"ProductName\":\"Jellyfin Server\",\"Version\":\"unknown\"}");
        assertThatThrownBy(() -> client.publicInfo(fake.url()))
                .hasMessage("Jellyfin unknown is too old; Home Control needs Jellyfin 10.9 or newer");

        fake.respondJson("GET", "/System/Info/Public", 200, "{\"Id\":\"s\",\"Version\":\"9.12.0\"}");
        assertThatThrownBy(() -> client.publicInfo(fake.url())).hasMessageContaining("9.12.0 is too old");

        fake.respondJson("GET", "/System/Info/Public", 200, "{\"Id\":\"s\",\"Version\":\"11.0.1\"}");
        assertThat(client.publicInfo(fake.url()).path("Version").asString()).isEqualTo("11.0.1");
    }

    @Test
    void aLoginThatFailsForAnotherReasonKeepsItsReason() throws IOException {
        fake = new FakeJellyfinServer().respondJson("POST", "/Users/AuthenticateByName", 500, "{}");

        assertThatThrownBy(() -> client.authenticateByName(fake.url(), "dev", "andre", "pw"))
                .isInstanceOfSatisfying(JellyfinException.class,
                        e -> assertThat(e.kind()).isEqualTo(ContentSourceException.Kind.SERVER_ERROR))
                .hasMessageNotContaining("user name or password");
    }

    @Test
    void anImageAnswerWithoutAContentTypeOrNot200IsNoImage() throws IOException {
        String itemId = "b1c2d3e4f5061728394a5b6c7d8e9f01";
        fake = new FakeJellyfinServer().respondBytes("GET", "/Items/" + itemId + "/Images/Primary", 200, null,
                new byte[] {1, 2, 3});
        assertThatThrownBy(() -> client.image(fake.url(), itemId, "Primary", null, 480))
                .isInstanceOf(JellyfinException.class).hasMessage("Jellyfin at " + fake.url() + " sent no image");

        fake.respondBytes("GET", "/Items/" + itemId + "/Images/Primary", 500, "image/jpeg", new byte[] {1});
        assertThatThrownBy(() -> client.image(fake.url(), itemId, "Primary", null, 480))
                .isInstanceOf(JellyfinException.class).hasMessage("Jellyfin at " + fake.url() + " sent no image");
    }

    @Test
    void aQueryValueThatIsNullIsLeftOut() throws IOException {
        fake = new FakeJellyfinServer().respondJson("GET", "/Items", 200, "{}");
        Map<String, String> query = new HashMap<>();
        query.put("userId", "u1");
        query.put("parentId", null);

        client.get(new JellyfinConnection(fake.url(), "tok", "dev", "u1"), "/Items", query);

        assertThat(fake.last("GET", "/Items").query()).isEqualTo(Map.of("userId", "u1"));
    }
}
