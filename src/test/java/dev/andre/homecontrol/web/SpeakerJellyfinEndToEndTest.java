package dev.andre.homecontrol.web;

import dev.andre.homecontrol.adapters.upnp.FakeUpnpRenderer;
import dev.andre.homecontrol.core.DeviceStatus;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static dev.andre.homecontrol.sources.jellyfin.FakeJellyfinServer.ACCESS_TOKEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * A Jellyfin track on a DLNA speaker through the real application: rail, route preview, the direct
 * stream with its API key on the device, now playing — and the key never reaching the browser.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SpeakerJellyfinEndToEndTest {

    static final String TRACK = "c0ffee00c0ffee00c0ffee00c0ffee01";
    static final String LOGIN = "household password";
    static final String ID = "speaker-e2e";

    @DynamicPropertySource
    static void isolated(DynamicPropertyRegistry registry) throws IOException {
        Path dataDir = Files.createTempDirectory("speaker-jellyfin-e2e");
        registry.add("shield.data-dir", dataDir::toString);
        registry.add("home-control.ssdp.enabled", () -> "false");
        registry.add("home-control.upnp.poll-interval-seconds", () -> "1");
        registry.add("home-control.upnp.idle-poll-interval-seconds", () -> "1");
    }

    @LocalServerPort
    int port;

    @Autowired
    DeviceManager devices;

    private final CookieManager cookies = new CookieManager();
    private final HttpClient browser = HttpClient.newBuilder().cookieHandler(cookies).build();
    private final List<String> browserBodies = new CopyOnWriteArrayList<>();

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

    private HttpRequest.Builder post(String path, Map<String, String> form) {
        String body = form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    /** Collects what the browser's event stream delivers for {@code window}, on its own client with the browser's cookies. */
    private void listenToEvents(Duration window) throws Exception {
        HttpClient stream = HttpClient.newBuilder().cookieHandler(cookies).build();
        List<String> lines = new CopyOnWriteArrayList<>();
        try {
            HttpResponse<Stream<String>> events = stream.send(get("/events").header("Accept", "text/event-stream").build(),
                    HttpResponse.BodyHandlers.ofLines());
            assertThat(events.statusCode()).isEqualTo(200);
            Thread reader = Thread.ofVirtual().start(() -> events.body().forEach(lines::add));
            reader.join(window);
        } finally {
            // shutdownNow, not close: an SSE stream never ends by itself.
            stream.shutdownNow();
        }
        browserBodies.add(String.join("\n", lines));
    }

    @Test
    void playsAJellyfinTrackOnASpeakerWithoutLeakingTheToken() throws Exception {
        try (FakeJellyfinServer jellyfin = new FakeJellyfinServer().withConnectableServer()
                     .respond("GET", "/Items", 200, "music-recent.json")
                     .respond("GET", "/Sessions", 200, "sessions.json")
                     .respond("GET", "/Items/" + TRACK, 200, "item-track.json")
                     .respond("POST", "/Items/" + TRACK + "/PlaybackInfo", 200, "playback-info-audio.json");
             FakeUpnpRenderer speaker = new FakeUpnpRenderer()) {
            devices.adopt(speaker.device(ID));
            try {
                HttpResponse<String> connected = send(browser, post("/setup/sources/jellyfin", Map.of(
                        "serverUrl", jellyfin.url().toString(), "mode", "password", "userName", "andre",
                        "password", "user password", "loginPassword", LOGIN, "loginPasswordConfirmation", LOGIN)));
                assertThat(connected.statusCode()).isEqualTo(302);
                await().atMost(Duration.ofSeconds(10)).until(() -> devices.state(ID).status() == DeviceStatus.CONNECTED);

                // The rail cache answers while it loads in the background: poll until the rail is there.
                await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(send(browser, get("/sources/jellyfin/rails/music-recent")).body())
                        .contains("Bunny Song").contains("The Rabbits"));
                assertThat(send(browser, get("/devices/" + ID + "/route?source=jellyfin&item=" + TRACK)).body())
                        .isEqualTo("Stream directly to this device (DLNA/UPnP)");

                HttpResponse<String> played = send(browser, post("/devices/" + ID + "/play", Map.of("source", "jellyfin", "item", TRACK)));
                assertThat(played.statusCode()).isEqualTo(200);
                assertThat(speaker.currentUri()).startsWith(jellyfin.url() + "/Audio/" + TRACK + "/stream.flac?static=true")
                        .contains("ApiKey=" + ACCESS_TOKEN); // the device needs the key
                assertThat(speaker.currentMetadata()).contains("<dc:title>Bunny Song</dc:title>")
                        .contains("<upnp:artist>The Rabbits</upnp:artist>");
                await().atMost(Duration.ofSeconds(10)).until(() -> devices.state(ID).nowPlaying() != null
                        && devices.state(ID).nowPlaying().title().equals("Bunny Song"));

                listenToEvents(Duration.ofSeconds(3));
                assertThat(browserBodies).anyMatch(body -> body.contains("Bunny Song"));
                // The server notices the closed stream only when it next writes to it: a few state
                // changes let it drop the stream, so shutdown does not wait for it.
                for (int volume = 31; volume <= 34; volume++) {
                    int wanted = volume;
                    speaker.setVolume(wanted);
                    await().atMost(Duration.ofSeconds(10)).until(() -> devices.state(ID).volumeLevel() == wanted);
                }
                assertThat(browserBodies).noneMatch(body -> body.contains(ACCESS_TOKEN) || body.contains("ApiKey"));
            } finally {
                devices.forget(ID);
            }
        }
    }
}
