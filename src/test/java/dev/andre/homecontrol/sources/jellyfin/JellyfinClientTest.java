package dev.andre.homecontrol.sources.jellyfin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JellyfinClientTest {

    private final JellyfinClient client = new JellyfinClient(new JellyfinProperties(true, 2, 5, 20), "0.8.0");
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
    void readsPublicSystemInfo() throws IOException {
        fake = new FakeJellyfinServer().respond("GET", "/System/Info/Public", 200, "system-info-public.json");

        JsonNode info = client.publicInfo(fake.url());

        assertThat(info.path("Id").asString()).isEqualTo("4e1a2b3c4d5e4f60718293a4b5c6d7e8");
        assertThat(info.path("ServerName").asString()).isEqualTo("nas");
        FakeJellyfinServer.Recorded recorded = fake.last("GET", "/System/Info/Public");
        assertThat(recorded.header("authorization")).doesNotContain("Token=");
    }

    @Test
    void refusesAServerThatIsNotJellyfin() throws IOException {
        fake = new FakeJellyfinServer().respondJson("GET", "/System/Info/Public", 200, "{\"hello\":\"world\"}");
        var preparedArg57_0 = fake.url();
        assertThatThrownBy(() -> client.publicInfo(preparedArg57_0))
                .isInstanceOf(JellyfinException.class)
                .extracting(e -> ((JellyfinException) e).kind())
                .isEqualTo(JellyfinException.Kind.NOT_JELLYFIN);
        assertThatThrownBy(() -> client.publicInfo(fake.url()))
                .hasMessage(fake.url() + " answered, but it is not a Jellyfin server");
        fake.close();

        fake = new FakeJellyfinServer().respondBytes("GET", "/System/Info/Public", 200, "text/html",
                "<html>".getBytes());
        var preparedArg67_0 = fake.url();
        assertThatThrownBy(() -> client.publicInfo(preparedArg67_0))
                .isInstanceOf(JellyfinException.class)
                .extracting(e -> ((JellyfinException) e).kind())
                .isEqualTo(JellyfinException.Kind.NOT_JELLYFIN);
        fake.close();

        fake = new FakeJellyfinServer();
        var preparedArg74_0 = fake.url();
        assertThatThrownBy(() -> client.publicInfo(preparedArg74_0))
                .isInstanceOf(JellyfinException.class)
                .extracting(e -> ((JellyfinException) e).kind())
                .isEqualTo(JellyfinException.Kind.NOT_JELLYFIN);
    }

    @Test
    void refusesJellyfinOlderThan10_9() throws IOException {
        fake = new FakeJellyfinServer().respond("GET", "/System/Info/Public", 200, "system-info-public-old.json");

        var preparedArg84_0 = fake.url();
        assertThatThrownBy(() -> client.publicInfo(preparedArg84_0))
                .isInstanceOf(JellyfinException.class)
                .extracting(e -> ((JellyfinException) e).kind())
                .isEqualTo(JellyfinException.Kind.UNSUPPORTED_VERSION);
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

        var preparedArg112_0 = fake.url();
        assertThatThrownBy(() -> client.authenticateByName(preparedArg112_0, "dev-1", "andre", "wrong"))
                .isInstanceOf(JellyfinException.class)
                .extracting(e -> ((JellyfinException) e).kind())
                .isEqualTo(JellyfinException.Kind.UNAUTHORIZED);
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
                .isEqualTo(JellyfinException.Kind.UNAUTHORIZED);
        assertThatThrownBy(() -> client.get(connection, "/b", Map.of()))
                .isInstanceOf(JellyfinException.class)
                .extracting(e -> ((JellyfinException) e).kind())
                .isEqualTo(JellyfinException.Kind.NOT_FOUND);
        assertThatThrownBy(() -> client.get(connection, "/c", Map.of()))
                .isInstanceOf(JellyfinException.class)
                .extracting(e -> ((JellyfinException) e).kind())
                .isEqualTo(JellyfinException.Kind.SERVER_ERROR);
        assertThatThrownBy(() -> client.get(connection, "/d", Map.of()))
                .isInstanceOf(JellyfinException.class)
                .extracting(e -> ((JellyfinException) e).kind())
                .isEqualTo(JellyfinException.Kind.BAD_RESPONSE);
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
                .isEqualTo(JellyfinException.Kind.UNREACHABLE);
        assertThatThrownBy(() -> client.get(connection, "/x", Map.of()))
                .hasMessageStartingWith("Could not reach Jellyfin at http://127.0.0.1:")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("secret-token-xyz"));
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
                    .isEqualTo(JellyfinException.Kind.INVALID_INPUT);
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
                .isEqualTo(JellyfinException.Kind.BAD_RESPONSE);
        assertThatThrownBy(() -> client.get(connection, "/big", Map.of()))
                .hasMessage("Jellyfin at " + fake.url() + " sent an oversized response");
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
        var preparedArg218_0 = fake.url();
        assertThatThrownBy(() -> client.image(preparedArg218_0, itemId, "Primary", null, 480))
                .isInstanceOf(JellyfinException.class)
                .extracting(e -> ((JellyfinException) e).kind())
                .isEqualTo(JellyfinException.Kind.BAD_RESPONSE);
        fake.close();

        // A raster-only allowlist: an SVG served from our own origin could carry a script.
        fake = new FakeJellyfinServer().respondBytes("GET", "/Items/" + itemId + "/Images/Primary", 200,
                "image/svg+xml", "<svg onload=\"alert(1)\"></svg>".getBytes());
        var preparedArg227_0 = fake.url();
        assertThatThrownBy(() -> client.image(preparedArg227_0, itemId, "Primary", null, 480))
                .isInstanceOf(JellyfinException.class)
                .extracting(e -> ((JellyfinException) e).kind())
                .isEqualTo(JellyfinException.Kind.BAD_RESPONSE);
        fake.close();

        fake = new FakeJellyfinServer().respondBytes("GET", "/Items/" + itemId + "/Images/Primary", 200,
                "image/jpeg", new byte[JellyfinClient.MAX_IMAGE_BYTES + 1]);
        var preparedArg235_0 = fake.url();
        assertThatThrownBy(() -> client.image(preparedArg235_0, itemId, "Primary", null, 480))
                .isInstanceOf(JellyfinException.class)
                .extracting(e -> ((JellyfinException) e).kind())
                .isEqualTo(JellyfinException.Kind.BAD_RESPONSE);
    }

    @Test
    void idsAreValidatedBeforeTheyBecomePathSegments() {
        assertThat(JellyfinClient.id("a1b2-C3")).isEqualTo("a1b2-C3");

        for (String invalid : new String[] {"../Users", "", "a/b"}) {
            assertThatThrownBy(() -> JellyfinClient.id(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
