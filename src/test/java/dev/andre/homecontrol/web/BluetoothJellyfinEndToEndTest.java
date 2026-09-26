package dev.andre.homecontrol.web;

import dev.andre.homecontrol.adapters.bluetooth.BluetoothSettings;
import dev.andre.homecontrol.adapters.bluetooth.bluez.BluetoothDeviceInfo;
import dev.andre.homecontrol.adapters.bluetooth.bluez.BluezClient;
import dev.andre.homecontrol.adapters.bluetooth.bluez.FakeBluezClient;
import dev.andre.homecontrol.adapters.bluetooth.player.FakeMpvScript;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.sources.jellyfin.FakeJellyfinServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static dev.andre.homecontrol.sources.jellyfin.FakeJellyfinServer.ACCESS_TOKEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * A Jellyfin track on a Bluetooth speaker through the real application: the speaker has no IP, so
 * rung 1 (open Jellyfin app) finds no session; it is neither a Cast receiver nor a media renderer,
 * so the stream is built from the server's own address and the local rung (LocalAudioSinkStrategy)
 * plays it — proving the key never reaches the browser, only the player's IPC loadfile command.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(BluetoothJellyfinEndToEndTest.FakeBluez.class)
class BluetoothJellyfinEndToEndTest {

    static final String TRACK = "c0ffee00c0ffee00c0ffee00c0ffee01";
    static final String ID = "bluetooth-aa-bb-cc-dd-ee-ff";
    static final String ADDRESS = "AA:BB:CC:DD:EE:FF";
    static final String LOGIN = "household password";
    static final FakeBluezClient BLUEZ = new FakeBluezClient();
    static final Path ROOT;
    static final Path LOG;
    static final Path MPV;

    static {
        try {
            ROOT = Files.createTempDirectory("bluetooth-jellyfin-e2e");
            LOG = ROOT.resolve("mpv-log.jsonl");
            Files.createFile(ROOT.resolve("system_bus_socket"));
            MPV = FakeMpvScript.create(ROOT.resolve("bin"), Map.of(
                    "FAKE_MPV_LOG", LOG.toString(),
                    "FAKE_MPV_DURATION", "600",
                    "FAKE_MPV_DEVICES", "pulse/bluez_output.AA_BB_CC_DD_EE_FF.1=JBL Flip 5"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        BLUEZ.known(ADDRESS, "JBL Flip 5").paired(true).connected(true).uuids(BluetoothDeviceInfo.A2DP_SINK);
    }

    @TestConfiguration
    static class FakeBluez {
        @Bean
        @Primary
        BluezClient fakeBluezClient() {
            return BLUEZ;
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("shield.data-dir", () -> ROOT.resolve("data").toString());
        registry.add("home-control.ssdp.enabled", () -> "false");
        registry.add("home-control.bluetooth.enabled", () -> "true");
        registry.add("home-control.bluetooth.dbus-address", () -> "unix:path=" + ROOT.resolve("system_bus_socket"));
        registry.add("home-control.bluetooth.mpv-path", MPV::toString);
        registry.add("home-control.bluetooth.runtime-dir", () -> ROOT.resolve("run").toString());
        registry.add("home-control.bluetooth.scan-seconds", () -> "1");
        registry.add("home-control.bluetooth.poll-interval-seconds", () -> "1");
        registry.add("home-control.bluetooth.playing-poll-interval-seconds", () -> "1");
        registry.add("home-control.bluetooth.player-start-timeout-seconds", () -> "20");
        registry.add("home-control.bluetooth.load-timeout-seconds", () -> "10");
        registry.add("home-control.bluetooth.command-timeout-seconds", () -> "3");
        registry.add("home-control.bluetooth.host-check-cache-seconds", () -> "1");
    }

    @AfterAll
    static void tearDown() {
        BLUEZ.close();
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
            stream.shutdownNow();
        }
        browserBodies.add(String.join("\n", lines));
    }

    private List<List<String>> ipcCommands() throws IOException {
        List<List<String>> commands = new ArrayList<>();
        for (JsonNode line : FakeMpvScript.log(LOG)) {
            if ("command".equals(line.path("type").asString(""))) {
                List<String> command = new ArrayList<>();
                line.path("command").forEach(a -> command.add(a.asString("")));
                commands.add(command);
            }
        }
        return commands;
    }

    private boolean anyFakeMpvAlive() throws IOException {
        for (JsonNode line : FakeMpvScript.log(LOG)) {
            if (!"start".equals(line.path("type").asString(""))) {
                continue;
            }
            long pid = line.path("pid").asLong(-1);
            if (pid > 0 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void playsAJellyfinTrackOnABluetoothSpeakerWithoutLeakingTheToken() throws Exception {
        try (FakeJellyfinServer jellyfin = new FakeJellyfinServer().withConnectableServer()
                     .respond("GET", "/Items", 200, "music-recent.json")
                     .respond("GET", "/Sessions", 200, "sessions.json")
                     .respond("GET", "/Items/" + TRACK, 200, "item-track.json")
                     .respond("POST", "/Items/" + TRACK + "/PlaybackInfo", 200, "playback-info-audio.json")) {
            devices.adopt(new Device(ID, "JBL Flip 5", DeviceKind.BLUETOOTH, ADDRESS,
                    Map.of("bluetooth", new BluetoothSettings(ADDRESS, FakeBluezClient.ADAPTER, "").toMap()), Instant.now()));
            try {
                HttpResponse<String> connected = send(browser, post("/setup/sources/jellyfin", Map.of(
                        "serverUrl", jellyfin.url().toString(), "mode", "password", "userName", "andre",
                        "password", "user password", "loginPassword", LOGIN, "loginPasswordConfirmation", LOGIN)));
                assertThat(connected.statusCode()).isEqualTo(302);
                await().atMost(Duration.ofSeconds(20)).until(() -> devices.state(ID).status() == DeviceStatus.CONNECTED);

                // The rail cache answers while it loads in the background: poll until the rail is there.
                await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                        assertThat(send(browser, get("/sources/jellyfin/rails/music-recent")).body()).contains("Bunny Song"));
                assertThat(send(browser, get("/devices/" + ID + "/route?source=jellyfin&item=" + TRACK)).body())
                        .isEqualTo("Play through the server on this Bluetooth speaker");
                HttpResponse<String> played = send(browser, post("/devices/" + ID + "/play", Map.of("source", "jellyfin", "item", TRACK)));
                assertThat(played.statusCode()).isEqualTo(200);
                List<List<String>> loads = new ArrayList<>();
                await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                    loads.clear();
                    loads.addAll(ipcCommands().stream().filter(command -> command.getFirst().equals("loadfile")).toList());
                    assertThat(loads).isNotEmpty();
                });
                assertThat(loads).singleElement().satisfies(load -> assertThat(load.get(1))
                        .startsWith(jellyfin.url() + "/Audio/" + TRACK + "/stream.flac?static=true")
                        .contains("ApiKey=" + ACCESS_TOKEN));                      // the player needs the key …
                List<JsonNode> starts = FakeMpvScript.log(LOG).stream().filter(l -> "start".equals(l.path("type").asString(""))).toList();
                assertThat(starts).isNotEmpty().allSatisfy(start -> assertThat(start.toString())
                        .doesNotContain("ApiKey").doesNotContain("/Audio/"));      // … but never on the command line
                await().atMost(Duration.ofSeconds(20)).until(() -> devices.state(ID).nowPlaying() != null
                        && devices.state(ID).nowPlaying().title().equals("Bunny Song"));

                listenToEvents(Duration.ofSeconds(3));
                assertThat(browserBodies).noneMatch(body -> body.contains(ACCESS_TOKEN) || body.contains("ApiKey"));
            } finally {
                devices.forget(ID);
            }
        }
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(anyFakeMpvAlive()).isFalse());
    }
}
