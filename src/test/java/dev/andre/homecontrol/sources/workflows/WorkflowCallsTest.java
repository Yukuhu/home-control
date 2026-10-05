package dev.andre.homecontrol.sources.workflows;

import com.sun.net.httpserver.HttpExchange;
import dev.andre.homecontrol.sources.http.OutboundAddressPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static dev.andre.homecontrol.sources.workflows.WorkflowDraft.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

@Timeout(20)
class WorkflowCallsTest {
    private static WorkflowHttpClient client() {
        return new WorkflowHttpClient(new WorkflowProperties(true, true, Duration.ofSeconds(1), Duration.ofSeconds(5), 8, 2_097_152, 3),
                new OutboundAddressPolicy(true, host -> new InetAddress[]{InetAddress.ofLiteral("127.0.0.1")}));
    }

    private static Call call(String name, String url, Variable... variables) {
        return new Call(name, CallScope.SHARED, url, List.of(), List.of(variables));
    }

    private static void reply(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test void independentCallsRunAtTheSameTime() throws Exception {
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        try (var server = new FakeWorkflowServer(); var http = client()) {
            for (String path : List.of("/a", "/b")) {
                server.route(path, exchange -> {
                    entered.countDown();
                    try { release.await(); } catch (InterruptedException _) { Thread.currentThread().interrupt(); }
                    reply(exchange, "{\"v\":\"" + path + "\"}");
                });
            }
            var draft = WorkflowFixtures.singleWith(List.of(
                    call("a", server.url("/a").toString(), new Variable("a", "/v", false)),
                    call("b", server.url("/b").toString(), new Variable("b", "/v", false))));
            var plan = WorkflowPlan.of(draft);
            var outcome = CompletableFuture.supplyAsync(() -> new WorkflowCalls(http).run(draft.calls(), plan, Map.of(),
                    new WorkflowCalls.Run(Duration.ofSeconds(10), 4), null), Executors.newVirtualThreadPerTaskExecutor());
            assertThat(entered.await(3, TimeUnit.SECONDS)).as("both calls started before either finished").isTrue();
            release.countDown();
            var values = outcome.get(5, TimeUnit.SECONDS).values();
            assertThat(values.get("a").text()).isEqualTo("/a");
            assertThat(values.get("b").text()).isEqualTo("/b");
        } finally { release.countDown(); }
    }

    @Test void aDependentCallRunsAfterItsSourceAndUsesItsValue() throws Exception {
        try (var server = new FakeWorkflowServer(); var http = client()) {
            server.respond("/token", 200, "{\"token\":\"t-1\"}");
            server.respond("/list", 200, "{\"n\":3}");
            var draft = WorkflowFixtures.singleWith(List.of(
                    call("token", server.url("/token").toString(), new Variable("token", "/token", true)),
                    new Call("list", CallScope.SHARED, server.url("/list") + "?t={token}",
                            List.of(new Header("Authorization", "Bearer {token}")), List.of(new Variable("n", "/n", false)))));
            var outcome = new WorkflowCalls(http).run(draft.calls(), WorkflowPlan.of(draft), Map.of(),
                    new WorkflowCalls.Run(Duration.ofSeconds(10), 4), null);
            assertThat(outcome.values().get("n").text()).isEqualTo("3");
            var list = server.requests("/list").getFirst();
            assertThat(list.rawQuery()).isEqualTo("t=t-1");
            assertThat(list.header("Authorization")).isEqualTo("Bearer t-1");
            assertThat(outcome.responses()).containsKeys("token", "list");
            assertThat(outcome.toString()).doesNotContain("t-1");
        }
    }

    @Test void valuesFromAResponseNeverChangeTheHost() throws Exception {
        try (var server = new FakeWorkflowServer(); var http = client()) {
            server.respond("/token", 200, "{\"id\":\"x/y@evil.example?z\"}");
            server.respond("/item", 200, "{}");
            var draft = WorkflowFixtures.singleWith(List.of(
                    call("token", server.url("/token").toString(), new Variable("id", "/id", false)),
                    call("item", server.url("/item") + "?q={id}")));
            new WorkflowCalls(http).run(draft.calls(), WorkflowPlan.of(draft), Map.of(),
                    new WorkflowCalls.Run(Duration.ofSeconds(10), 4), null);
            // The value stayed one encoded query value, and the request reached the configured server.
            assertThat(server.requests("/item")).singleElement()
                    .satisfies(request -> assertThat(request.rawQuery()).isEqualTo("q=x%2Fy%40evil.example%3Fz"));
        }
    }

    @Test void aFailureNamesTheCallAndSkipsWhatDependsOnIt() throws Exception {
        try (var server = new FakeWorkflowServer(); var http = client()) {
            server.respond("/token", 500, "{}");
            server.respond("/list", 200, "{}");
            var draft = WorkflowFixtures.singleWith(List.of(
                    call("token", server.url("/token").toString(), new Variable("token", "/token", true)),
                    call("list", server.url("/list") + "?t={token}")));
            assertThatThrownBy(() -> new WorkflowCalls(http).run(draft.calls(), WorkflowPlan.of(draft), Map.of(),
                    new WorkflowCalls.Run(Duration.ofSeconds(10), 4), null))
                    .isInstanceOfSatisfying(WorkflowException.class, e -> assertThat(e.call()).isEqualTo("token"))
                    .hasMessage("Call token: server returned HTTP 500");
            assertThat(server.count("/list")).isZero();
        }
    }

    @Test void aFailureEndsTheRunWithoutWaitingForASlowIndependentCall() throws Exception {
        var release = new CountDownLatch(1);
        try (var server = new FakeWorkflowServer(); var http = client()) {
            server.respond("/broken", 404, "{}");
            server.route("/slow", exchange -> {
                try { release.await(); } catch (InterruptedException _) { Thread.currentThread().interrupt(); }
                reply(exchange, "{}");
            });
            var draft = WorkflowFixtures.singleWith(List.of(
                    call("slow", server.url("/slow").toString()), call("broken", server.url("/broken").toString())));
            long started = System.nanoTime();
            assertThatThrownBy(() -> new WorkflowCalls(http).run(draft.calls(), WorkflowPlan.of(draft), Map.of(),
                    new WorkflowCalls.Run(Duration.ofSeconds(10), 4), null))
                    .hasMessage("Call broken: server returned HTTP 404");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        } finally { release.countDown(); }
    }

    @Test void twoIndependentCallsFailingTogetherEndTheRunPromptlyWithOneMessage() throws Exception {
        var release = new CountDownLatch(1);
        try (var server = new FakeWorkflowServer(); var http = client()) {
            server.respond("/broken1", 404, "{}");
            server.respond("/broken2", 404, "{}");
            server.route("/slow", exchange -> {
                try { release.await(); } catch (InterruptedException _) { Thread.currentThread().interrupt(); }
                reply(exchange, "{}");
            });
            var draft = WorkflowFixtures.singleWith(List.of(call("slow", server.url("/slow").toString()),
                    call("broken1", server.url("/broken1").toString()), call("broken2", server.url("/broken2").toString())));
            long started = System.nanoTime();
            var plan = WorkflowPlan.of(draft);
            var run = new WorkflowCalls.Run(Duration.ofSeconds(10), 4);
            var engine = new WorkflowCalls(http);
            var calls = draft.calls();
            assertThatThrownBy(() -> engine.run(calls, plan, Map.of(), run, null))
                    .isInstanceOf(WorkflowException.class)
                    .hasMessageMatching("Call broken[12]: server returned HTTP 404");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        } finally { release.countDown(); }
    }

    @Test void aRunNeverHasMoreFetchesAtOnceThanItsShare() throws Exception {
        var active = new AtomicInteger();
        var most = new AtomicInteger();
        var overlap = new CountDownLatch(1);
        try (var server = new FakeWorkflowServer(); var http = client()) {
            for (String path : List.of("/a", "/b", "/c")) {
                server.route(path, exchange -> {
                    int now = active.incrementAndGet();
                    most.accumulateAndGet(now, Math::max);
                    if (now > 1) overlap.countDown();
                    // Stay active until a second fetch overlaps (the failure) or a generous window shows none did.
                    try { overlap.await(300, TimeUnit.MILLISECONDS); } catch (InterruptedException _) { Thread.currentThread().interrupt(); }
                    active.decrementAndGet();
                    reply(exchange, "{}");
                });
            }
            var draft = WorkflowFixtures.singleWith(List.of(call("a", server.url("/a").toString()),
                    call("b", server.url("/b").toString()), call("c", server.url("/c").toString())));
            new WorkflowCalls(http).run(draft.calls(), WorkflowPlan.of(draft), Map.of(),
                    new WorkflowCalls.Run(Duration.ofSeconds(10), 1), null);
            assertThat(most.get()).isEqualTo(1);
        }
    }

    @Test void theRunDeadlineEndsARunThatTakesTooLong() throws Exception {
        var release = new CountDownLatch(1);
        try (var server = new FakeWorkflowServer(); var http = client()) {
            server.route("/slow", exchange -> {
                try { release.await(); } catch (InterruptedException _) { Thread.currentThread().interrupt(); }
                reply(exchange, "{}");
            });
            var draft = WorkflowFixtures.singleWith(List.of(call("slow", server.url("/slow").toString())));
            long started = System.nanoTime();
            var plan = WorkflowPlan.of(draft);
            var run = new WorkflowCalls.Run(Duration.ofMillis(300), 4);
            var engine = new WorkflowCalls(http);
            var calls = draft.calls();
            assertThatThrownBy(() -> engine.run(calls, plan, Map.of(), run, null))
                    .isInstanceOf(WorkflowException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        } finally { release.countDown(); }
    }

    @Test void aPerEntryFailureNamesTheEntry() throws Exception {
        try (var server = new FakeWorkflowServer(); var http = client()) {
            server.respond("/images/news", 404, "{}");
            var chain = WorkflowFixtures.chain(server.url("/"));
            var plan = WorkflowPlan.of(chain);
            var known = Map.of("id", new WorkflowJson.Value("news", false), "token", new WorkflowJson.Value("t", true));
            var entryCalls = plan.refreshCalls(CallScope.ENTRY);
            var run = new WorkflowCalls.Run(Duration.ofSeconds(5), 3);
            assertThatThrownBy(() -> new WorkflowCalls(http).run(entryCalls, plan, known, run, "News"))
                    .hasMessage("Call images · entry \"News\": server returned HTTP 404");
        }
    }

    /** A client whose fetches wait for {@code release}, whatever the run's deadline says. */
    private static WorkflowHttpClient heldClient(CountDownLatch release) {
        WorkflowHttpClient held = mock(WorkflowHttpClient.class);
        given(held.fetch(any(), anyLong())).willAnswer(_ -> {
            release.await(10, TimeUnit.SECONDS);
            return "{}".getBytes(StandardCharsets.UTF_8);
        });
        return held;
    }

    @Test void aRunThatOutlastsItsBudgetIsStoppedWithoutWaitingForItsCalls() {
        var release = new CountDownLatch(1);
        try {
            var draft = WorkflowFixtures.singleWith(List.of(call("slow", "https://api.example/slow")));
            var engine = new WorkflowCalls(heldClient(release));
            var plan = WorkflowPlan.of(draft);
            var run = new WorkflowCalls.Run(Duration.ofMillis(200), 4);
            var calls = draft.calls();

            assertThatThrownBy(() -> engine.run(calls, plan, Map.of(), run, null))
                    .isInstanceOf(WorkflowException.class)
                    .hasMessage("Fetch JSON: the workflow took too long; try again later");
        } finally { release.countDown(); }
    }

    @Test void anInterruptedRunStopsAndKeepsTheInterrupt() throws Exception {
        var release = new CountDownLatch(1);
        try {
            var draft = WorkflowFixtures.singleWith(List.of(call("slow", "https://api.example/slow")));
            var engine = new WorkflowCalls(heldClient(release));
            var plan = WorkflowPlan.of(draft);
            var failure = new AtomicReference<Throwable>();
            var stillInterrupted = new AtomicReference<Boolean>();
            Thread running = Thread.ofPlatform().start(() -> {
                try {
                    engine.run(draft.calls(), plan, Map.of(), new WorkflowCalls.Run(Duration.ofSeconds(10), 4), null);
                } catch (RuntimeException e) {
                    failure.set(e);
                }
                stillInterrupted.set(Thread.currentThread().isInterrupted());
            });
            await().until(() -> running.getState() == Thread.State.TIMED_WAITING);

            running.interrupt();
            running.join(5000);

            assertThat(failure.get()).isInstanceOf(WorkflowException.class)
                    .hasMessage("Fetch JSON: request interrupted");
            assertThat(stillInterrupted.get()).isTrue();
        } finally { release.countDown(); }
    }

    @Test void anUnexpectedFailureInACallSaysNothingAboutIt() {
        WorkflowHttpClient broken = mock(WorkflowHttpClient.class);
        given(broken.fetch(any(), anyLong())).willThrow(new IllegalStateException("internal detail"));
        var draft = WorkflowFixtures.singleWith(List.of(call("main", "https://api.example/main")));
        var engine = new WorkflowCalls(broken);
        var plan = WorkflowPlan.of(draft);
        var run = new WorkflowCalls.Run(Duration.ofSeconds(5), 4);
        var calls = draft.calls();

        assertThatThrownBy(() -> engine.run(calls, plan, Map.of(), run, null))
                .isInstanceOf(WorkflowException.class)
                .hasMessage("Fetch JSON: request failed");
    }

    @Test void aRunWithoutCallsKeepsWhatItWasGiven() {
        var known = Map.of("id", new WorkflowJson.Value("news", false));
        var draft = WorkflowFixtures.singleWith(List.of());

        var outcome = new WorkflowCalls(mock(WorkflowHttpClient.class)).run(List.of(), WorkflowPlan.of(draft), known,
                new WorkflowCalls.Run(Duration.ofSeconds(1), 1), null);

        assertThat(outcome.values()).isEqualTo(known);
        assertThat(outcome.responses()).isEmpty();
    }
}
