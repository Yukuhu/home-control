package dev.andre.homecontrol.web;

import com.sun.net.httpserver.HttpServer;
import dev.andre.homecontrol.adapters.net.FakeWakeOnLanReceiver;
import dev.andre.homecontrol.adapters.net.FakeWebSocketServer;
import dev.andre.homecontrol.adapters.net.WakeOnLan;
import dev.andre.homecontrol.adapters.webos.FakeSsapServer;
import dev.andre.homecontrol.adapters.webos.WebOsAdapter;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceRegistry;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.discovery.ssdp.FakeSsdpResponder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * An LG TV through the whole application with real sockets: SSDP discovery, prompt pairing on the
 * setup page, keys, inputs, app links, the deep-link test, Wake-on-LAN and forgetting.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebOsEndToEndTest {

    static final FakeSsapServer TV;
    static final FakeSsdpResponder SSDP;
    static final HttpServer DESCRIPTIONS;
    static final FakeWakeOnLanReceiver WOL;
    static final Path DATA;
    static final String ID = "webos-127-0-0-1";

    static {
        try {
            TV = new FakeSsapServer(false);
            WOL = new FakeWakeOnLanReceiver();
            DESCRIPTIONS = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            DESCRIPTIONS.createContext("/lg/description.xml", exchange -> {
                byte[] body = Files.readAllBytes(Path.of("src/test/resources/fixtures/ssdp/lg-description.xml"));
                exchange.getResponseHeaders().add("Content-Type", "text/xml");
                exchange.sendResponseHeaders(200, body.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            DESCRIPTIONS.start();
            SSDP = new FakeSsdpResponder();
            SSDP.answer(WebOsAdapter.SEARCH_TARGET,
                    FakeSsdpResponder.fixture("lg-search-response.txt", "127.0.0.1", DESCRIPTIONS.getAddress().getPort()));
            DATA = Files.createTempDirectory("webos-e2e");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("shield.data-dir", DATA::toString);
        registry.add("home-control.ssdp.enabled", () -> "true");
        registry.add("home-control.ssdp.multicast-address", () -> "127.0.0.1");
        registry.add("home-control.ssdp.port", SSDP::port);
        registry.add("home-control.ssdp.listen-port", () -> "0");
        registry.add("home-control.ssdp.search-interval-seconds", () -> "1");
        registry.add("home-control.webos.port", TV::port);
        registry.add("home-control.webos.secure-port", WebOsEndToEndTest::closedPort);
        registry.add("home-control.webos.reconnect-max-delay-seconds", () -> "2");
        registry.add("home-control.webos.wake-grace-seconds", () -> "0");
        registry.add("home-control.tizen.enabled", () -> "false");
        registry.add("home-control.wake-on-lan.broadcast-address", () -> "127.0.0.1");
        registry.add("home-control.wake-on-lan.port", WOL::port);
        registry.add("home-control.deep-link-test.timeout", () -> "5s");
    }

    static int closedPort() {
        try {
            return FakeWebSocketServer.closedPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @AfterAll
    static void stopFakes() {
        TV.close();
        SSDP.close();
        DESCRIPTIONS.stop(0);
        WOL.close();
    }

    @LocalServerPort
    int port;

    @Autowired
    DeviceManager devices;

    @Autowired
    DeviceRegistry registry;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String form) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private void awaitStatus(DeviceStatus status) {
        await().atMost(Duration.ofSeconds(10)).until(() -> devices.state(ID).status() == status);
    }

    @Test
    void discoversPairsControlsWakesAndTestsALgTv() throws Exception {
        // 1. Discovered through SSDP and offered for prompt pairing.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(get("/setup").body())
                .contains("[LG] webOS TV OLED55C9PLA").contains("action=\"/setup/prompt-pair\""));

        // 2. Pairing accepts the prompt and registers the TV under its SSDP name.
        HttpResponse<String> paired = post("/setup/prompt-pair", "adapter=webos&host=127.0.0.1");
        assertThat(paired.statusCode()).isBetween(300, 399);
        assertThat(paired.headers().firstValue("Location")).hasValueSatisfying(location ->
                assertThat(location).endsWith("/?device=" + ID));
        Device device = registry.findById(ID).orElseThrow();
        assertThat(device.kind()).isEqualTo(DeviceKind.WEBOS);
        assertThat(device.name()).isEqualTo("[LG] webOS TV OLED55C9PLA");
        assertThat(device.adapterSettings("webos")).containsEntry("clientKey", FakeSsapServer.CLIENT_KEY);

        // 3. Connected, MAC learned, inputs on the dashboard.
        awaitStatus(DeviceStatus.CONNECTED);
        await().atMost(Duration.ofSeconds(10)).until(() -> "A8:23:FE:01:02:03".equals(
                registry.findById(ID).orElseThrow().adapterSettings("webos").get("macAddress")));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(get("/?device=" + ID).body()).contains("/devices/" + ID + "/input/HDMI_2"));
        assertThat(get("/setup").body()).doesNotContain(FakeSsapServer.CLIENT_KEY);

        // 4. A key through the pointer input socket.
        assertThat(post("/devices/" + ID + "/key/DPAD_UP", "").statusCode()).isEqualTo(204);
        assertThat(TV.nextButton()).isEqualTo("type:button\nname:UP\n\n");

        // 5. An input switch.
        assertThat(post("/devices/" + ID + "/input/HDMI_2", "").statusCode()).isEqualTo(204);
        JsonNode switchInput = TV.nextRequest("ssap://tv/switchInput");
        assertThat(switchInput.path("payload").path("inputId").asString("")).isEqualTo("HDMI_2");

        // 6. A YouTube link as a launch with content target.
        HttpResponse<String> play = post("/devices/" + ID + "/play",
                "uri=" + encode("https://www.youtube.com/watch?v=aqz-KE-bpKQ"));
        assertThat(play.statusCode()).isEqualTo(200);
        assertThat(play.body()).isEqualTo("Open in the YouTube app");
        JsonNode launch = TV.nextRequest("ssap://system.launcher/launch");
        assertThat(launch.path("payload").path("params").path("contentTarget").asString(""))
                .isEqualTo("https://www.youtube.com/tv?v=aqz-KE-bpKQ");

        // 7. The deep-link test sees the app change.
        TV.changeForegroundApp("com.webos.app.home");
        await().atMost(Duration.ofSeconds(10)).until(() -> "com.webos.app.home".equals(devices.state(ID).currentApp()));
        HttpResponse<String> test = post("/setup/devices/" + ID + "/deep-link-test", "");
        assertThat(test.statusCode()).isEqualTo(200);
        assertThat(test.body()).contains("deep-link-result app-changed").contains("youtube.leanback.v4");

        // 8. Switched off: keys fail with 409, Power wakes it, it comes back.
        TV.refuseConnections(true);
        awaitStatus(DeviceStatus.DISCONNECTED);
        assertThat(post("/devices/" + ID + "/key/HOME", "").statusCode()).isEqualTo(409);
        assertThat(post("/devices/" + ID + "/key/POWER", "").statusCode()).isEqualTo(204);
        assertThat(WOL.nextPacket()).containsExactly(WakeOnLan.magicPacket("A8:23:FE:01:02:03"));
        TV.refuseConnections(false);
        awaitStatus(DeviceStatus.CONNECTED);

        // 9. Forgetting removes it everywhere.
        HttpResponse<String> forgotten = post("/setup/forget", "id=" + ID);
        assertThat(forgotten.statusCode()).isBetween(300, 399);
        assertThat(registry.findById(ID)).isEmpty();
        assertThat(devices.states()).doesNotContainKey(ID);
    }
}
