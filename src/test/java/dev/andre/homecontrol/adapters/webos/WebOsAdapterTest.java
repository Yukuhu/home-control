package dev.andre.homecontrol.adapters.webos;

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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class WebOsAdapterTest {

    @TempDir
    Path dir;

    private FakeSsapServer tv;
    private FakeWakeOnLanReceiver receiver;
    private DeviceRegistry registry;
    private SsdpDiscovery ssdp;

    @BeforeEach
    void setUp() throws IOException {
        tv = new FakeSsapServer(false);
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

    private WebOsProperties properties(int reconnectInitialDelaySeconds) throws IOException {
        return new WebOsProperties(true, tv.port(), FakeWebSocketServer.closedPort(), 2, 2, 2,
                reconnectInitialDelaySeconds, Math.max(2, reconnectInitialDelaySeconds), 0);
    }

    private WebOsAdapter adapter(SsdpDiscovery discovery, WebOsProperties properties) {
        return new WebOsAdapter(properties, discovery, registry, new WakeOnLan(receiver.address()));
    }

    private static SsdpDiscovery notStarted() {
        return new SsdpDiscovery(new SsdpProperties(false, "127.0.0.1", 1900, 0, 60, 2));
    }

    private Device device() {
        Device device = new Device("lg", "LG TV", DeviceKind.WEBOS, "127.0.0.1",
                Map.of("webos", Map.of("clientKey", FakeSsapServer.CLIENT_KEY)), Instant.now());
        registry.save(device);
        return device;
    }

    @Test
    void declaresKeysPowerVolumeAndAppLinks() throws IOException {
        WebOsAdapter adapter = adapter(notStarted(), properties(1));

        assertThat(adapter.capabilities(device()))
                .containsExactlyInAnyOrder(Capability.REMOTE_KEYS, Capability.POWER, Capability.VOLUME, Capability.APP_LINK);
        assertThat(adapter).isInstanceOf(WakeOnLanAdapter.class);
        assertThat(adapter.id()).isEqualTo("webos");
        assertThat(adapter.kind()).isEqualTo(DeviceKind.WEBOS);
        assertThat(adapter.settingsFor(new DiscoveredDevice("webos", "LG", "127.0.0.1", 3000))).isEmpty();
    }

    @Test
    void reportsTheForegroundAppLive() throws IOException {
        assertThat(adapter(notStarted(), properties(1)).foregroundAppReporting(device()))
                .isEqualTo(dev.andre.homecontrol.core.ForegroundAppReporting.LIVE);
    }

    @Test
    void connectReturnsAStartedSession() throws Exception {
        WebOsAdapter adapter = adapter(notStarted(), properties(1));
        DeviceHandle handle = adapter.connect(device(), state -> { });

        await().atMost(Duration.ofSeconds(5)).until(() -> handle.state().status() == DeviceStatus.CONNECTED);
        handle.close();
        int connections = tv.connections();
        tv.dropConnections();
        Thread.sleep(2000);

        assertThat(tv.connections()).isEqualTo(connections);
    }

    @Test
    void listsLgTvsTheSharedListenerFound() throws Exception {
        HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        http.createContext("/lg/description.xml", exchange -> {
            byte[] body = Files.readAllBytes(Path.of("src/test/resources/fixtures/ssdp/lg-description.xml"));
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        http.start();
        try (FakeSsdpResponder responder = new FakeSsdpResponder()) {
            responder.answer(WebOsAdapter.SEARCH_TARGET,
                    FakeSsdpResponder.fixture("lg-search-response.txt", "127.0.0.1", http.getAddress().getPort()));
            ssdp = new SsdpDiscovery(new SsdpProperties(true, "127.0.0.1", responder.port(), 0, 1, 1));
            ssdp.start();
            WebOsAdapter adapter = adapter(ssdp, properties(1));

            DiscoveredDevice expected = new DiscoveredDevice("webos", "[LG] webOS TV OLED55C9PLA", "127.0.0.1", tv.port());
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(adapter.discovered()).containsExactly(expected));
            await().atMost(Duration.ofSeconds(5)).until(() ->
                    ssdp.services(WebOsAdapter.SEARCH_TARGET).getFirst().friendlyName().isPresent());
            assertThat(adapter.discovered()).containsExactly(expected);
        } finally {
            http.stop(0);
        }
    }

    @Test
    void withoutADescriptionTheNameComesFromTheLgHeader() throws Exception {
        try (FakeSsdpResponder responder = new FakeSsdpResponder()) {
            responder.answer(WebOsAdapter.SEARCH_TARGET,
                    FakeSsdpResponder.fixture("lg-search-response.txt", "127.0.0.1", FakeWebSocketServer.closedPort()));
            ssdp = new SsdpDiscovery(new SsdpProperties(true, "127.0.0.1", responder.port(), 0, 1, 1));
            ssdp.start();
            WebOsAdapter adapter = adapter(ssdp, properties(1));

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(adapter.discovered())
                    .containsExactly(new DiscoveredDevice("webos", "[LG] webOS TV OLED55C9PLA", "127.0.0.1", tv.port())));
        }
    }

    @Test
    void anSsdpAnnouncementFromItsTvReconnectsAtOnce() throws Exception {
        ssdp = new SsdpDiscovery(new SsdpProperties(true, "127.0.0.1", FakeWebSocketServer.closedPort(), 0, 60, 1));
        ssdp.start();
        WebOsAdapter adapter = adapter(ssdp, properties(30));
        tv.refuseConnections(true);
        List<DeviceStatus> seen = new CopyOnWriteArrayList<>();
        AtomicReference<DeviceHandle> handle = new AtomicReference<>(
                adapter.connect(device(), state -> seen.add(state.status())));
        try {
            // The first attempt failed; the 30-second backoff is running.
            await().atMost(Duration.ofSeconds(5)).until(() -> seen.contains(DeviceStatus.CONNECTING)
                    && handle.get().state().status() == DeviceStatus.DISCONNECTED);
            tv.refuseConnections(false);

            String alive = "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nNT: " + WebOsAdapter.SEARCH_TARGET
                    + "\r\nNTS: ssdp:alive\r\nUSN: uuid:lg::" + WebOsAdapter.SEARCH_TARGET
                    + "\r\nCACHE-CONTROL: max-age=1800\r\nLOCATION: http://127.0.0.1:1/x.xml\r\n\r\n";
            try (DatagramSocket sender = new DatagramSocket()) {
                byte[] bytes = alive.getBytes(StandardCharsets.US_ASCII);
                sender.send(new DatagramPacket(bytes, bytes.length,
                        new InetSocketAddress(InetAddress.getLoopbackAddress(), ssdp.listenPort())));
            }

            await().atMost(Duration.ofSeconds(5)).until(() -> handle.get().state().status() == DeviceStatus.CONNECTED);
        } finally {
            handle.get().close();
        }
    }

}
