package dev.andre.homecontrol.web;

import com.sun.net.httpserver.HttpServer;
import dev.andre.homecontrol.adapters.net.FakeWakeOnLanReceiver;
import dev.andre.homecontrol.adapters.net.WakeOnLan;
import dev.andre.homecontrol.adapters.tizen.FakeTizenServer;
import dev.andre.homecontrol.adapters.tizen.TizenAdapter;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * A Samsung TV through the whole application with real sockets: SSDP discovery, Allow pairing,
 * keys, app launch and DIAL, the polled deep-link test, Wake-on-LAN and forgetting.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TizenEndToEndTest {

    static final FakeTizenServer TV;
    static final FakeSsdpResponder SSDP;
    static final HttpServer DESCRIPTIONS;
    static final FakeWakeOnLanReceiver WOL;
    static final Path DATA;
    static final String ID = "tizen-127-0-0-1";

    static {
        try {
            TV = new FakeTizenServer();
            WOL = new FakeWakeOnLanReceiver();
            DESCRIPTIONS = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            DESCRIPTIONS.createContext("/samsung/description.xml", exchange -> {
                byte[] body = Files.readAllBytes(Path.of("src/test/resources/fixtures/ssdp/samsung-description.xml"));
                exchange.getResponseHeaders().add("Content-Type", "text/xml");
                exchange.sendResponseHeaders(200, body.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            DESCRIPTIONS.start();
            SSDP = new FakeSsdpResponder();
            SSDP.answer(TizenAdapter.SEARCH_TARGET,
                    FakeSsdpResponder.fixture("samsung-search-response.txt", "127.0.0.1", DESCRIPTIONS.getAddress().getPort()));
            DATA = Files.createTempDirectory("tizen-e2e");
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
        registry.add("home-control.webos.enabled", () -> "false");
        registry.add("home-control.tizen.port", TV::port);
        registry.add("home-control.tizen.rest-port", TV::httpPort);
        registry.add("home-control.tizen.dial-port", TV::httpPort);
        registry.add("home-control.tizen.poll-interval-seconds", () -> "1");
        registry.add("home-control.tizen.wake-grace-seconds", () -> "0");
        registry.add("home-control.tizen.pairing-timeout-seconds", () -> "5");
        registry.add("home-control.wake-on-lan.broadcast-address", () -> "127.0.0.1");
        registry.add("home-control.wake-on-lan.port", WOL::port);
        registry.add("home-control.deep-link-test.timeout", () -> "5s");
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

    private Map<String, String> settings() {
        return registry.findById(ID).orElseThrow().adapterSettings("tizen");
    }

    @Test
    void discoversPairsControlsWakesAndTestsASamsungTv() throws Exception {
        // 1. Discovered through SSDP.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(get("/setup").body()).contains("[TV] Samsung 8 Series (55)"));

        // 2. Allow on the TV stores the pairing and the issued token; the first connection had none.
        HttpResponse<String> paired = post("/setup/prompt-pair", "adapter=tizen&host=127.0.0.1");
        assertThat(paired.statusCode()).isBetween(300, 399);
        assertThat(paired.headers().firstValue("Location")).hasValueSatisfying(location ->
                assertThat(location).endsWith("/?device=" + ID));
        assertThat(settings()).containsEntry("paired", "true").containsEntry("token", FakeTizenServer.TOKEN);
        assertThat(TV.queries().getFirst()).doesNotContain("token=");

        // 3. Connected with the token; MAC learned from REST.
        awaitStatus(DeviceStatus.CONNECTED);
        await().atMost(Duration.ofSeconds(10)).until(() -> "70:2A:D5:01:02:03".equals(settings().get("macAddress")));
        assertThat(TV.queries()).anyMatch(query -> query.contains("token=" + FakeTizenServer.TOKEN));
        assertThat(get("/setup").body()).doesNotContain(FakeTizenServer.TOKEN);

        // 4. A key.
        assertThat(post("/devices/" + ID + "/key/HOME", "").statusCode()).isEqualTo(204);
        assertThat(TV.nextKey()).isEqualTo("KEY_HOME");

        // 5. Netflix opens the app.
        assertThat(post("/devices/" + ID + "/play", "uri=" + encode("https://www.netflix.com/title/80057281")).statusCode())
                .isEqualTo(200);
        assertThat(TV.nextLaunch()).isEqualTo(FakeTizenServer.NETFLIX);

        // 6. Web links are refused with what Samsung can open.
        HttpResponse<String> web = post("/devices/" + ID + "/play", "uri=" + encode("https://example.org/page"));
        assertThat(web.statusCode()).isEqualTo(422);
        assertThat(web.body()).contains("cannot open web links");

        // 7. The polled deep-link test sees YouTube come to the front through DIAL.
        await().atMost(Duration.ofSeconds(10)).until(() -> "Netflix".equals(devices.state(ID).currentApp()));
        HttpResponse<String> test = post("/setup/devices/" + ID + "/deep-link-test", "");
        assertThat(test.statusCode()).isEqualTo(200);
        assertThat(test.body()).contains("deep-link-result app-changed").contains("YouTube").contains("polled");
        assertThat(TV.nextDialBody()).endsWith("|v=aqz-KE-bpKQ");

        // 8. A DIAL refusal is a 502.
        TV.setDialAvailable(false);
        HttpResponse<String> refused = post("/devices/" + ID + "/play",
                "uri=" + encode("https://www.youtube.com/watch?v=aqz-KE-bpKQ"));
        assertThat(refused.statusCode()).isEqualTo(502);
        assertThat(refused.body()).contains("not available over DIAL");
        TV.setDialAvailable(true);

        // 9. Switched off, woken, back.
        TV.switchOff();
        awaitStatus(DeviceStatus.DISCONNECTED);
        assertThat(post("/devices/" + ID + "/key/POWER", "").statusCode()).isEqualTo(204);
        assertThat(WOL.nextPacket()).containsExactly(WakeOnLan.magicPacket("70:2A:D5:01:02:03"));
        TV.switchOn();
        awaitStatus(DeviceStatus.CONNECTED);

        // 10. Forgetting removes it.
        assertThat(post("/setup/forget", "id=" + ID).statusCode()).isBetween(300, 399);
        assertThat(registry.findById(ID)).isEmpty();
    }
}
