package dev.andre.homecontrol.testsupport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static dev.andre.homecontrol.testsupport.FakeHttpServer.ANY_METHOD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.awaitility.Awaitility.await;

class FakeHttpServerTest {

    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private FakeHttpServer server;

    @BeforeEach
    void start() throws IOException {
        server = FakeHttpServer.start();
    }

    @AfterEach
    void stop() {
        server.close();
        client.close();
    }

    private HttpResponse<String> get(String pathAndQuery) throws Exception {
        return client.send(HttpRequest.newBuilder(server.url(pathAndQuery)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String pathAndQuery, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(server.url(pathAndQuery))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("X-Custom", "yes")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void answersACannedResponseWithItsStatusTypeHeadersAndBody() throws Exception {
        server.respond("GET", "/a", Response.json(301, "{\"ok\":true}").withHeader("Location", "http://elsewhere.invalid/"));

        HttpResponse<String> response = get("/a");

        assertThat(response.statusCode()).isEqualTo(301);
        assertThat(response.body()).isEqualTo("{\"ok\":true}");
        assertThat(response.headers().firstValue("Content-Type")).contains(Response.JSON);
        assertThat(response.headers().firstValue("Location")).contains("http://elsewhere.invalid/");
    }

    @Test
    void aHeldRouteRecordsTheRequestAtOnceAndAnswersWhenReleased() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        server.hold(ANY_METHOD, "/held", release, Response.of(200, "text/plain", "late"));

        CompletableFuture<HttpResponse<String>> answer = client.sendAsync(
                HttpRequest.newBuilder(server.url("/held")).build(), HttpResponse.BodyHandlers.ofString());
        await().until(() -> server.count(ANY_METHOD, "/held") == 1);
        assertThat(answer).isNotDone();

        release.countDown();

        assertThat(answer.get(5, TimeUnit.SECONDS).body()).isEqualTo("late");
    }

    @Test
    void unknownRoutesGet404WithoutABodyAndAreStillRecorded() throws Exception {
        HttpResponse<String> response = get("/missing?x=1");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).isEmpty();
        assertThat(server.requests()).singleElement().satisfies(request -> {
            assertThat(request.path()).isEqualTo("/missing");
            assertThat(request.query()).containsExactly(entry("x", "1"));
        });
    }

    @Test
    void aFallbackAnswersUnknownRoutesFromTheRequest() throws Exception {
        server.fallback(request -> Response.of(418, "text/plain", "teapot " + request.path()));

        assertThat(get("/x").statusCode()).isEqualTo(418);
        assertThat(get("/y").body()).isEqualTo("teapot /y");
    }

    @Test
    void routesMatchOnTheMethodUnlessAnyMethodIsGiven() throws Exception {
        server.respond("POST", "/post-only", Response.json(200, "{}"));
        server.respond(ANY_METHOD, "/any", Response.json(200, "{}"));

        assertThat(get("/post-only").statusCode()).isEqualTo(404);
        assertThat(post("/post-only", "").statusCode()).isEqualTo(200);
        assertThat(get("/any").statusCode()).isEqualTo(200);
        assertThat(post("/any", "").statusCode()).isEqualTo(200);
    }

    @Test
    void aPathEndingInDoubleStarMatchesEveryPathBelowIt() throws Exception {
        server.respond(ANY_METHOD, "/api/**", Response.json(200, "{}"));

        assertThat(get("/api/v1/json/123/eventsday.php").statusCode()).isEqualTo(200);
        assertThat(get("/other").statusCode()).isEqualTo(404);
    }

    @Test
    void aPredicateSelectsBetweenRoutesForTheSamePath() throws Exception {
        server.respond("GET", "/items", request -> "2".equals(request.query().get("page")), Response.json(200, "second"));
        server.respond("GET", "/items", request -> !request.query().containsKey("page"), Response.json(200, "first"));

        assertThat(get("/items").body()).isEqualTo("first");
        assertThat(get("/items?page=2").body()).isEqualTo("second");
        assertThat(get("/items?page=3").statusCode()).isEqualTo(404);
    }

    @Test
    void theNewestMatchingRouteAnswers() throws Exception {
        server.respond("GET", "/a", Response.json(200, "old"));
        server.respond("GET", "/a", Response.json(200, "new"));

        assertThat(get("/a").body()).isEqualTo("new");
    }

    @Test
    void answersComeInOrderAndTheLastOneRepeats() throws Exception {
        server.respond("POST", "/token", Response.json(428, "pending"), Response.json(200, "granted"));

        assertThat(post("/token", "").statusCode()).isEqualTo(428);
        assertThat(post("/token", "").statusCode()).isEqualTo(200);
        assertThat(post("/token", "").statusCode()).isEqualTo(200);
    }

    @Test
    void aHandlerAnswersItsOwnRoute() throws Exception {
        server.handle("GET", "/custom", exchange -> {
            exchange.getResponseHeaders().set("X-Test", "yes");
            exchange.sendResponseHeaders(204, -1);
        });

        HttpResponse<String> response = get("/custom");

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.headers().firstValue("X-Test")).contains("yes");
    }

    @Test
    void aHandlerCanReadTheRequestBody() throws Exception {
        server.handle("POST", "/echo", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        });

        HttpResponse<String> response = post("/echo", "hello");

        assertThat(response.body()).isEqualTo("hello");
        assertThat(server.last("POST", "/echo").body()).isEqualTo("hello");
    }

    @Test
    void everyRequestIsRecordedWithMethodPathQueryHeadersAndBody() throws Exception {
        server.respond("POST", "/form", Response.empty(204));

        post("/form?x=1&y=a%20b", "k=v");

        Request request = server.last("POST", "/form");
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/form");
        assertThat(request.rawQuery()).isEqualTo("x=1&y=a%20b");
        assertThat(request.query()).containsExactly(entry("x", "1"), entry("y", "a b"));
        assertThat(request.header("X-CUSTOM")).isEqualTo("yes");
        assertThat(request.body()).isEqualTo("k=v");
        assertThat(server.count("POST", "/form")).isEqualTo(1);
        assertThat(server.requests(ANY_METHOD, "/form")).hasSize(1);
        assertThat(server.requests("GET", "/form")).isEmpty();
    }

    @Test
    void aRepeatedQueryNameKeepsItsLastValue() {
        assertThat(Request.decode("a=1&a=2&b")).containsExactly(entry("a", "2"), entry("b", ""));
        assertThat(Request.decode(null)).isEmpty();
        assertThat(Request.decode("")).isEmpty();
    }

    @Test
    void lastNamesWhatWasReceivedWhenNothingMatched() throws Exception {
        get("/other");

        assertThatThrownBy(() -> server.last("GET", "/never"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("No GET /never received")
                .hasMessageContaining("/other");
    }

    @Test
    void aServerDelayHoldsEveryAnswer() throws Exception {
        server.delay(Duration.ofMillis(300));

        long started = System.nanoTime();
        get("/missing");

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofMillis(300));
    }

    @Test
    void aRouteDelayHoldsThatRoute() throws Exception {
        server.respond("GET", "/slow", Response.json(200, "{}").withDelay(Duration.ofMillis(300)));

        long started = System.nanoTime();
        get("/slow");

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofMillis(300));
    }

    @Test
    void aTricklingRouteSendsABodyThatNeverEnds() throws Exception {
        server.trickle(ANY_METHOD, "/**");

        HttpResponse<InputStream> response = client.send(HttpRequest.newBuilder(server.url("/feed")).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(body.read()).isEqualTo(' ');
            assertThat(body.read()).isEqualTo(' ');
        }

        // The server counts a byte after flushing it, so the second count can trail the read.
        await().atMost(Duration.ofSeconds(5)).until(() -> server.bytesTrickled() > 1);
        await().atMost(Duration.ofSeconds(5)).until(() -> server.openTrickles() == 0);
    }

    @Test
    void resetForgetsRoutesRequestsDelayAndFallback() throws Exception {
        server.respond("GET", "/a", Response.json(200, "{}"));
        server.fallback(Response.empty(418));
        get("/a");
        server.delay(Duration.ofSeconds(30));

        server.reset();

        assertThat(server.requests()).isEmpty();
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(server.url("/a"))
                .timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    @Timeout(20)
    void closeEndsBlockedHandlersAndTrickles() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        server.handle("GET", "/block", exchange -> {
            entered.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        });
        server.trickle("GET", "/trickle");
        client.sendAsync(HttpRequest.newBuilder(server.url("/block")).build(), HttpResponse.BodyHandlers.discarding());
        CompletableFuture<HttpResponse<InputStream>> trickling = client.sendAsync(
                HttpRequest.newBuilder(server.url("/trickle")).build(), HttpResponse.BodyHandlers.ofInputStream());
        entered.await();
        // Wait for the trickle to have written past its headers, not just for openTrickles() to have been
        // incremented (which happens before sendResponseHeaders and so can race close() below).
        await().atMost(Duration.ofSeconds(5)).until(() -> server.bytesTrickled() > 0);

        server.close();

        assertThat(server.openTrickles()).isZero();
        assertThatThrownBy(() -> get("/block")).isInstanceOf(IOException.class);
        // Nobody reads this trickling body; close it so the JDK HttpClient does not keep the exchange open
        // waiting for it, which would otherwise hang client.close() in @AfterEach.
        trickling.join().body().close();
    }
}
