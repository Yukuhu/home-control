package dev.andre.homecontrol.web;

import dev.andre.homecontrol.adapters.androidtv.AndroidTvSettings;
import dev.andre.homecontrol.adapters.androidtv.protocol.CertificateStore;
import dev.andre.homecontrol.adapters.androidtv.protocol.FakeRemoteServer;
import dev.andre.homecontrol.adapters.cast.protocol.CastIncoming;
import dev.andre.homecontrol.adapters.cast.protocol.FakeCastReceiver;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.sources.jellyfin.FakeJellyfinServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static dev.andre.homecontrol.sources.jellyfin.FakeJellyfinServer.ACCESS_TOKEN;
import static dev.andre.homecontrol.sources.jellyfin.FakeJellyfinServer.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Jellyfin through the real application over real sockets: fake Jellyfin ↔ source ↔ resolver ↔
 * planner ↔ Android TV and Cast adapters ↔ HTTP, with the login gate in front.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class JellyfinEndToEndTest {

    static final String EPISODE = "3f2a9c1e7b6d4e5f8a9b0c1d2e3f4a5b";
    static final String SHIELD_SESSION = "1d2c3b4a59687f6e5d4c3b2a19081726";
    static final String SHIELD_JELLYFIN_DEVICE = "b2c4d6e8f0a1c3e5";
    static final String LOGIN = "household password";
    static Path dataDir;

    @DynamicPropertySource
    static void isolatedAndFast(DynamicPropertyRegistry registry) throws IOException {
        dataDir = Files.createTempDirectory("jellyfin-e2e");
        registry.add("shield.data-dir", dataDir::toString);
        registry.add("home-control.cast.command-timeout-seconds", () -> "3");
        registry.add("home-control.cast.load-timeout-seconds", () -> "5");
    }

    @LocalServerPort
    int port;

    @Autowired
    DeviceManager devices;

    @Autowired
    CertificateStore certificates;

    private final HttpClient browser = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
    private final HttpClient stranger = HttpClient.newHttpClient();
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
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    @Test
    void connectBrowseAndPlayThroughEveryRouteWithoutLeakingTheToken() throws Exception {
        try (FakeJellyfinServer jellyfin = new FakeJellyfinServer().withConnectableServer()
                     .respond("GET", "/UserItems/Resume", 200, "resume.json")
                     .respond("GET", "/Items", 200, "search.json")
                     .respond("GET", "/Sessions", 200, "sessions.json")
                     .respond("GET", "/Items/" + EPISODE, 200, "item-episode.json")
                     .respond("POST", "/Items/" + EPISODE + "/PlaybackInfo", 200, "playback-info-direct.json")
                     .respondJson("POST", "/Sessions/" + SHIELD_SESSION + "/Playing", 204, null)
                     .respondBytes("GET", "/Items/" + EPISODE + "/Images/Primary", 200, "image/jpeg", new byte[]{1, 2, 3});
             FakeRemoteServer shieldRemote = new FakeRemoteServer();
             FakeCastReceiver kitchenCast = new FakeCastReceiver()) {

            certificates.loadOrCreate("shield-e2e");
            devices.adopt(AndroidTvSettings.device("shield-e2e", "Shield", "127.0.0.1", shieldRemote.port(), null, Instant.now()));
            kitchenCast.appSpeaks("F007D354", "urn:x-cast:com.connectsdk");
            devices.adopt(new Device("kitchen-e2e", "Kitchen", DeviceKind.CAST, "127.0.0.1",
                    Map.of("cast", Map.of("port", String.valueOf(kitchenCast.port()))), Instant.now()));
            try {
                // Device-only: open without login.
                assertThat(send(stranger, page("/setup")).statusCode()).isEqualTo(200);

                // Connecting Jellyfin sets the login password and logs this browser in.
                HttpResponse<String> connected = send(browser, post("/setup/sources/jellyfin", Map.of(
                        "serverUrl", jellyfin.url().toString(), "mode", "password", "userName", "andre",
                        "password", "user password", "loginPassword", LOGIN, "loginPasswordConfirmation", LOGIN)));
                assertThat(connected.statusCode()).isEqualTo(302);
                assertThat(send(browser, page("/setup")).body()).contains("Connected to nas");

                // From now on everything is gated for other clients, images and SSE included.
                assertThat(send(stranger, get("/sources")).statusCode()).isEqualTo(401);
                assertThat(send(stranger, get("/events")).statusCode()).isEqualTo(401);
                assertThat(send(stranger, get("/sources/jellyfin/images/" + EPISODE + "/Primary?tag=1a2b3c4d5e6f")).statusCode()).isEqualTo(401);
                assertThat(send(stranger, page("/setup")).statusCode()).isEqualTo(302);

                // Rails, artwork and search for the logged-in browser. The rail cache (D1) answers
                // the first request while it loads in the background, so poll until it is READY.
                HttpResponse<String>[] railHolder = new HttpResponse[1];
                await().atMost(Duration.ofSeconds(5)).until(() -> {
                    railHolder[0] = send(browser, get("/sources/jellyfin/rails/resume"));
                    return railHolder[0].statusCode() == 200;
                });
                HttpResponse<String> rail = railHolder[0];
                assertThat(rail.statusCode()).isEqualTo(200);
                assertThat(rail.body()).contains("Northern Lights").contains("/sources/jellyfin/images/" + EPISODE + "/Primary?tag=1a2b3c4d5e6f");
                assertThat(send(browser, get("/sources/jellyfin/images/" + EPISODE + "/Primary?tag=1a2b3c4d5e6f")).statusCode()).isEqualTo(200);
                assertThat(jellyfin.last("GET", "/Items/" + EPISODE + "/Images/Primary").header("authorization")).isNull();
                HttpResponse<String> search = send(browser, get("/search?q=bunny"));
                assertThat(search.body()).contains("Big Buck Bunny").contains("Meadow Tales").contains("Bunny Song");

                // Rung 1: the Shield's Jellyfin app is linked on the setup page, then commanded.
                assertThat(send(browser, post("/setup/sources/jellyfin/links",
                        Map.of("session", SHIELD_JELLYFIN_DEVICE, "device", "shield-e2e"))).statusCode()).isEqualTo(302);
                // The app is now closed and the Shield asleep. Preview must still offer Play,
                // without waking the device or sending anything to Jellyfin.
                await().atMost(Duration.ofSeconds(5)).until(() -> devices.state("shield-e2e").connected());
                shieldRemote.pushPower(false);
                shieldRemote.pushCurrentApp("com.google.android.tvlauncher");
                jellyfin.respondJson("GET", "/Sessions", 200, "[]");
                await().until(() -> !devices.state("shield-e2e").powerOn()
                        && "com.google.android.tvlauncher".equals(devices.state("shield-e2e").currentApp()));
                HttpResponse<String> shieldPreview = send(browser, get("/devices/shield-e2e/route-preview?source=jellyfin&item=" + EPISODE));
                assertThat(shieldPreview.statusCode()).isEqualTo(200);
                assertThat(shieldPreview.body()).contains("\"playable\":true", "jellyfin-app");
                assertThat(shieldRemote.receivedKeyPresses()).isEmpty();
                assertThat(jellyfin.requests("POST", "/Sessions/" + SHIELD_SESSION + "/Playing")).isEmpty();

                CompletableFuture<HttpResponse<String>> starting = CompletableFuture.supplyAsync(() -> {
                    try {
                        return send(browser, post("/devices/shield-e2e/play-attempt", Map.of("source", "jellyfin", "item", EPISODE)));
                    } catch (Exception e) {
                        throw new CompletionException(e);
                    }
                });
                assertThat(shieldRemote.nextKeyPress()).isEqualTo(224);
                assertThat(starting).isNotDone();
                shieldRemote.pushPower(true);
                assertThat(shieldRemote.nextAppLink()).isEqualTo("market://launch?id=org.jellyfin.androidtv");
                assertThat(starting).isNotDone();
                assertThat(jellyfin.requests("POST", "/Sessions/" + SHIELD_SESSION + "/Playing")).isEmpty();
                shieldRemote.pushCurrentApp("org.jellyfin.androidtv");
                jellyfin.respond("GET", "/Sessions", 200, "sessions.json");
                HttpResponse<String> onShield = starting.get(5, TimeUnit.SECONDS);
                assertThat(onShield.statusCode()).isEqualTo(200);
                assertThat(onShield.body()).contains("\"played\":true", "jellyfin-app");
                assertThat(jellyfin.last("POST", "/Sessions/" + SHIELD_SESSION + "/Playing").query()).containsExactlyInAnyOrderEntriesOf(Map.of(
                        "playCommand", "PlayNow", "itemIds", EPISODE, "startPositionTicks", "6120000000"));
                assertThat(jellyfin.requests("POST", "/Sessions/" + SHIELD_SESSION + "/Playing")).hasSize(1);

                assertThat(send(stranger, post("/setup/sources/jellyfin/players",
                        Map.of("device", "shield-e2e", "player", "vlc"))).statusCode()).isIn(302, 401);
                // VLC preference persists independently of a running Jellyfin session.
                assertThat(send(browser, post("/setup/sources/jellyfin/players",
                        Map.of("device", "shield-e2e", "player", "vlc"))).statusCode()).isEqualTo(302);
                assertThat(send(browser, page("/setup")).body()).contains("Device playback", "VLC");
                jellyfin.respondJson("GET", "/Sessions", 200, "[]");
                jellyfin.respond("POST", "/Items/" + EPISODE + "/PlaybackInfo", 200, "playback-info-direct.json");
                shieldRemote.pushPower(false);
                shieldRemote.pushCurrentApp("com.google.android.tvlauncher");
                await().until(() -> !devices.state("shield-e2e").powerOn());
                HttpResponse<String> vlcPreview = send(browser, get("/devices/shield-e2e/route-preview?source=jellyfin&item=" + EPISODE));
                assertThat(vlcPreview.body()).contains("jellyfin-vlc", "VLC").doesNotContain(ACCESS_TOKEN);
                assertThat(jellyfin.requests("POST", "/Items/" + EPISODE + "/PlaybackInfo")).isEmpty();
                CompletableFuture<HttpResponse<String>> vlcStarting = CompletableFuture.supplyAsync(() -> {
                    try {
                        return send(browser, post("/devices/shield-e2e/play-attempt", Map.of("source", "jellyfin", "item", EPISODE)));
                    } catch (Exception e) {
                        throw new CompletionException(e);
                    }
                });
                assertThat(shieldRemote.nextKeyPress()).isEqualTo(224);
                shieldRemote.pushPower(true);
                String vlcLink = shieldRemote.nextAppLink();
                assertThat(vlcLink).startsWith("vlc://" + jellyfin.url() + "/Videos/" + EPISODE + "/stream.mkv?")
                        .contains("static=false", "startTimeTicks=6120000000", "videoCodec=copy", "audioCodec=copy",
                                "mediaSourceId=" + EPISODE, "api_key=" + ACCESS_TOKEN);
                assertThat(vlcStarting.get(5, TimeUnit.SECONDS).body()).contains("jellyfin-vlc", "\"optimistic\":true");
                assertThat(jellyfin.requests("POST", "/Sessions/" + SHIELD_SESSION + "/Playing")).hasSize(1);

                // Rung 3 (spec §5.3): the Kitchen Cast device has no Jellyfin app, so the Jellyfin receiver gets the request.
                await().until(() -> devices.state("kitchen-e2e").connected());
                HttpResponse<String> preview = send(browser, get("/devices/kitchen-e2e/route?source=jellyfin&item=" + EPISODE));
                assertThat(preview.body()).isEqualTo("Cast with the Jellyfin receiver");
                assertThat(kitchenCast.received("urn:x-cast:com.connectsdk", "")).isEmpty();
                HttpResponse<String> onKitchen = send(browser, post("/devices/kitchen-e2e/play", Map.of("source", "jellyfin", "item", EPISODE)));
                assertThat(onKitchen.statusCode()).isEqualTo(200);
                assertThat(onKitchen.body()).isEqualTo("Cast with the Jellyfin receiver");
                CastIncoming request = kitchenCast.received("urn:x-cast:com.connectsdk", "").getLast();
                assertThat(request.payload().path("command").asString("")).isEqualTo("PlayNow");
                assertThat(request.payload().path("accessToken").asString("")).isEqualTo(ACCESS_TOKEN);
                assertThat(request.payload().path("userId").asString("")).isEqualTo(USER_ID);
                assertThat(request.payload().path("serverAddress").asString("")).isEqualTo(jellyfin.url().toString());
                assertThat(request.payload().path("options").path("items").path(0).path("Id").asString("")).isEqualTo(EPISODE);
                assertThat(request.payload().path("options").path("startPositionTicks").asLong(0)).isEqualTo(6_120_000_000L);

                // Nothing the browser received, and nothing in sources.json, holds the token.
                assertThat(browserBodies).noneMatch(body -> body.contains(ACCESS_TOKEN) || body.contains("ApiKey"));
                assertThat(Files.readString(dataDir.resolve("sources.json"))).doesNotContain(ACCESS_TOKEN);
                assertThat(Files.readString(dataDir.resolve("secrets.json"))).doesNotContain(ACCESS_TOKEN);

                // Disconnecting removes the last secret: the deployment is device-only again.
                assertThat(send(browser, post("/setup/sources/jellyfin/disconnect", Map.of())).statusCode()).isEqualTo(302);
                assertThat(jellyfin.requests("POST", "/Sessions/Logout")).isNotEmpty();
                assertThat(send(stranger, page("/setup")).statusCode()).isEqualTo(200);
            } finally {
                devices.forget("shield-e2e");
                devices.forget("kitchen-e2e");
            }
        }
    }
}
