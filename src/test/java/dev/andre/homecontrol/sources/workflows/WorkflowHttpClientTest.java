package dev.andre.homecontrol.sources.workflows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

@Timeout(15)
class WorkflowHttpClientTest {
    private static WorkflowHttpClient client(Duration timeout) {
        return client(timeout, host -> new InetAddress[]{InetAddress.ofLiteral("127.0.0.1")});
    }

    private static WorkflowHttpClient client(Duration timeout, WorkflowUrlPolicy.HostResolver resolver) {
        return new WorkflowHttpClient(new WorkflowProperties(true, true, Duration.ofSeconds(1), timeout, 4, 2_097_152, 3),
                new WorkflowUrlPolicy(true, resolver));
    }

    private static WorkflowDraft.Fetch request(URI uri) {
        return new WorkflowDraft.Fetch(uri.toString(), List.of(new WorkflowDraft.Header("Authorization", "Bearer secret-marker")));
    }

    private static URI named(URI uri) { return URI.create(uri.toString().replace("127.0.0.1", "fixture.invalid")); }

    @Test void connectsToExactlyTheCheckedDnsAnswerAndRetainsHost() throws Exception {
        var calls = new AtomicInteger();
        try (var server = new FakeWorkflowServer(); var client = client(Duration.ofSeconds(3), host -> {
            assertThat(host).isEqualTo("fixture.invalid");
            return new InetAddress[]{InetAddress.ofLiteral(calls.incrementAndGet() == 1 ? "127.0.0.1" : "169.254.1.1")};
        })) {
            server.respond("/feed", 200, "{\"ok\":true}");
            assertThat(client.fetch(request(named(server.url("/feed"))))).asString().isEqualTo("{\"ok\":true}");
            assertThat(calls).hasValue(1);
            assertThat(server.requests("/feed").getFirst().header("Host")).startsWith("fixture.invalid:");
        }
    }

    @Test void aCrossOriginRedirectDoesNotForwardAuthorization() throws Exception {
        try (var first = new FakeWorkflowServer(); var other = new FakeWorkflowServer(); var client = client(Duration.ofSeconds(3))) {
            first.redirect("/feed", 302, other.url("/stolen").toString());
            var preparedArg55_0 = request(first.url("/feed"));
            assertThatThrownBy(() -> client.fetch(preparedArg55_0))
                    .isInstanceOf(WorkflowException.class).hasMessageContaining("Fetch JSON").hasMessageNotContaining("secret-marker");
            assertThat(other.count("/stolen")).isZero();
        }
    }

    @Test void followsThreeSameOriginRedirectsWithConfiguredHeadersButNoCookies() throws Exception {
        try (var server = new FakeWorkflowServer(); var client = client(Duration.ofSeconds(3))) {
            server.route("/a", e -> { e.getResponseHeaders().set("Set-Cookie", "secret=value"); e.getResponseHeaders().set("Location", "/b"); e.sendResponseHeaders(302, -1); });
            server.redirect("/b", 307, "/c");
            server.redirect("/c", 308, "/feed");
            server.respond("/feed", 200, "{}");
            assertThat(client.fetch(request(server.url("/a")))).asString().isEqualTo("{}");
            assertThat(server.requests("/feed").getFirst().header("Authorization")).isEqualTo("Bearer secret-marker");
            assertThat(server.requests("/feed").getFirst().header("Cookie")).isNull();
        }
    }

    @Test void rejectsFourthRedirect() throws Exception {
        try (var server = new FakeWorkflowServer(); var client = client(Duration.ofSeconds(3))) {
            for (int i = 0; i < 4; i++) server.redirect("/" + i, 302, "/" + (i + 1));
            server.respond("/4", 200, "{}");
            var preparedArg77_0 = request(server.url("/0"));
            assertThatThrownBy(() -> client.fetch(preparedArg77_0)).isInstanceOf(WorkflowException.class).hasMessageContaining("redirect");
            assertThat(server.count("/4")).isZero();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"http://[broken", "file:///tmp/secret-marker", "http://secret-marker@localhost/a", "/a#secret-marker"})
    void rejectsMalformedOrUnsafeRedirects(String location) throws Exception {
        try (var server = new FakeWorkflowServer(); var client = client(Duration.ofSeconds(3))) {
            server.redirect("/a", 302, location);
            var preparedArg86_0 = request(server.url("/a"));
            assertThatThrownBy(() -> client.fetch(preparedArg86_0)).isInstanceOf(WorkflowException.class).hasMessageNotContaining("secret-marker");
        }
    }

    @ParameterizedTest @ValueSource(ints = {201, 204, 301, 400, 401, 403, 500})
    void rejectsNon200AndRecoversCapacity(int status) throws Exception {
        try (var server = new FakeWorkflowServer(); var client = client(Duration.ofSeconds(3))) {
            server.respond("/bad", status, "secret-marker");
            server.respond("/ok", 200, "{}");
            for (int i = 0; i < 6; i++) {
                var preparedArg96_0 = request(server.url("/bad"));
                assertThatThrownBy(() -> client.fetch(preparedArg96_0)).isInstanceOf(WorkflowException.class).hasMessageNotContaining("secret-marker").hasCause(null);
                assertThat(client.fetch(request(server.url("/ok")))).asString().isEqualTo("{}");
            }
        }
    }

    @Test void boundsBodyAtTwoMibAndRejectsCompression() throws Exception {
        try (var server = new FakeWorkflowServer(); var client = client(Duration.ofSeconds(3))) {
            server.respond("/exact", 200, " ".repeat(2_097_152));
            server.respond("/big", 200, " ".repeat(2_097_153));
            server.route("/compressed", e -> { e.getResponseHeaders().set("Content-Encoding", "gzip"); e.sendResponseHeaders(200, 0); e.getResponseBody().write("secret-marker".getBytes()); });
            assertThat(client.fetch(request(server.url("/exact")))).hasSize(2_097_152);
            var preparedArg108_0 = request(server.url("/big"));
            assertThatThrownBy(() -> client.fetch(preparedArg108_0)).isInstanceOf(WorkflowException.class).hasMessageContaining("large");
            var preparedArg109_0 = request(server.url("/compressed"));
            assertThatThrownBy(() -> client.fetch(preparedArg109_0)).isInstanceOf(WorkflowException.class).hasMessageContaining("compression");
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void deadlineCoversBothHeadersAndBody(boolean afterHeaders) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var server = new FakeWorkflowServer(); var client = client(Duration.ofMillis(350)); var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            server.block("/slow", afterHeaders, entered, release);
            long start = System.nanoTime();
            var result = callers.submit(() -> catchThrowable(() -> client.fetch(request(server.url("/slow")))));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(result.get(3, TimeUnit.SECONDS)).isInstanceOf(WorkflowException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
        } finally { release.countDown(); }
    }

    @Test void fourActiveRequestsRejectFifthAndRecoverAfterCompletion() throws Exception {
        var entered = new CountDownLatch(4);
        var release = new CountDownLatch(1);
        try (var server = new FakeWorkflowServer(); var client = client(Duration.ofSeconds(5)); var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            server.block("/slow", true, entered, release);
            var futures = new ArrayList<java.util.concurrent.Future<byte[]>>();
            for (int i = 0; i < 4; i++) futures.add(callers.submit(() -> client.fetch(request(server.url("/slow")))));
            try {
                assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
                var preparedArg136_0 = request(server.url("/fifth"));
                assertThatThrownBy(() -> client.fetch(preparedArg136_0)).isInstanceOf(WorkflowException.class).hasMessageContaining("busy");
                assertThat(server.count("/fifth")).isZero();
            } finally { release.countDown(); }
            for (var future : futures) assertThat(future.get(3, TimeUnit.SECONDS)).asString().isEqualTo("{}");
            server.respond("/ok", 200, "{}");
            assertThat(client.fetch(request(server.url("/ok")))).asString().isEqualTo("{}");
        } finally { release.countDown(); }
    }

    @Test void callerInterruptionCancelsBodyAndPreservesInterruptFlag() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var interrupted = new AtomicBoolean();
        try (var server = new FakeWorkflowServer(); var client = client(Duration.ofSeconds(5))) {
            server.block("/slow", true, entered, release);
            var caller = Thread.ofVirtual().start(() -> {
                failure.set(catchThrowable(() -> client.fetch(request(server.url("/slow")))));
                interrupted.set(Thread.currentThread().isInterrupted());
            });
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            caller.interrupt();
            caller.join(2000);
            assertThat(caller.isAlive()).isFalse();
            assertThat(failure.get()).isInstanceOf(WorkflowException.class);
            assertThat(interrupted).isTrue();
        } finally { release.countDown(); }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void timedOutDnsWorkersRetainAllPermitsUntilTheyActuallyExit(boolean media) throws Exception {
        var entered = new CountDownLatch(4);
        var release = new CountDownLatch(1);
        var resolved = new CountDownLatch(4);
        try (var server = new FakeWorkflowServer(); var client = client(Duration.ofMillis(400), host -> {
            entered.countDown();
            boolean done = false;
            while (!done) {
                try { release.await(); done = true; } catch (InterruptedException _) { /* simulate uncancellable DNS */ }
            }
            resolved.countDown();
            return new InetAddress[]{InetAddress.ofLiteral("127.0.0.1")};
        }); var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = new ArrayList<java.util.concurrent.Future<Throwable>>();
            for (int i = 0; i < 4; i++) results.add(callers.submit(() -> catchThrowable(() -> {
                if (media) client.checkMedia(named(server.url("/late")));
                else client.fetch(request(named(server.url("/late"))));
            })));
            try {
                assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
                for (var result : results) assertThat(result.get(3, TimeUnit.SECONDS)).isInstanceOf(WorkflowException.class);
                var preparedArg187_0 = named(server.url("/media"));
                assertThatThrownBy(() -> client.checkMedia(preparedArg187_0)).isInstanceOf(WorkflowException.class).hasMessageContaining("busy");
                var preparedArg188_0 = request(server.url("/fifth"));
                assertThatThrownBy(() -> client.fetch(preparedArg188_0)).isInstanceOf(WorkflowException.class).hasMessageContaining("busy");
            } finally { release.countDown(); }
            assertThat(resolved.await(3, TimeUnit.SECONDS)).isTrue();
            server.respond("/ok", 200, "{}");
            awaitCapacity(client, server.url("/ok"));
            assertThat(server.count("/late")).isZero();
        } finally { release.countDown(); }
    }

    @Test void mediaValidationUsesBoundedDnsButMakesNoHttpRequest() throws Exception {
        try (var server = new FakeWorkflowServer(); var client = client(Duration.ofSeconds(3))) {
            client.checkMedia(named(server.url("/media")));
            assertThat(server.count("/media")).isZero();
            var preparedArg201_0 = URI.create("http://169.254.1.1/media");
            assertThatThrownBy(() -> client.checkMedia(preparedArg201_0)).isInstanceOf(WorkflowException.class).hasMessageContaining("Build media URL");
        }
    }

    @Test void rejectsUntrustedTlsEvenThroughInjectedDns() throws Exception {
        try (var server = FakeWorkflowServer.untrustedHttps(); var client = client(Duration.ofSeconds(3))) {
            server.respond("/feed", 200, "{}");
            var preparedArg208_0 = request(named(server.url("/feed")));
            assertThatThrownBy(() -> client.fetch(preparedArg208_0)).isInstanceOf(WorkflowException.class).hasCause(null);
            assertThat(server.count("/feed")).isZero();
        }
    }


    @Test void revalidatesDnsAtEveryRedirectConnection() throws Exception {
        var calls = new AtomicInteger();
        try (var server = new FakeWorkflowServer(); var client = client(Duration.ofSeconds(3), host ->
                new InetAddress[]{InetAddress.ofLiteral(calls.incrementAndGet() == 1 ? "127.0.0.1" : "169.254.1.1")})) {
            server.redirect("/feed", 302, "/next");
            server.respond("/next", 200, "{}");
            var preparedArg220_0 = request(named(server.url("/feed")));
            assertThatThrownBy(() -> client.fetch(preparedArg220_0)).isInstanceOf(WorkflowException.class);
            assertThat(server.count("/feed")).isOne();
            assertThat(server.count("/next")).isZero();
            assertThat(calls).hasValue(2);
        }
    }

    @Test void mixedDnsAnswersPreventAnyConnection() throws Exception {
        try (var server = new FakeWorkflowServer(); var client = client(Duration.ofSeconds(3), host ->
                new InetAddress[]{InetAddress.ofLiteral("127.0.0.1"), InetAddress.ofLiteral("169.254.1.1")})) {
            var preparedArg230_0 = request(named(server.url("/feed")));
            assertThatThrownBy(() -> client.fetch(preparedArg230_0)).isInstanceOf(WorkflowException.class);
            assertThat(server.count("/feed")).isZero();
        }
    }

    @Test void rejectsLiteralLoopbackWithoutConsultingAnAllowedDnsAnswer() throws Exception {
        try (var server = new FakeWorkflowServer(); var client = new WorkflowHttpClient(
                new WorkflowProperties(true, false, Duration.ofSeconds(1), Duration.ofSeconds(2), 4, 2_097_152, 3),
                new WorkflowUrlPolicy(false, host -> new InetAddress[]{InetAddress.ofLiteral("192.168.1.1")}))) {
            var preparedArg239_0 = request(server.url("/feed"));
            assertThatThrownBy(() -> client.fetch(preparedArg239_0)).isInstanceOf(WorkflowException.class);
            assertThat(server.count("/feed")).isZero();
        }
    }

    @Test void rejectsChunkedOverflowWithoutWaitingForTheResponseToFinish() throws Exception {
        var release = new CountDownLatch(1);
        try (var server = new FakeWorkflowServer(); var client = client(Duration.ofSeconds(4)); var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            server.route("/large", e -> {
                e.sendResponseHeaders(200, 0);
                e.getResponseBody().write(new byte[2_097_153]);
                e.getResponseBody().flush();
                try { release.await(); } catch (InterruptedException _) { Thread.currentThread().interrupt(); }
            });
            var result = callers.submit(() -> catchThrowable(() -> client.fetch(request(server.url("/large")))));
            assertThat(result.get(2, TimeUnit.SECONDS)).isInstanceOf(WorkflowException.class).hasMessageContaining("large");
        } finally { release.countDown(); }
    }

    @ParameterizedTest @ValueSource(ints = {302, 403})
    void doesNotDrainBlockedRedirectOrErrorBodies(int status) throws Exception {
        var release = new CountDownLatch(1);
        try (var server = new FakeWorkflowServer(); var client = client(Duration.ofSeconds(4)); var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            server.route("/blocked", e -> {
                e.getResponseHeaders().set("Location", "/ok");
                e.sendResponseHeaders(status, 0);
                e.getResponseBody().write('x');
                e.getResponseBody().flush();
                try { release.await(); } catch (InterruptedException _) { Thread.currentThread().interrupt(); }
            });
            server.respond("/ok", 200, "{}");
            var result = callers.submit(() -> {
                var blockedRequest = request(server.url("/blocked"));
                if (status == 302) assertThat(client.fetch(blockedRequest)).asString().isEqualTo("{}");
                else assertThatThrownBy(() -> client.fetch(blockedRequest)).isInstanceOf(WorkflowException.class).hasMessageContaining("403");
            });
            result.get(2, TimeUnit.SECONDS);
        } finally { release.countDown(); }
    }

    @Test void totalDeadlineStopsAContinuouslyTricklingBody() throws Exception {
        var entered = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var chunks = new AtomicInteger();
        try (var server = new FakeWorkflowServer(); var client = client(Duration.ofMillis(500)); var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            server.route("/trickle", e -> {
                e.sendResponseHeaders(200, 0);
                entered.countDown();
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        e.getResponseBody().write(' ');
                        e.getResponseBody().flush();
                        chunks.incrementAndGet();
                        // Timed pacing models a slow peer; concurrency assertions synchronize on the latch.
                        if (finished.await(30, TimeUnit.MILLISECONDS)) break;
                    }
                } catch (InterruptedException _) { Thread.currentThread().interrupt(); }
            });
            var result = callers.submit(() -> catchThrowable(() -> client.fetch(request(server.url("/trickle")))));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(result.get(2, TimeUnit.SECONDS)).isInstanceOf(WorkflowException.class);
            assertThat(chunks.get()).isGreaterThan(1);
        } finally { finished.countDown(); }
    }

    @Test void closeCancelsActiveRequestsAndRejectsFurtherWork() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var server = new FakeWorkflowServer(); var client = client(Duration.ofSeconds(10)); var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            server.block("/slow", true, entered, release);
            var result = callers.submit(() -> catchThrowable(() -> client.fetch(request(server.url("/slow")))));
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            client.close();
            assertThat(result.get(2, TimeUnit.SECONDS)).isInstanceOf(WorkflowException.class);
            var preparedArg312_0 = request(server.url("/next"));
            assertThatThrownBy(() -> client.fetch(preparedArg312_0)).isInstanceOf(WorkflowException.class).hasMessageContaining("closed");
            var preparedArg313_0 = server.url("/next");
            assertThatThrownBy(() -> client.checkMedia(preparedArg313_0)).isInstanceOf(WorkflowException.class).hasMessageContaining("closed");
        } finally { release.countDown(); }
    }

    @ParameterizedTest @ValueSource(strings = {"Host", "Cookie", "Connection", "Proxy-Authorization", "Accept-Encoding", "Content-Length", "Transfer-Encoding"})
    void neverSendsTransportOrCookieHeaderOverrides(String header) throws Exception {
        try (var server = new FakeWorkflowServer(); var client = client(Duration.ofSeconds(3))) {
            var request = new WorkflowDraft.Fetch(server.url("/feed").toString(), List.of(new WorkflowDraft.Header(header, "secret-marker")));
            assertThatThrownBy(() -> client.fetch(request)).isInstanceOf(WorkflowException.class).hasMessageNotContaining("secret-marker");
            assertThat(server.count("/feed")).isZero();
        }
    }

    private static void awaitCapacity(WorkflowHttpClient client, URI uri) {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (true) {
            try { assertThat(client.fetch(request(uri))).asString().isEqualTo("{}"); return; }
            catch (WorkflowException failure) {
                if (!failure.getMessage().contains("busy") || System.nanoTime() >= until) throw failure;
                Thread.yield();
            }
        }
    }
}
