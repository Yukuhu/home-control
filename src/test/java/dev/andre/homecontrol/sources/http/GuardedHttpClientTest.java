package dev.andre.homecontrol.sources.http;

import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.core.content.ContentSourceException.Kind;
import dev.andre.homecontrol.sources.http.GuardedHttpClient.Profile;
import dev.andre.homecontrol.sources.http.GuardedHttpClient.Redirects;
import dev.andre.homecontrol.testsupport.FakeHttpServer;
import dev.andre.homecontrol.testsupport.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.awaitility.Awaitility.await;

/**
 * The guarded client against a fake server on loopback. Host names resolve through the test's policy: source.test
 * and other.test to the fake, metadata.test to the cloud metadata address.
 */
class GuardedHttpClientTest {

    private static final int CAP = 64;

    private FakeHttpServer server;
    private final AtomicInteger lookups = new AtomicInteger();
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void start() throws IOException {
        server = FakeHttpServer.start();
    }

    @AfterEach
    void stop() {
        release.countDown();
        server.close();
    }

    private OutboundAddressPolicy policy(boolean allowLoopback) {
        Map<String, String> names = Map.of("source.test", "127.0.0.1", "other.test", "127.0.0.1",
                "metadata.test", "169.254.169.254");
        return new OutboundAddressPolicy(allowLoopback, host -> {
            lookups.incrementAndGet();
            String address = names.get(host);
            if (address == null) {
                throw new UnknownHostException(host);
            }
            return new InetAddress[] {InetAddress.ofLiteral(address)};
        });
    }

    private static Profile profile(Redirects redirects, Duration deadline, int maxConcurrent) {
        return new Profile("the source", redirects, 2, CAP, Duration.ofSeconds(1), deadline, maxConcurrent,
                new HttpUrls.Rules(true, false, true, false, 0));
    }

    private GuardedHttpClient client(Redirects redirects) {
        return client(redirects, Duration.ofSeconds(2), 2, true);
    }

    private GuardedHttpClient client(Redirects redirects, Duration deadline, int maxConcurrent, boolean allowLoopback) {
        return new GuardedHttpClient(profile(redirects, deadline, maxConcurrent), policy(allowLoopback),
                failure -> new ContentSourceException(failure.kind(), failure.describe("the source")));
    }

    private URI at(String host, String path) {
        return URI.create("http://" + host + ":" + server.url().getPort() + path);
    }

    private static ContentSourceException failureOf(Runnable call) {
        return catchThrowableOfType(ContentSourceException.class, call::run);
    }

    private void blockUntilReleased(String path, CountDownLatch entered) {
        server.handle("GET", path, exchange -> {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            try (exchange) {
                exchange.sendResponseHeaders(200, -1);
            }
        });
    }

    @Test
    void readsABodyFromAnApprovedHost() {
        server.respond("GET", "/ok", Response.of(200, "text/plain", "hello"));
        try (var client = client(Redirects.NONE)) {
            OutboundResponse response = client.send(OutboundRequest.get(at("source.test", "/ok")).header("Accept", "text/plain"));

            assertThat(response.status()).isEqualTo(200);
            assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("hello");
            assertThat(response.contentType()).startsWith("text/plain");
            assertThat(server.last("GET", "/ok").headers()).containsEntry("accept", "text/plain");
        }
    }

    @Test
    void aHostIsLookedUpOnceAndConnectedToAtTheAddressThatPassed() {
        server.respond("GET", "/ok", Response.of(200, "text/plain", "hello"));
        try (var client = client(Redirects.NONE)) {
            client.send(OutboundRequest.get(at("source.test", "/ok")));
        }

        assertThat(lookups).hasValue(1);
        assertThat(server.count("GET", "/ok")).isEqualTo(1);
    }

    @Test
    void refusesARefusedAddressWithoutConnecting() {
        server.respond("GET", "/ok", Response.of(200, "text/plain", "hello"));
        try (var client = client(Redirects.NONE, Duration.ofSeconds(2), 2, false)) {
            ContentSourceException named = failureOf(() -> client.send(OutboundRequest.get(at("source.test", "/ok"))));
            ContentSourceException literal = failureOf(() -> client.send(OutboundRequest.get(at("127.0.0.1", "/ok"))));

            assertThat(named.kind()).isEqualTo(Kind.BLOCKED);
            assertThat(named).hasMessage("Home Control does not connect to source.test (address not allowed)");
            assertThat(literal.kind()).isEqualTo(Kind.BLOCKED);
        }
        assertThat(server.requests()).isEmpty();
    }

    @Test
    void sameOriginFollowsWithinTheOriginAndRefusesAnother() {
        server.respond("GET", "/a", Response.empty(302).withHeader("Location", "/b"));
        server.respond("GET", "/b", Response.of(200, "text/plain", "b"));
        server.respond("GET", "/away", Response.empty(302).withHeader("Location", at("other.test", "/b").toString()));
        try (var client = client(Redirects.SAME_ORIGIN)) {
            assertThat(client.send(OutboundRequest.get(at("source.test", "/a"))).body()).asString().isEqualTo("b");

            ContentSourceException away = failureOf(() -> client.send(OutboundRequest.get(at("source.test", "/away"))));
            assertThat(away.kind()).isEqualTo(Kind.BLOCKED);
            assertThat(away).hasMessageContaining(OutboundFailure.OTHER_ORIGIN);
        }
    }

    @Test
    void resolvesRelativeRedirects() {
        server.respond("GET", "/relative", Response.empty(302).withHeader("Location", "b"));
        server.respond("GET", "/protocol-relative", Response.empty(302)
                .withHeader("Location", "//other.test:" + server.url().getPort() + "/b"));
        server.respond("GET", "/b", Response.of(200, "text/plain", "b"));
        try (var sameOrigin = client(Redirects.SAME_ORIGIN); var checked = client(Redirects.CHECKED)) {
            assertThat(sameOrigin.send(OutboundRequest.get(at("source.test", "/relative"))).body()).asString().isEqualTo("b");
            assertThat(failureOf(() -> sameOrigin.send(OutboundRequest.get(at("source.test", "/protocol-relative")))).kind())
                    .isEqualTo(Kind.BLOCKED);
            assertThat(checked.send(OutboundRequest.get(at("source.test", "/protocol-relative"))).body()).asString().isEqualTo("b");
        }
    }

    @Test
    void checkedFollowsAnotherOriginAndChecksEveryHop() {
        server.respond("GET", "/away", Response.empty(302).withHeader("Location", at("other.test", "/b").toString()));
        server.respond("GET", "/b", Response.of(200, "text/plain", "b"));
        server.respond("GET", "/metadata", Response.empty(302).withHeader("Location", "http://metadata.test/latest"));
        try (var client = client(Redirects.CHECKED)) {
            OutboundResponse away = client.send(OutboundRequest.get(at("source.test", "/away")));
            assertThat(away.body()).asString().isEqualTo("b");
            assertThat(away.uri()).isEqualTo(at("other.test", "/b"));

            assertThat(failureOf(() -> client.send(OutboundRequest.get(at("source.test", "/metadata")))).kind())
                    .isEqualTo(Kind.BLOCKED);
        }
    }

    @Test
    void stopsAfterTheLastAllowedRedirect() {
        server.respond("GET", "/loop", Response.empty(302).withHeader("Location", "/loop"));
        try (var client = client(Redirects.CHECKED)) {
            ContentSourceException loop = failureOf(() -> client.send(OutboundRequest.get(at("source.test", "/loop"))));

            assertThat(loop.kind()).isEqualTo(Kind.BAD_RESPONSE);
            assertThat(loop).hasMessageContaining(OutboundFailure.TOO_MANY_REDIRECTS);
        }
        assertThat(server.count("GET", "/loop")).isEqualTo(3);
    }

    @Test
    void aRedirectWithoutADestinationIsABadResponse() {
        server.respond("GET", "/nowhere", Response.empty(302));
        try (var client = client(Redirects.CHECKED)) {
            assertThat(failureOf(() -> client.send(OutboundRequest.get(at("source.test", "/nowhere")))))
                    .hasMessageContaining(OutboundFailure.NO_REDIRECT_TARGET);
        }
    }

    @Test
    void returnsTheRedirectWhenNotFollowingAndForAPost() {
        server.respond(FakeHttpServer.ANY_METHOD, "/a", Response.empty(302).withHeader("Location", "/b"));
        try (var none = client(Redirects.NONE); var checked = client(Redirects.CHECKED)) {
            OutboundResponse notFollowed = none.send(OutboundRequest.get(at("source.test", "/a")));
            OutboundResponse posted = checked.send(OutboundRequest.post(at("source.test", "/a"),
                    "x=1".getBytes(StandardCharsets.UTF_8), "application/x-www-form-urlencoded"));

            assertThat(notFollowed.status()).isEqualTo(302);
            assertThat(notFollowed.header("location")).isEqualTo("/b");
            assertThat(posted.status()).isEqualTo(302);
            assertThat(server.last("POST", "/a").body()).isEqualTo("x=1");
        }
    }

    @Test
    void aBodyOfExactlyTheCapIsRead() {
        server.respond("GET", "/cap", Response.of(200, "text/plain", "a".repeat(CAP)));
        try (var client = client(Redirects.NONE)) {
            assertThat(client.send(OutboundRequest.get(at("source.test", "/cap"))).body()).hasSize(CAP);
        }
    }

    @Test
    void oneByteOverTheCapFailsWithOrWithoutContentLength() {
        server.respond("GET", "/declared", Response.of(200, "text/plain", "a".repeat(CAP + 1)));
        server.handle("GET", "/chunked", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (exchange; OutputStream out = exchange.getResponseBody()) {
                out.write("a".repeat(CAP + 1).getBytes(StandardCharsets.UTF_8));
            }
        });
        try (var client = client(Redirects.NONE)) {
            ContentSourceException declared = failureOf(() -> client.send(OutboundRequest.get(at("source.test", "/declared"))));
            ContentSourceException chunked = failureOf(() -> client.send(OutboundRequest.get(at("source.test", "/chunked"))));

            assertThat(declared.kind()).isEqualTo(Kind.TOO_LARGE);
            assertThat(declared).hasMessage("The source at source.test sent more than 64 bytes");
            assertThat(chunked.kind()).isEqualTo(Kind.TOO_LARGE);
            assertThat(client.send(OutboundRequest.get(at("source.test", "/declared")).limitedTo(CAP + 1)).body())
                    .hasSize(CAP + 1);
        }
    }

    @Test
    void refusesACompressedBody() {
        server.respond("GET", "/gzip", Response.of(200, "text/plain", "x").withHeader("Content-Encoding", "gzip"));
        try (var client = client(Redirects.NONE)) {
            ContentSourceException gzip = failureOf(() -> client.send(OutboundRequest.get(at("source.test", "/gzip"))));

            assertThat(gzip.kind()).isEqualTo(Kind.BAD_RESPONSE);
            assertThat(gzip).hasMessageContaining(OutboundFailure.COMPRESSED);
        }
    }

    @Test
    void oneDeadlineCoversATrickledBody() {
        server.trickle("GET", "/slow");
        try (var client = client(Redirects.NONE, Duration.ofMillis(800), 2, true)) {
            ContentSourceException slow = failureOf(() -> client.send(OutboundRequest.get(at("source.test", "/slow"))));

            assertThat(slow.kind()).isEqualTo(Kind.UNREACHABLE);
            assertThat(slow).hasMessage("Could not reach the source at source.test (request timed out)");
        }
        assertThat(server.bytesTrickled()).isGreaterThan(1);
    }

    @Test
    void oneDeadlineCoversARedirectChain() {
        Duration hop = Duration.ofMillis(400);
        server.respond("GET", "/r1", Response.empty(302).withHeader("Location", "/r2").withDelay(hop));
        server.respond("GET", "/r2", Response.empty(302).withHeader("Location", "/r3").withDelay(hop));
        server.respond("GET", "/r3", Response.of(200, "text/plain", "late").withDelay(hop));
        try (var client = client(Redirects.CHECKED, Duration.ofMillis(1_000), 2, true)) {
            assertThat(failureOf(() -> client.send(OutboundRequest.get(at("source.test", "/r1")))))
                    .hasMessageContaining(OutboundFailure.TIMED_OUT);
        }
    }

    /**
     * A platform lookup ignores interruption, so it can answer after the caller gave up. The caller still hears at the
     * deadline, and the late answer never opens a connection: nothing is accepted on the socket it names.
     */
    @Test
    void aHangingLookupEndsAtTheDeadlineAndItsLateAnswerConnectsNowhere() throws Exception {
        AtomicInteger accepted = new AtomicInteger();
        try (var socket = new ServerSocket(0, 50, InetAddress.ofLiteral("127.0.0.1"))) {
            Thread.ofVirtual().start(() -> {
                while (!socket.isClosed()) {
                    try (var _ = socket.accept()) {
                        accepted.incrementAndGet();
                    } catch (IOException _) {
                        // closed at the end of the test
                    }
                }
            });
            CountDownLatch lookedUp = new CountDownLatch(1);
            var deaf = new OutboundAddressPolicy(true, host -> {
                lookedUp.countDown();
                awaitIgnoringInterrupts(release);
                return new InetAddress[] {InetAddress.ofLiteral("127.0.0.1")};
            });
            URI target = URI.create("http://source.test:" + socket.getLocalPort() + "/ok");
            try (var client = new GuardedHttpClient(profile(Redirects.NONE, Duration.ofMillis(300), 1), deaf,
                    failure -> new ContentSourceException(failure.kind(), failure.describe("the source")))) {
                assertThat(failureOf(() -> client.send(OutboundRequest.get(target))))
                        .hasMessageContaining(OutboundFailure.TIMED_OUT);
                assertThat(lookedUp.await(5, TimeUnit.SECONDS)).isTrue();

                release.countDown();
                // One slot: this call starts only once the late worker has finished and freed it.
                client.vet(OutboundRequest.get(target).endingBy(System.nanoTime() + Duration.ofSeconds(5).toNanos()));
            }
            await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2)).until(() -> accepted.get() == 0);
        }
    }

    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
        boolean waiting = true;
        while (waiting) {
            try {
                waiting = !latch.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                // A platform resolver does not stop when its thread is interrupted; neither does this one.
            }
        }
    }

    @Test
    void failsFastWhenEverySlotIsBusyOrWaitsUntilTheCallersDeadline() throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        blockUntilReleased("/held", entered);
        try (var client = client(Redirects.NONE, Duration.ofSeconds(5), 1, true)) {
            CompletableFuture<?> holder = CompletableFuture.runAsync(() -> client.send(OutboundRequest.get(at("source.test", "/held"))));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            ContentSourceException fast = failureOf(() -> client.send(OutboundRequest.get(at("source.test", "/other")).failingFastWhenBusy()));
            ContentSourceException waited = failureOf(() -> client.send(OutboundRequest.get(at("source.test", "/other"))
                    .endingBy(System.nanoTime() + Duration.ofMillis(200).toNanos())));

            assertThat(fast.kind()).isEqualTo(Kind.RATE_LIMITED);
            assertThat(fast).hasMessage("Home Control is busy talking to the source; try again in a moment");
            assertThat(waited.kind()).isEqualTo(Kind.RATE_LIMITED);
            release.countDown();
            holder.join();
        }
    }

    @Test
    void closingCancelsExchangesInFlight() throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        blockUntilReleased("/held", entered);
        var client = client(Redirects.NONE);
        CompletableFuture<ContentSourceException> held = CompletableFuture.supplyAsync(
                () -> failureOf(() -> client.send(OutboundRequest.get(at("source.test", "/held")))));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        client.close();

        assertThat(held.join()).hasMessageContaining(OutboundFailure.CLOSED);
        assertThat(failureOf(() -> client.send(OutboundRequest.get(at("source.test", "/ok")))))
                .hasMessageContaining(OutboundFailure.CLOSED);
    }

    @Test
    void anInterruptedCallerOpensNoConnection() {
        server.respond("GET", "/ok", Response.of(200, "text/plain", "hello"));
        try (var client = client(Redirects.NONE)) {
            Thread.currentThread().interrupt();
            ContentSourceException interrupted = failureOf(() -> client.send(OutboundRequest.get(at("source.test", "/ok"))));

            assertThat(Thread.interrupted()).isTrue();
            assertThat(interrupted).hasMessageContaining(OutboundFailure.INTERRUPTED);
        }
        assertThat(server.requests()).isEmpty();
    }

    @Test
    void anUnknownHostAndARefusedConnectionAreUnreachable() throws IOException {
        int closedPort;
        try (var socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        try (var client = client(Redirects.NONE)) {
            assertThat(failureOf(() -> client.send(OutboundRequest.get(URI.create("http://nowhere.test/x")))))
                    .hasMessage("Could not reach the source at nowhere.test (unknown host)");
            assertThat(failureOf(() -> client.send(OutboundRequest.get(URI.create("http://source.test:" + closedPort + "/x")))))
                    .hasMessage("Could not reach the source at source.test (connection refused)");
        }
    }

    @Test
    void noFailureRevealsThePathQueryOrACause() throws IOException {
        int closedPort;
        try (var socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        server.respond("GET", "/secret-path/large", Response.of(200, "text/plain", "a".repeat(CAP + 1)));
        server.respond("GET", "/secret-path/gzip", Response.of(200, "text/plain", "x").withHeader("Content-Encoding", "gzip"));
        server.respond("GET", "/secret-path/away", Response.empty(302).withHeader("Location", at("other.test", "/secret-away").toString()));
        server.trickle("GET", "/secret-path/slow");
        try (var client = client(Redirects.SAME_ORIGIN, Duration.ofMillis(600), 2, true);
             var refusing = client(Redirects.SAME_ORIGIN, Duration.ofMillis(600), 2, false)) {
            for (Runnable call : new Runnable[] {
                    () -> client.send(OutboundRequest.get(at("source.test", "/secret-path/large?token=secret-token"))),
                    () -> client.send(OutboundRequest.get(at("source.test", "/secret-path/gzip?token=secret-token"))),
                    () -> client.send(OutboundRequest.get(at("source.test", "/secret-path/away?token=secret-token"))),
                    () -> client.send(OutboundRequest.get(at("source.test", "/secret-path/slow?token=secret-token"))),
                    () -> client.send(OutboundRequest.get(URI.create("http://source.test:" + closedPort + "/secret-path?token=secret-token"))),
                    () -> refusing.send(OutboundRequest.get(at("source.test", "/secret-path?token=secret-token")))}) {
                ContentSourceException failure = failureOf(call);

                assertThat(failure).hasNoCause();
                assertThat(failure.getMessage()).doesNotContain("secret");
            }
        }
    }

    @Test
    void vettingChecksTheAddressWithoutConnecting() {
        try (var client = client(Redirects.NONE, Duration.ofSeconds(2), 2, false)) {
            assertThat(failureOf(() -> client.vet(OutboundRequest.get(at("source.test", "/media.mp4")))).kind())
                    .isEqualTo(Kind.BLOCKED);
        }
        try (var client = client(Redirects.NONE)) {
            client.vet(OutboundRequest.get(at("source.test", "/media.mp4")));
        }
        assertThat(server.requests()).isEmpty();
    }

    @Test
    void anOriginIsItsSchemeHostAndPortWithDefaultsFilledIn() {
        assertThat(GuardedHttpClient.sameOrigin(URI.create("HTTP://Example.COM/a"), URI.create("http://example.com:80/b"))).isTrue();
        assertThat(GuardedHttpClient.sameOrigin(URI.create("https://host/a"), URI.create("https://host:443/b"))).isTrue();
        assertThat(GuardedHttpClient.sameOrigin(URI.create("https://host/a"), URI.create("http://host:443/a"))).isFalse();
        assertThat(GuardedHttpClient.sameOrigin(URI.create("http://host/a"), URI.create("http://host:81/a"))).isFalse();
    }

    @Test
    void anErrorOnTheWorkerFailsTheCallerAtOnceAndFreesItsSlot() {
        var broken = new OutboundAddressPolicy(true, host -> {
            throw new AssertionError("resolver bug");
        });
        // Far longer than the test could wait: a caller left waiting for its deadline would hang here.
        try (var client = new GuardedHttpClient(profile(Redirects.NONE, Duration.ofSeconds(60), 1), broken,
                failure -> new ContentSourceException(failure.kind(), failure.describe("the source")))) {
            // More calls than slots: each failed worker must have given its slot back.
            for (int call = 0; call < 3; call++) {
                assertThat(failureOf(() -> client.vet(OutboundRequest.get(URI.create("http://fixture.invalid/media")))))
                        .hasMessageContaining(OutboundFailure.FAILED);
            }
        }
    }

    @Test
    void anErrorBodyIsReadOnlyWhenAskedFor() throws Exception {
        server.respond("GET", "/explained", Response.of(500, "text/plain", "quota exceeded"));
        server.handle("GET", "/stalled", exchange -> {
            exchange.sendResponseHeaders(403, 0);
            exchange.getResponseBody().write('x');
            exchange.getResponseBody().flush();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        try (var client = client(Redirects.NONE, Duration.ofSeconds(8), 2, true)) {
            OutboundResponse explained = client.send(OutboundRequest.get(at("source.test", "/explained")).withErrorBody());
            CompletableFuture<OutboundResponse> stalled = CompletableFuture.supplyAsync(
                    () -> client.send(OutboundRequest.get(at("source.test", "/stalled"))));

            assertThat(explained.body()).asString().isEqualTo("quota exceeded");
            // The stalled body is dropped unread: the answer comes long before the 8-second deadline.
            assertThat(stalled.get(3, TimeUnit.SECONDS).status()).isEqualTo(403);
            assertThat(client.send(OutboundRequest.get(at("source.test", "/explained"))).body()).isEmpty();
        }
    }
    @Test
    void aFailureOnARedirectNamesTheHostItWasGoingTo() {
        server.respond("GET", "/metadata", Response.empty(302).withHeader("Location", "http://metadata.test/latest"));
        try (var client = client(Redirects.CHECKED)) {
            assertThat(failureOf(() -> client.send(OutboundRequest.get(at("source.test", "/metadata")))))
                    .hasMessage("Home Control does not connect to metadata.test (address not allowed)");
        }
    }
    @Test
    void anAnswerOtherThan200IsNotReadUnlessAsked() throws Exception {
        server.handle("GET", "/partial", exchange -> {
            exchange.sendResponseHeaders(206, 0);
            exchange.getResponseBody().write('x');
            exchange.getResponseBody().flush();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        try (var client = client(Redirects.NONE, Duration.ofSeconds(8), 2, true)) {
            CompletableFuture<OutboundResponse> partial = CompletableFuture.supplyAsync(
                    () -> client.send(OutboundRequest.get(at("source.test", "/partial"))));

            // A caller that only accepts 200 rejects the status; the body that never ends is not waited for.
            OutboundResponse answered = partial.get(3, TimeUnit.SECONDS);
            assertThat(answered.status()).isEqualTo(206);
            assertThat(answered.body()).isEmpty();
        }
    }
    /**
     * A caller with a deadline of its own (a workflow run) waits for a slot until then, not only for the profile's
     * deadline; once admitted, the exchange still ends within the profile's deadline. Here the only slot is held past
     * the profile's 300 ms by a lookup that ignores interrupts, and the waiting call still gets through.
     */
    @Test
    void aCallerWithItsOwnDeadlineWaitsForASlotBeyondTheProfilesDeadline() throws InterruptedException {
        server.respond("GET", "/next", Response.of(200, "text/plain", "next"));
        CountDownLatch holding = new CountDownLatch(1);
        var slowForOneHost = new OutboundAddressPolicy(true, host -> {
            if (host.equals("held.test")) {
                holding.countDown();
                awaitIgnoringInterrupts(release);
            }
            return new InetAddress[] {InetAddress.ofLiteral("127.0.0.1")};
        });
        try (var client = new GuardedHttpClient(profile(Redirects.NONE, Duration.ofMillis(300), 1), slowForOneHost,
                failure -> new ContentSourceException(failure.kind(), failure.describe("the source")))) {
            CompletableFuture.runAsync(() -> failureOf(() -> client.send(OutboundRequest.get(at("held.test", "/x")))));
            assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();
            CompletableFuture.delayedExecutor(1, TimeUnit.SECONDS).execute(release::countDown);
            long sent = System.nanoTime();

            OutboundResponse next = client.send(OutboundRequest.get(at("source.test", "/next"))
                    .endingBy(sent + Duration.ofSeconds(5).toNanos()));

            assertThat(next.body()).asString().isEqualTo("next");
            assertThat(Duration.ofNanos(System.nanoTime() - sent)).isGreaterThan(Duration.ofMillis(300));
        }
    }

    /** Jellyfin's session commands post nothing; the JDK client sent no content type for them, and neither do we. */
    @Test
    void aPostWithoutAContentTypeSendsNone() {
        server.respond("POST", "/command", Response.empty(204));
        try (var client = client(Redirects.NONE)) {
            client.send(OutboundRequest.post(at("source.test", "/command"), new byte[0], null));
        }

        assertThat(server.last("POST", "/command").headers()).doesNotContainKey("content-type");
        assertThat(server.last("POST", "/command").body()).isEmpty();
    }
}
