package dev.andre.homecontrol.discovery.ssdp;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class SsdpDiscoveryTest {

    private static final String LG_TARGET = "urn:lge-com:service:webos-second-screen:1";
    private static final String SAMSUNG_TARGET = "urn:samsung.com:device:RemoteControlReceiver:1";

    private FakeSsdpResponder responder;
    private HttpServer http;
    private MutableClock clock;
    private SsdpDiscovery discovery;
    private final AtomicInteger httpRequests = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        responder = new FakeSsdpResponder();
        http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        http.createContext("/lg/description.xml", exchange -> serve(exchange, "lg-description.xml"));
        http.createContext("/samsung/description.xml", exchange -> serve(exchange, "samsung-description.xml"));
        http.createContext("/big/description.xml", this::serveOversized);
        http.start();
        clock = new MutableClock(Instant.parse("2026-09-16T12:00:00Z"), ZoneOffset.UTC);
        discovery = new SsdpDiscovery(new SsdpProperties(true, "127.0.0.1", responder.port(), 0, 1, 1),
                clock, HttpClient.newHttpClient());
        discovery.start();
    }

    @AfterEach
    void tearDown() {
        discovery.close();
        http.stop(0);
        responder.close();
    }

    private void serve(com.sun.net.httpserver.HttpExchange exchange, String fixtureName) throws IOException {
        httpRequests.incrementAndGet();
        byte[] body = Files.readAllBytes(Path.of("src/test/resources/fixtures/ssdp/" + fixtureName));
        exchange.getResponseHeaders().add("Content-Type", "text/xml");
        exchange.sendResponseHeaders(200, body.length);
        try (var out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /**
     * A valid, parseable LG description padded past the 64 KiB cap with an XML comment: it would
     * parse successfully (and yield a friendly name) if fully read, so only the size cap — not
     * malformed content — can be what makes the test's assertion hold.
     */
    private void serveOversized(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        httpRequests.incrementAndGet();
        String base = Files.readString(Path.of("src/test/resources/fixtures/ssdp/lg-description.xml"));
        char[] padding = new char[70 * 1024];
        Arrays.fill(padding, 'a');
        String oversized = base.replace("</root>", "<!-- " + new String(padding) + " -->\n</root>");
        byte[] body = oversized.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/xml");
        exchange.sendResponseHeaders(200, body.length);
        try (var out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private void sendUdp(String message) throws IOException {
        try (DatagramSocket sender = new DatagramSocket()) {
            byte[] bytes = message.getBytes(StandardCharsets.US_ASCII);
            sender.send(new DatagramPacket(bytes, bytes.length,
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), discovery.listenPort())));
        }
    }

    private int httpPort() {
        return http.getAddress().getPort();
    }

    @Test
    void findsAWatchedServiceAndFetchesItsDescription() throws IOException {
        responder.answer(LG_TARGET, FakeSsdpResponder.fixture("lg-search-response.txt", "127.0.0.1", httpPort()));

        discovery.watch(LG_TARGET);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            List<SsdpService> services = discovery.services(LG_TARGET);
            assertThat(services).hasSize(1);
            SsdpService service = services.getFirst();
            assertThat(service.usn()).isEqualTo(
                    "uuid:e8d7f6a5-1234-4bcd-9ef0-a8b7c6d5e4f3::urn:lge-com:service:webos-second-screen:1");
            assertThat(service.address()).isEqualTo("127.0.0.1");
            assertThat(service.friendlyName()).contains("[LG] webOS TV OLED55C9PLA");
        });
    }

    @Test
    void ignoresServicesOfTypesNobodyWatches() throws IOException {
        responder.answer(SAMSUNG_TARGET,
                FakeSsdpResponder.fixture("samsung-search-response.txt", "127.0.0.1", httpPort()));
        discovery.watch(LG_TARGET);

        await().atMost(Duration.ofSeconds(5)).until(() -> responder.searches() >= 2);

        assertThat(discovery.services(SAMSUNG_TARGET)).isEmpty();
    }

    @Test
    void learnsFromNotifyAndForgetsOnByeBye() throws IOException {
        List<SsdpService> byebyes = new CopyOnWriteArrayList<>();
        discovery.addListener("urn:x:1", new SsdpListener() {
            @Override
            public void alive(SsdpService service) {
            }

            @Override
            public void byebye(SsdpService service) {
                byebyes.add(service);
            }
        });
        String location = "http://127.0.0.1:" + httpPort() + "/lg/description.xml";
        String alive = "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nNT: urn:x:1\r\nNTS: ssdp:alive\r\n"
                + "USN: uuid:a::urn:x:1\r\nCACHE-CONTROL: max-age = 120\r\nLOCATION: " + location + "\r\n\r\n";
        String byebye = alive.replace("NTS: ssdp:alive", "NTS: ssdp:byebye");

        try (DatagramSocket sender = new DatagramSocket()) {
            byte[] aliveBytes = alive.getBytes(StandardCharsets.US_ASCII);
            sender.send(new DatagramPacket(aliveBytes, aliveBytes.length,
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), discovery.listenPort())));

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(discovery.services("urn:x:1")).hasSize(1));

            byte[] byebyeBytes = byebye.getBytes(StandardCharsets.US_ASCII);
            sender.send(new DatagramPacket(byebyeBytes, byebyeBytes.length,
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), discovery.listenPort())));

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertThat(discovery.services("urn:x:1")).isEmpty();
                assertThat(byebyes).hasSize(1);
            });
        }
    }

    @Test
    void garbageDatagramsAreIgnored() throws Exception {
        discovery.watch(LG_TARGET);

        responder.sendGarbage(discovery.listenPort());
        Thread.sleep(300);
        assertThat(discovery.services(LG_TARGET)).isEmpty();

        String alive = "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nNT: " + LG_TARGET + "\r\nNTS: ssdp:alive\r\n"
                + "USN: uuid:after-garbage::" + LG_TARGET + "\r\nCACHE-CONTROL: max-age=120\r\n\r\n";
        sendUdp(alive);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(discovery.services(LG_TARGET)).extracting(SsdpService::usn)
                        .containsExactly("uuid:after-garbage::" + LG_TARGET));
    }

    @Test
    void expiresAServiceAfterItsMaxAge() throws IOException {
        responder.answer(LG_TARGET, FakeSsdpResponder.fixture("lg-search-response.txt", "127.0.0.1", httpPort()));
        discovery.watch(LG_TARGET);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(discovery.services(LG_TARGET)).hasSize(1));

        int before = responder.searches();
        responder.stopAnswering(LG_TARGET);
        await().atMost(Duration.ofSeconds(5)).until(() -> responder.searches() >= before + 2);

        clock.advance(Duration.ofSeconds(1801));

        assertThat(discovery.services(LG_TARGET)).isEmpty();
    }

    @Test
    void listenersHearAliveServicesOfTheirTargetOnly() throws IOException {
        responder.answer(LG_TARGET, FakeSsdpResponder.fixture("lg-search-response.txt", "127.0.0.1", httpPort()));
        List<SsdpService> lgAlive = new CopyOnWriteArrayList<>();
        List<SsdpService> samsungAlive = new CopyOnWriteArrayList<>();
        discovery.addListener(LG_TARGET, lgAlive::add);
        discovery.addListener(SAMSUNG_TARGET, samsungAlive::add);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(lgAlive).isNotEmpty());

        assertThat(lgAlive.getFirst().type()).isEqualTo(LG_TARGET);
        assertThat(samsungAlive).isEmpty();
    }

    @Test
    void disabledDiscoveryOpensNoSockets() throws InterruptedException {
        try (SsdpDiscovery disabled = new SsdpDiscovery(
                new SsdpProperties(false, "127.0.0.1", responder.port(), 0, 1, 1))) {
            disabled.start();
            disabled.watch(LG_TARGET);

            assertThat(disabled.listenPort()).isEqualTo(-1);
            Thread.sleep(1500);
            assertThat(responder.searches()).isZero();
        }
    }

    @Test
    void aLocationHostThatDiffersFromTheAnnouncingAddressIsNeverFetched() throws IOException {
        String location = "http://127.0.0.2:" + httpPort() + "/lg/description.xml";
        String alive = "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nNT: urn:mismatch:1\r\nNTS: ssdp:alive\r\n"
                + "USN: uuid:mismatch::urn:mismatch:1\r\nCACHE-CONTROL: max-age = 120\r\nLOCATION: " + location
                + "\r\n\r\n";
        discovery.watch("urn:mismatch:1");

        sendUdp(alive);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(discovery.services("urn:mismatch:1")).hasSize(1));
        SsdpService service = discovery.services("urn:mismatch:1").getFirst();
        // The service is recorded at the address it actually announced from, never the forged LOCATION host.
        assertThat(service.address()).isEqualTo("127.0.0.1");
        assertThat(service.friendlyName()).isEmpty();
        assertThat(httpRequests.get()).isZero();
    }

    @Test
    void aLocationWithAHostNameInsteadOfAnIpLiteralIsNeverFetched() throws IOException {
        String location = "http://tv.invalid.example:" + httpPort() + "/lg/description.xml";
        String alive = "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nNT: urn:hostname:1\r\nNTS: ssdp:alive\r\n"
                + "USN: uuid:hostname::urn:hostname:1\r\nCACHE-CONTROL: max-age = 120\r\nLOCATION: " + location
                + "\r\n\r\n";
        discovery.watch("urn:hostname:1");

        sendUdp(alive);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(discovery.services("urn:hostname:1")).hasSize(1));
        assertThat(discovery.services("urn:hostname:1").getFirst().friendlyName()).isEmpty();
        assertThat(httpRequests.get()).isZero();
    }

    @Test
    void aMatchingLocationHostIsStillFetched() throws IOException {
        String location = "http://127.0.0.1:" + httpPort() + "/lg/description.xml";
        String alive = "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nNT: urn:match:1\r\nNTS: ssdp:alive\r\n"
                + "USN: uuid:match::urn:match:1\r\nCACHE-CONTROL: max-age = 120\r\nLOCATION: " + location + "\r\n\r\n";
        discovery.watch("urn:match:1");

        sendUdp(alive);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(discovery.services("urn:match:1").getFirst().friendlyName())
                        .contains("[LG] webOS TV OLED55C9PLA"));
        assertThat(httpRequests.get()).isEqualTo(1);
    }

    @Test
    void ignoresADescriptionLargerThanTheSizeCap() throws IOException, InterruptedException {
        responder.answer(LG_TARGET, FakeSsdpResponder.fixture("lg-search-response.txt", "127.0.0.1", httpPort())
                .replace("/lg/description.xml", "/big/description.xml"));

        discovery.watch(LG_TARGET);

        await().atMost(Duration.ofSeconds(5)).until(() -> httpRequests.get() >= 1);
        // The fetch is rejected on the Content-Length pre-check, so no async parse ever completes;
        // give it a moment to prove that, rather than racing a negative assertion.
        Thread.sleep(500);
        assertThat(discovery.services(LG_TARGET)).hasSize(1);
        assertThat(discovery.services(LG_TARGET).getFirst().friendlyName()).isEmpty();
    }

    @Test
    void fetchesDescriptionsOverHttp11() throws IOException {
        List<String> protocols = new CopyOnWriteArrayList<>();
        List<String> upgrades = new CopyOnWriteArrayList<>();
        http.removeContext("/lg/description.xml");
        http.createContext("/lg/description.xml", exchange -> {
            protocols.add(exchange.getProtocol());
            upgrades.add(String.valueOf(exchange.getRequestHeaders().getFirst("Upgrade")));
            serve(exchange, "lg-description.xml");
        });
        responder.answer(LG_TARGET, FakeSsdpResponder.fixture("lg-search-response.txt", "127.0.0.1", httpPort()));

        try (SsdpDiscovery real = new SsdpDiscovery(new SsdpProperties(true, "127.0.0.1", responder.port(), 0, 1, 1))) {
            real.start();
            real.watch(LG_TARGET);

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(real.services(LG_TARGET)).singleElement()
                            .satisfies(service -> assertThat(service.friendlyName()).contains("[LG] webOS TV OLED55C9PLA")));
        }
        assertThat(protocols).first().isEqualTo("HTTP/1.1");
        assertThat(upgrades).first().isEqualTo("null");
    }

    /** A {@link Clock} whose {@code instant()} can be moved forward by the test. */
    private static final class MutableClock extends Clock {

        private volatile Instant instant;
        private final ZoneId zone;

        MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void advance(Duration amount) {
            instant = instant.plus(amount);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
