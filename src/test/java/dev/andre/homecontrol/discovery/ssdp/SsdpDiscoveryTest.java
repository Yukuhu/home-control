package dev.andre.homecontrol.discovery.ssdp;

import com.sun.net.httpserver.HttpServer;
import dev.andre.homecontrol.discovery.ssdp.protocol.FakeSsdpResponder;
import dev.andre.homecontrol.discovery.ssdp.protocol.SsdpMessage;
import dev.andre.homecontrol.testsupport.Fixtures;
import dev.andre.homecontrol.testsupport.MutableClock;
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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
        discovery = new SsdpDiscovery(new SsdpProperties(true, "127.0.0.1", responder.port(), 0,
                Duration.ofSeconds(1), 1),
                new SsdpTimings(Duration.ofMillis(200)), clock, HttpClient.newHttpClient());
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
        byte[] body = Fixtures.bytes("ssdp/" + fixtureName);
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
        String base = Fixtures.read("ssdp/lg-description.xml");
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
                // only bye-byes are recorded; alive announcements are checked through services()
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
        await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(discovery.services(LG_TARGET)).isEmpty());

        String alive = "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nNT: " + LG_TARGET + "\r\nNTS: ssdp:alive\r\n"
                + "USN: uuid:after-garbage::" + LG_TARGET + "\r\nCACHE-CONTROL: max-age=120\r\n\r\n";
        sendUdp(alive);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(discovery.services(LG_TARGET)).extracting(SsdpService::usn)
                        .containsExactly("uuid:after-garbage::" + LG_TARGET));
    }

    /** One datagram, from any host on the LAN, must never end discovery until the next restart. */
    @Test
    void noDatagramAndNoListenerEndsTheReceiving() throws IOException {
        discovery.addListener("urn:x:1", new SsdpListener() {
            @Override
            public void alive(SsdpService service) {
                if (service.usn().startsWith("uuid:breaks-the-listener")) {
                    throw new IllegalStateException("a listener with a bug");
                }
            }

            @Override
            public void byebye(SsdpService service) {
                // not needed here
            }
        });
        String alive = """
                NOTIFY * HTTP/1.1\r
                HOST: 239.255.255.250:1900\r
                NT: urn:x:1\r
                NTS: ssdp:alive\r
                USN: %s::urn:x:1\r
                CACHE-CONTROL: max-age=%s\r
                \r
                """;

        sendUdp(alive.formatted("uuid:lives-for-ever", "99999999999999999999"));
        sendUdp(alive.formatted("uuid:breaks-the-listener", "120"));
        sendUdp(alive.formatted("uuid:after-both", "120"));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(discovery.services("urn:x:1")).extracting(SsdpService::usn)
                        .contains("uuid:lives-for-ever::urn:x:1", "uuid:after-both::urn:x:1"));
        clock.advance(Duration.ofDays(1));
        assertThat(discovery.services("urn:x:1")).extracting(SsdpService::usn)
                .doesNotContain("uuid:lives-for-ever::urn:x:1");
    }

    private static SsdpMessage announcement(String uuid, int maxAge) {
        String alive = "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nNT: urn:x:1\r\nNTS: ssdp:alive\r\n"
                + "USN: " + uuid + "::urn:x:1\r\nCACHE-CONTROL: max-age=" + maxAge + "\r\n\r\n";
        return SsdpMessage.parse(alive.getBytes(StandardCharsets.US_ASCII), alive.length()).orElseThrow();
    }

    private static InetAddress host(int last) {
        return InetAddress.ofLiteral("10.0.0." + last);
    }

    private List<String> heard() {
        return discovery.services("urn:x:1").stream().map(SsdpService::usn).toList();
    }

    /** One host on the LAN cannot fill discovery with announcements of services it invents. */
    @Test
    void oneHostKeepsAtMostItsShareOfTheServices() {
        discovery.watch("urn:x:1");

        for (int i = 0; i < SsdpDiscovery.MAX_SERVICES_PER_HOST + 10; i++) {
            discovery.handle(announcement("uuid:flood-" + i, 86400), host(66));
        }
        discovery.handle(announcement("uuid:tv", 1800), host(5));

        assertThat(discovery.services("urn:x:1")).filteredOn(service -> service.address().equals("10.0.0.66"))
                .hasSize(SsdpDiscovery.MAX_SERVICES_PER_HOST);
        assertThat(heard()).contains("uuid:tv::urn:x:1");
    }

    /** The search answers and the announcements are read by two threads; together they still keep to the caps. */
    @Test
    void bothReceivingThreadsTogetherKeepToTheCaps() {
        discovery.watch("urn:x:1");
        for (int round = 0; round < 20; round++) {
            discovery.close();
            discovery = new SsdpDiscovery(new SsdpProperties(false, "127.0.0.1", responder.port(), 0,
                    Duration.ofSeconds(1), 1), new SsdpTimings(Duration.ofMillis(200)), clock,
                    HttpClient.newHttpClient());
            discovery.watch("urn:x:1");
            int r = round;
            CountDownLatch start = new CountDownLatch(1);
            try (ExecutorService readers = Executors.newFixedThreadPool(2)) {
                for (int thread = 0; thread < 2; thread++) {
                    int t = thread;
                    readers.execute(() -> {
                        awaitQuietly(start);
                        for (int i = 0; i < SsdpDiscovery.MAX_SERVICES_PER_HOST; i++) {
                            discovery.handle(announcement("uuid:r" + r + "-t" + t + "-" + i, 1800), host(66));
                        }
                    });
                }
                start.countDown();
            }

            assertThat(discovery.services("urn:x:1")).as("round " + round)
                    .hasSize(SsdpDiscovery.MAX_SERVICES_PER_HOST);
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * When discovery is full, the service heard from longest ago makes room, not one the sender says expires soonest:
     * a real device answers every search, so a burst of announcements with long lifetimes cannot push it out.
     */
    @Test
    void whenFullTheServiceHeardFromLongestAgoMakesRoom() {
        discovery.watch("urn:x:1");
        discovery.handle(announcement("uuid:tv", 1800), host(5));
        int hosts = SsdpDiscovery.MAX_SERVICES / SsdpDiscovery.MAX_SERVICES_PER_HOST;

        for (int h = 0; h < hosts; h++) {
            for (int i = 0; i < SsdpDiscovery.MAX_SERVICES_PER_HOST; i++) {
                clock.advance(Duration.ofSeconds(1));
                discovery.handle(announcement("uuid:flood-" + h + "-" + i, 86400), host(100 + h));
                if (h == hosts / 2 && i == 0) {
                    // The TV answers the search that runs every minute.
                    discovery.handle(announcement("uuid:tv", 1800), host(5));
                }
            }
        }

        assertThat(heard()).hasSize(SsdpDiscovery.MAX_SERVICES).contains("uuid:tv::urn:x:1")
                .doesNotContain("uuid:flood-0-0::urn:x:1");
    }

    /** A service that moved to a new LOCATION while its old description was being fetched never gets the old one. */
    @Test
    void aDescriptionFetchedForAnOldLocationIsNotKept() throws Exception {
        CountDownLatch released = new CountDownLatch(1);
        HttpServer moving = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        ExecutorService handlers = Executors.newCachedThreadPool();
        moving.setExecutor(handlers);
        moving.createContext("/old", exchange -> {
            try {
                released.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            serve(exchange, "lg-description.xml");
        });
        moving.createContext("/new", exchange -> serve(exchange, "samsung-description.xml"));
        moving.start();
        try {
            discovery.watch("urn:x:1");
            String base = "http://127.0.0.1:" + moving.getAddress().getPort();
            String announce = "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nNT: urn:x:1\r\nNTS: ssdp:alive\r\n"
                    + "USN: uuid:moving::urn:x:1\r\nCACHE-CONTROL: max-age=120\r\nLOCATION: " + base + "%s\r\n\r\n";
            sendUdp(announce.formatted("/old"));
            await().atMost(Duration.ofSeconds(5)).until(() -> !discovery.services("urn:x:1").isEmpty());
            sendUdp(announce.formatted("/new"));
            await().atMost(Duration.ofSeconds(5)).until(() -> discovery.services("urn:x:1").getFirst().location()
                    .toString().endsWith("/new"));

            released.countDown();
            await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    assertThat(discovery.services("urn:x:1").getFirst().description()).isNull());

            sendUdp(announce.formatted("/new"));
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(discovery.services("urn:x:1").getFirst().friendlyName()).isPresent());
        } finally {
            released.countDown();
            moving.stop(0);
            handlers.shutdownNow();
        }
    }

    /** Each new service's description is fetched once, by a few fetches at a time, however many are announced. */
    @Test
    void descriptionsAreFetchedAFewAtATimeAndOncePerService() throws Exception {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger mostAtOnce = new AtomicInteger();
        AtomicInteger requests = new AtomicInteger();
        CountDownLatch released = new CountDownLatch(1);
        HttpServer slow = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        ExecutorService handlers = Executors.newCachedThreadPool();
        slow.setExecutor(handlers);
        slow.createContext("/slow", exchange -> {
            requests.incrementAndGet();
            mostAtOnce.accumulateAndGet(running.incrementAndGet(), Math::max);
            try {
                released.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            } finally {
                running.decrementAndGet();
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
            }
        });
        slow.start();
        try {
            discovery.watch("urn:x:1");
            String location = "http://127.0.0.1:" + slow.getAddress().getPort() + "/slow";
            for (int round = 0; round < 3; round++) {
                for (int i = 0; i < 10; i++) {
                    sendUdp("NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nNT: urn:x:1\r\nNTS: ssdp:alive\r\n"
                            + "USN: uuid:slow-" + i + "::urn:x:1\r\nCACHE-CONTROL: max-age=120\r\nLOCATION: "
                            + location + "\r\n\r\n");
                }
            }

            await().atMost(Duration.ofSeconds(5)).until(() -> running.get() == SsdpDiscovery.MAX_FETCHES);
            await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2))
                    .until(() -> running.get() == SsdpDiscovery.MAX_FETCHES);
            assertThat(requests).as("a service whose fetch runs is not fetched again").hasValue(SsdpDiscovery.MAX_FETCHES);
        } finally {
            released.countDown();
            slow.stop(0);
            handlers.shutdownNow();
        }
        assertThat(mostAtOnce.get()).isEqualTo(SsdpDiscovery.MAX_FETCHES);
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
    void disabledDiscoveryOpensNoSockets() {
        try (SsdpDiscovery disabled = new SsdpDiscovery(
                new SsdpProperties(false, "127.0.0.1", responder.port(), 0, Duration.ofSeconds(1), 1))) {
            disabled.start();
            disabled.watch(LG_TARGET);

            assertThat(disabled.listenPort()).isEqualTo(-1);
            await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    assertThat(responder.searches()).isZero());
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
    void ignoresADescriptionLargerThanTheSizeCap() throws IOException {
        responder.answer(LG_TARGET, FakeSsdpResponder.fixture("lg-search-response.txt", "127.0.0.1", httpPort())
                .replace("/lg/description.xml", "/big/description.xml"));

        discovery.watch(LG_TARGET);

        await().atMost(Duration.ofSeconds(5)).until(() -> httpRequests.get() >= 1);
        // The fetch is rejected on the Content-Length pre-check, so no async parse ever completes;
        // hold the assertion for a moment to prove that, rather than racing a negative assertion.
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            assertThat(discovery.services(LG_TARGET)).hasSize(1);
            assertThat(discovery.services(LG_TARGET).getFirst().friendlyName()).isEmpty();
        });
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

        try (SsdpDiscovery real = new SsdpDiscovery(new SsdpProperties(true, "127.0.0.1", responder.port(), 0,
                Duration.ofSeconds(1), 1),
                new SsdpTimings(Duration.ofMillis(200)))) {
            real.start();
            real.watch(LG_TARGET);

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(real.services(LG_TARGET)).singleElement()
                            .satisfies(service -> assertThat(service.friendlyName()).contains("[LG] webOS TV OLED55C9PLA")));
        }
        assertThat(protocols).first().isEqualTo("HTTP/1.1");
        assertThat(upgrades).first().isEqualTo("null");
    }
}
