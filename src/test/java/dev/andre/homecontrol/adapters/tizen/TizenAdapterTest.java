package dev.andre.homecontrol.adapters.tizen;

import com.sun.net.httpserver.HttpServer;
import dev.andre.homecontrol.adapters.net.FakeWakeOnLanReceiver;
import dev.andre.homecontrol.adapters.net.FakeWebSocketServer;
import dev.andre.homecontrol.adapters.net.WakeOnLan;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceRegistry;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.DiscoveredDevice;
import dev.andre.homecontrol.core.WakeOnLanAdapter;
import dev.andre.homecontrol.device.JsonFileDeviceRegistry;
import dev.andre.homecontrol.discovery.ssdp.FakeSsdpResponder;
import dev.andre.homecontrol.discovery.ssdp.SsdpDiscovery;
import dev.andre.homecontrol.discovery.ssdp.SsdpProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class TizenAdapterTest {

    @TempDir
    Path dir;

    private FakeTizenServer tv;
    private FakeWakeOnLanReceiver receiver;
    private DeviceRegistry registry;
    private SsdpDiscovery ssdp;

    @BeforeEach
    void setUp() throws IOException {
        tv = new FakeTizenServer();
        receiver = new FakeWakeOnLanReceiver();
        registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
    }

    @AfterEach
    void tearDown() {
        if (ssdp != null) {
            ssdp.close();
        }
        tv.close();
        receiver.close();
    }

    private TizenProperties properties(int pollIntervalSeconds) {
        return new TizenProperties(true, tv.port(), tv.httpPort(), tv.httpPort(), "Home Control", 2, 2, 2,
                pollIntervalSeconds, 0);
    }

    private TizenAdapter adapter(SsdpDiscovery discovery, TizenProperties properties) {
        return new TizenAdapter(properties, discovery, registry, new WakeOnLan(receiver.address()));
    }

    private static SsdpDiscovery notStarted() {
        return new SsdpDiscovery(new SsdpProperties(false, "127.0.0.1", 1900, 0, 60, 2));
    }

    private Device device() {
        Device device = new Device("samsung", "Samsung TV", DeviceKind.TIZEN, "127.0.0.1",
                Map.of("tizen", Map.of("paired", "true", "token", FakeTizenServer.TOKEN)), Instant.now());
        registry.save(device);
        return device;
    }

    @Test
    void declaresKeysPowerVolumeAndAppLinks() {
        TizenAdapter adapter = adapter(notStarted(), properties(1));

        assertThat(adapter.capabilities(device()))
                .containsExactlyInAnyOrder(Capability.REMOTE_KEYS, Capability.POWER, Capability.VOLUME, Capability.APP_LINK);
        assertThat(adapter.id()).isEqualTo("tizen");
        assertThat(adapter.kind()).isEqualTo(DeviceKind.TIZEN);
        assertThat(adapter).isInstanceOf(WakeOnLanAdapter.class);
        assertThat(adapter.settingsFor(new DiscoveredDevice("tizen", "Samsung", "127.0.0.1", 8002))).isEmpty();
    }

    @Test
    void pollsTheForegroundApp() {
        assertThat(adapter(notStarted(), properties(1)).foregroundAppReporting(device()))
                .isEqualTo(dev.andre.homecontrol.core.ForegroundAppReporting.POLLED);
    }

    @Test
    void listsSamsungTvsTheSharedListenerFound() throws Exception {
        HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        http.createContext("/samsung/description.xml", exchange -> {
            byte[] body = Files.readAllBytes(Path.of("src/test/resources/fixtures/ssdp/samsung-description.xml"));
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        http.start();
        try (FakeSsdpResponder responder = new FakeSsdpResponder()) {
            responder.answer(TizenAdapter.SEARCH_TARGET,
                    FakeSsdpResponder.fixture("samsung-search-response.txt", "127.0.0.1", http.getAddress().getPort()));
            ssdp = new SsdpDiscovery(new SsdpProperties(true, "127.0.0.1", responder.port(), 0, 1, 1));
            ssdp.start();
            TizenAdapter adapter = adapter(ssdp, properties(1));

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(adapter.discovered())
                    .containsExactly(new DiscoveredDevice("tizen", "[TV] Samsung 8 Series (55)", "127.0.0.1", tv.port())));
        } finally {
            http.stop(0);
        }
    }

    @Test
    void withoutADescriptionTheNameIsGeneric() throws Exception {
        try (FakeSsdpResponder responder = new FakeSsdpResponder()) {
            responder.answer(TizenAdapter.SEARCH_TARGET,
                    FakeSsdpResponder.fixture("samsung-search-response.txt", "127.0.0.1", FakeWebSocketServer.closedPort()));
            ssdp = new SsdpDiscovery(new SsdpProperties(true, "127.0.0.1", responder.port(), 0, 1, 1));
            ssdp.start();
            TizenAdapter adapter = adapter(ssdp, properties(1));

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(adapter.discovered())
                    .containsExactly(new DiscoveredDevice("tizen", "Samsung TV", "127.0.0.1", tv.port())));
        }
    }

    @Test
    void anSsdpAnnouncementTriggersAnImmediatePoll() throws Exception {
        ssdp = new SsdpDiscovery(new SsdpProperties(true, "127.0.0.1", FakeWebSocketServer.closedPort(), 0, 60, 1));
        ssdp.start();
        TizenAdapter adapter = adapter(ssdp, properties(30));
        tv.switchOff();
        DeviceHandle handle = adapter.connect(device(), state -> { });
        try {
            await().atMost(Duration.ofSeconds(5)).until(() -> tv.connections() == 0
                    && handle.state().status() == DeviceStatus.DISCONNECTED);
            Thread.sleep(500);
            tv.switchOn();

            String alive = "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nNT: " + TizenAdapter.SEARCH_TARGET
                    + "\r\nNTS: ssdp:alive\r\nUSN: uuid:samsung::" + TizenAdapter.SEARCH_TARGET
                    + "\r\nCACHE-CONTROL: max-age=1800\r\nLOCATION: http://127.0.0.1:1/x.xml\r\n\r\n";
            try (DatagramSocket sender = new DatagramSocket()) {
                byte[] bytes = alive.getBytes(StandardCharsets.US_ASCII);
                sender.send(new DatagramPacket(bytes, bytes.length,
                        new InetSocketAddress(InetAddress.getLoopbackAddress(), ssdp.listenPort())));
            }

            await().atMost(Duration.ofSeconds(5)).until(() -> handle.state().status() == DeviceStatus.CONNECTED);
        } finally {
            handle.close();
        }
    }
}
