# Phase 1.3a Shared Test Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One shared set of test helpers in `dev.andre.homecontrol.testsupport`. It holds a general HTTP fake that six web-API fakes wrap, one TLS helper, one mutable clock, one event-stream reader and one state recorder, and replaces the copies the tests carry today.

**Architecture:**
- `FakeHttpServer` owns the socket, routing, recording, delays, trickling and fallbacks.
- Each web-API fake (`FakeJellyfinServer` and five more) keeps its class name, its package and every method its tests call. It becomes a thin wrapper that translates its own vocabulary (fixtures, endpoints, keys) into `FakeHttpServer` routes, and translates recorded requests back into its own `Recorded` type.
- The protocol fakes (UPnP, Tizen, Cast, Android TV, mpv) stay as they are, except that Cast and the WebSocket fake take their server certificate from `TestTls`.

**Tech Stack:** Java 25, JUnit 5 with AssertJ and Awaitility, `com.sun.net.httpserver`, BouncyCastle (already on the classpath), Gradle through `scripts/gradle.sh`.

**Spec:** `docs/superpowers/specs/2026-09-27-phase-1-guardrails-design.md`, section "1.3a Shared test support (M)", and "Delivery and sequencing".

## Global Constraints

- **Start condition:** #127 (Phase 1.2) is merged. Branch `test/shared-test-support` from `origin/main`.
- **The package:** `dev.andre.homecontrol.testsupport`, in `src/test/java`. The `e2e` source set already has the test output on its classpath, so both suites can use it. Gradle's `java-test-fixtures` waits for the Phase 4 module split.
- **No production change:** `src/main` is not touched. `git diff --stat origin/main -- src/main` prints nothing at the end.
- **Test count:** the test pull requests keep the test count equal or higher. Each moved or deleted test names its replacement in the commit message.
- **Each fake moves in its own commit, with its tests green.** Each wrapped fake keeps every method, constant and nested type its tests use, so its test classes do not change.
- **The lines of fake and helper code are measured before and after** (Task 1 Step 1 and Task 11 Step 4).
- **Build and test only through `scripts/gradle.sh`.** There is no local JDK. `scripts/gradle.sh build` is green at the end.
- **Waiting:** a helper that waits on purpose (a delay, a trickle) uses `Thread.sleep` with `@SuppressWarnings("java:S2925")` and a one-line reason. Every other wait uses Awaitility.
- **No wall-clock upper bounds in new tests** (spec 1.3b). A delay is checked with a lower bound; hangs are guarded by `@Timeout`.
- **Commits:**
  - Conventional Commits, ending with a `Co-Authored-By:` trailer naming the model that wrote them.
  - Stage only the files you changed: `git add <paths>`, never `git add -A`.

## Plan decisions

Where the code turned out different from the spec's picture, this plan decides as follows:

1. **`TestTls` replaces the self-built certificates in `FakeCastReceiver`, `FakeWebSocketServer` and `FakeWorkflowServer`.**
   - The Android TV fakes (`FakePairingServer`, `FakeRemoteServer`, `RefusingPairingServer`) keep `TlsSockets.keyManagers(identity)`. Their `ClientCertificate` identity takes part in the pairing secret, and their trust manager is the pairing trust, so they belong to "Android TV's client-certificate pinning is not touched".
   - `FakeWebSocketServer` stops borrowing the Android TV adapter's `ClientCertificate`.
2. **`MutableClock` replaces six clocks, not four.** Besides the YouTube, TMDB, TheSportsDB and calendar copies, there are the nested copy in `SsdpDiscoveryTest` and `RailCacheTest.TestClock`, which is the same class under another name. The architecture page's progress measure goes from 5 to 1.
3. **`RecordingStateListener` replaces the recorded `List<DeviceState>` in the six session tests.**
   - Its `awaitStatus(status, atMost)` replaces the waits that scan the recorded list for a status: Cast twice, Tizen once, webOS twice.
   - The tests' own `awaitStatus` helpers poll `session.state()`, not the recorded list, so they stay as they are.
4. **`FakeHttpServer` routes on more than method, path and query,** because the fakes need it:
   - a predicate over the whole request (the Google fake matches form fields);
   - a list of answers where the last one repeats (OAuth "pending, then granted");
   - a `/**` suffix that matches every path below it (the trickling tests call any path);
   - a fallback that decides from the request (TheSportsDB answers 400 for an unknown API key in the path, and 404 for an unknown endpoint).

   The newest matching route answers, which is what `Map.put` gave the old fakes, and what "later rules win" gave the Google fake.
5. **Routing uses the raw path.** The Google and workflow fakes matched the decoded path; none of the paths their tests use contains an escape.
6. **A repeated query name keeps its last value** in `Request.query()`, as the Jellyfin, TMDB, calendar and TheSportsDB fakes had it. The Google fake keeps its own `decode`, which joins repeated values with commas, for its `Recorded.query()` and `form()`.

## Review Focus

- **`close()` while a handler blocks or trickles must return and free the port.** Otherwise a test class hangs until CI's job limit. `FakeWorkflowServer.block(...)` and the trickling tests depend on it. Test: `closeEndsBlockedHandlersAndTrickles` (Task 1).
- **A route registered again for the same method and path must win over the earlier one.** Tests override a fake's defaults this way: Jellyfin's `withConnectableServer`, TheSportsDB's per-endpoint defaults, and the Google fake's later rules. Test: `theNewestMatchingRouteAnswers` (Task 1). The wrapped fakes' own suites cover their defaults.
- **A request to an unknown route must still be recorded.** Tests assert that the client called at all. Test: `unknownRoutesGet404WithoutABodyAndAreStillRecorded` (Task 1).
- **A default client must still refuse the self-signed certificate.** The workflow fetcher's "untrusted HTTPS is refused" test depends on it. Test: `aDefaultClientRefusesTheCertificate` (Task 7).
- **A repeated query parameter must keep its last value.** A change here silently alters what the Jellyfin, TMDB and sports tests see. Test: `aRepeatedQueryNameKeepsItsLastValue` (Task 1).

---

### Task 1: `FakeHttpServer`, and the trickling server folded into it

**Files:**
- Create: `src/test/java/dev/andre/homecontrol/testsupport/FakeHttpServer.java`
- Create: `src/test/java/dev/andre/homecontrol/testsupport/Request.java`
- Create: `src/test/java/dev/andre/homecontrol/testsupport/Response.java`
- Test: `src/test/java/dev/andre/homecontrol/testsupport/FakeHttpServerTest.java`
- Modify: `src/test/java/dev/andre/homecontrol/sources/http/SlowBodyDeadlineTest.java`, `src/test/java/dev/andre/homecontrol/sources/http/BoundedBodyTest.java`
- Delete: `src/test/java/dev/andre/homecontrol/sources/http/TricklingServer.java`

**Interfaces:**
- Consumes: nothing.
- Produces (every later task uses these exact names):
  - `FakeHttpServer`:
    - `ANY_METHOD`;
    - `static start()` and `static start(SSLContext)`, both `throws IOException`;
    - `url()` and `url(String path)`;
    - `respond(String method, String path, Response... answers)` and `respond(String method, String path, Predicate<Request> when, Response... answers)`;
    - `handle(String method, String path, HttpHandler handler)`;
    - `trickle(String method, String path)`;
    - `fallback(Response)` and `fallback(Function<Request, Response>)`;
    - `delay(Duration)`;
    - `requests()`, `requests(String method, String path)`, `count(String method, String path)` and `last(String method, String path)`;
    - `bytesTrickled()` and `openTrickles()`;
    - `reset()` and `close()`.
  - `Request`: a record `(String method, URI uri, Map<String, String> query, Map<String, String> headers, String body)`, with `path()`, `rawQuery()`, `header(String)` and `static decode(String rawQuery)`.
  - `Response`: a record `(int status, String contentType, Map<String, String> headers, byte[] body, Duration delay)`, with:
    - `JSON` (`"application/json; charset=utf-8"`);
    - `static of(int, String, byte[])`, `static of(int, String, String)`, `static json(int, String)` and `static empty(int)`;
    - `withHeader(String, String)` and `withDelay(Duration)`.

- [ ] **Step 1: Record the baseline**

```bash
git switch -c test/shared-test-support origin/main
wc -l src/test/java/dev/andre/homecontrol/sources/jellyfin/FakeJellyfinServer.java \
  src/test/java/dev/andre/homecontrol/sources/sports/calendar/FakeCalendarServer.java \
  src/test/java/dev/andre/homecontrol/sources/sports/thesportsdb/FakeTheSportsDbServer.java \
  src/test/java/dev/andre/homecontrol/sources/tmdb/FakeTmdbServer.java \
  src/test/java/dev/andre/homecontrol/sources/youtube/FakeGoogleServer.java \
  src/test/java/dev/andre/homecontrol/sources/workflows/FakeWorkflowServer.java \
  src/test/java/dev/andre/homecontrol/sources/http/TricklingServer.java \
  src/test/java/dev/andre/homecontrol/sources/youtube/MutableClock.java \
  src/test/java/dev/andre/homecontrol/sources/tmdb/MutableClock.java \
  src/test/java/dev/andre/homecontrol/sources/sports/thesportsdb/MutableClock.java \
  src/test/java/dev/andre/homecontrol/sources/sports/calendar/MutableClock.java \
  src/test/java/dev/andre/homecontrol/web/EventStreamReader.java | tail -1
scripts/gradle.sh test
cat build/test-results/test/*.xml | grep -c '<testcase '
```

Expected:
- `1281 total` (when this plan was written);
- a green run;
- the test count, which was 2,698 when this plan was written.

Write both numbers into the pull request description later. The inline copies also go away, but they are not in this count: the clock nested in `SsdpDiscoveryTest`, `RailCacheTest.TestClock`, and the certificate code in `FakeCastReceiver` and `FakeWebSocketServer`.

- [ ] **Step 2: Write the failing test**

`src/test/java/dev/andre/homecontrol/testsupport/FakeHttpServerTest.java`:

```java
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
import java.util.concurrent.CountDownLatch;

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

        assertThat(server.bytesTrickled()).isGreaterThan(1);
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
        client.sendAsync(HttpRequest.newBuilder(server.url("/trickle")).build(), HttpResponse.BodyHandlers.ofInputStream());
        entered.await();
        await().atMost(Duration.ofSeconds(5)).until(() -> server.openTrickles() == 1);

        server.close();

        assertThat(server.openTrickles()).isZero();
        assertThatThrownBy(() -> get("/block")).isInstanceOf(IOException.class);
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.testsupport.FakeHttpServerTest'`

Expected: FAIL at `compileTestJava`: `cannot find symbol: class FakeHttpServer`, `class Request` and `class Response`.

- [ ] **Step 4: Write `Request` and `Response`**

`src/test/java/dev/andre/homecontrol/testsupport/Request.java`:

```java
package dev.andre.homecontrol.testsupport;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * A request {@link FakeHttpServer} received. Header names are lower case; a header sent more than once is joined
 * with commas.
 */
public record Request(String method, URI uri, Map<String, String> query, Map<String, String> headers, String body) {

    /** The path as the client sent it, not decoded. */
    public String path() {
        return uri.getRawPath();
    }

    /** The query as the client sent it, or an empty string without one. */
    public String rawQuery() {
        return uri.getRawQuery() == null ? "" : uri.getRawQuery();
    }

    public String header(String name) {
        return headers.get(name.toLowerCase(Locale.ROOT));
    }

    /** Decodes {@code a=1&b=x%20y}. A name given more than once keeps its last value. */
    public static Map<String, String> decode(String rawQuery) {
        Map<String, String> values = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return values;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            values.put(key, value);
        }
        return values;
    }

    static Request of(HttpExchange exchange) throws IOException {
        Map<String, String> headers = new TreeMap<>();
        exchange.getRequestHeaders().forEach((name, values) ->
                headers.put(name.toLowerCase(Locale.ROOT), String.join(",", values)));
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        URI uri = exchange.getRequestURI();
        return new Request(exchange.getRequestMethod(), uri, decode(uri.getRawQuery()), headers, body);
    }
}
```

`src/test/java/dev/andre/homecontrol/testsupport/Response.java`:

```java
package dev.andre.homecontrol.testsupport;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A canned answer of {@link FakeHttpServer}. An empty body is sent as no body at all; a null content type sends no
 * Content-Type header.
 */
public record Response(int status, String contentType, Map<String, String> headers, byte[] body, Duration delay) {

    public static final String JSON = "application/json; charset=utf-8";

    public static Response of(int status, String contentType, byte[] body) {
        return new Response(status, contentType, Map.of(), body, Duration.ZERO);
    }

    public static Response of(int status, String contentType, String body) {
        return of(status, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    public static Response json(int status, String json) {
        return of(status, JSON, json);
    }

    /** A status alone: no Content-Type header and no body. */
    public static Response empty(int status) {
        return of(status, null, new byte[0]);
    }

    public Response withHeader(String name, String value) {
        Map<String, String> more = new LinkedHashMap<>(headers);
        more.put(name, value);
        return new Response(status, contentType, Map.copyOf(more), body, delay);
    }

    /** Waits this long before answering, on top of any delay the whole server has. */
    public Response withDelay(Duration wait) {
        return new Response(status, contentType, headers, body, wait);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Response that && status == that.status && Objects.equals(contentType, that.contentType)
                && headers.equals(that.headers) && Arrays.equals(body, that.body) && delay.equals(that.delay);
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, contentType, headers, Arrays.hashCode(body), delay);
    }

    @Override
    public String toString() {
        return "Response[" + status + " " + contentType + ", " + body.length + " bytes, headers " + headers
                + ", delay " + delay + "]";
    }
}
```

(`equals`, `hashCode` and `toString` are written out because the record holds an array. SonarCloud reports `java:S6218` otherwise, and this project treats that rule as a bug.)

- [ ] **Step 5: Write `FakeHttpServer`**

`src/test/java/dev/andre/homecontrol/testsupport/FakeHttpServer.java`:

```java
package dev.andre.homecontrol.testsupport;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * An in-process HTTP or HTTPS server on 127.0.0.1 for tests.
 *
 * <p>A route matches on method, path and a predicate over the request; the newest matching route answers, so a test
 * can override a default. A path ending in {@code /**} matches every path that starts with what comes before the
 * {@code **}. Every request is recorded before it is answered. A request no route matches gets the fallback, which is
 * 404 without a body unless changed.
 */
public final class FakeHttpServer implements AutoCloseable {

    /** Matches every method, wherever a method is asked for. */
    public static final String ANY_METHOD = "*";

    private static final Function<Request, Response> NOT_FOUND = request -> Response.empty(404);

    private record Route(String method, String path, Predicate<Request> when, HttpHandler handler) {
        boolean matches(Request request) {
            return methodMatches(method, request.method()) && pathMatches(path, request.path()) && when.test(request);
        }
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final List<Route> routes = new CopyOnWriteArrayList<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger bytesTrickled = new AtomicInteger();
    private final AtomicInteger openTrickles = new AtomicInteger();
    private volatile Function<Request, Response> fallback = NOT_FOUND;
    private volatile Duration delay = Duration.ZERO;
    private volatile boolean closed;

    private FakeHttpServer(HttpServer server) {
        this.server = server;
        server.createContext("/", this::dispatch);
        server.setExecutor(executor);
        server.start();
    }

    /** A plain HTTP server on a free port. */
    public static FakeHttpServer start() throws IOException {
        return new FakeHttpServer(HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0));
    }

    /** An HTTPS server on a free port, presenting the certificate of {@code tls} (see {@link TestTls}). */
    public static FakeHttpServer start(SSLContext tls) throws IOException {
        HttpsServer https = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        https.setHttpsConfigurator(new HttpsConfigurator(tls));
        return new FakeHttpServer(https);
    }

    public URI url() {
        String scheme = server instanceof HttpsServer ? "https" : "http";
        return URI.create(scheme + "://127.0.0.1:" + server.getAddress().getPort());
    }

    public URI url(String path) {
        return URI.create(url() + path);
    }

    /** Answers {@code method path} with {@code answers} in order; the last one repeats. */
    public FakeHttpServer respond(String method, String path, Response... answers) {
        return respond(method, path, request -> true, answers);
    }

    /** Like {@link #respond(String, String, Response...)}, for requests {@code when} accepts. */
    public FakeHttpServer respond(String method, String path, Predicate<Request> when, Response... answers) {
        if (answers.length == 0) {
            throw new IllegalArgumentException("A route needs at least one answer");
        }
        Deque<Response> queue = new ArrayDeque<>(List.of(answers));
        return route(method, path, when, exchange -> {
            Response next;
            synchronized (queue) {
                next = queue.size() > 1 ? queue.pollFirst() : queue.peekFirst();
            }
            write(exchange, next);
        });
    }

    /** Lets {@code handler} answer {@code method path}. The server closes the exchange afterwards. */
    public FakeHttpServer handle(String method, String path, HttpHandler handler) {
        return route(method, path, request -> true, handler);
    }

    /** Answers 200 with a JSON body that never ends: one space every 50 ms until the client or the server closes. */
    public FakeHttpServer trickle(String method, String path) {
        return handle(method, path, this::trickle);
    }

    /** What a request no route matches gets, instead of 404 without a body. */
    public FakeHttpServer fallback(Response response) {
        return fallback(request -> response);
    }

    public FakeHttpServer fallback(Function<Request, Response> answer) {
        this.fallback = answer;
        return this;
    }

    /** Every request, whether a route matches or not, waits this long before it is answered. */
    public FakeHttpServer delay(Duration wait) {
        this.delay = wait;
        return this;
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    public List<Request> requests(String method, String path) {
        return requests.stream()
                .filter(request -> methodMatches(method, request.method()) && request.path().equals(path))
                .toList();
    }

    public int count(String method, String path) {
        return requests(method, path).size();
    }

    public Request last(String method, String path) {
        List<Request> matching = requests(method, path);
        if (matching.isEmpty()) {
            throw new AssertionError("No " + method + " " + path + " received; got " + requests);
        }
        return matching.getLast();
    }

    /** Bytes the trickling routes have written; more than one proves a client read past the headers. */
    public int bytesTrickled() {
        return bytesTrickled.get();
    }

    /** Trickling responses still being written; zero once every such client has closed its connection. */
    public int openTrickles() {
        return openTrickles.get();
    }

    /** Forgets every route, request and delay, and restores the 404 fallback. */
    public void reset() {
        routes.clear();
        requests.clear();
        delay = Duration.ZERO;
        fallback = NOT_FOUND;
    }

    /** Stops listening and ends every handler still running, blocked or trickling. Calling it again does nothing. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        server.stop(0);
        executor.shutdownNow();
        executor.close();
    }

    private FakeHttpServer route(String method, String path, Predicate<Request> when, HttpHandler handler) {
        routes.addFirst(new Route(method, path, when, handler));
        return this;
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        try (exchange) {
            Request request = Request.of(exchange);
            requests.add(request);
            pause(delay);
            HttpHandler handler = routes.stream()
                    .filter(route -> route.matches(request))
                    .findFirst()
                    .map(Route::handler)
                    .orElse(unknown -> write(unknown, fallback.apply(request)));
            handler.handle(exchange);
        }
    }

    private static void write(HttpExchange exchange, Response response) throws IOException {
        pause(response.delay());
        if (response.contentType() != null) {
            exchange.getResponseHeaders().set("Content-Type", response.contentType());
        }
        response.headers().forEach(exchange.getResponseHeaders()::set);
        if (response.body().length == 0) {
            exchange.sendResponseHeaders(response.status(), -1);
            return;
        }
        exchange.sendResponseHeaders(response.status(), response.body().length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(response.body());
        }
    }

    // A peer that is slow on purpose is exactly what the deadline tests exercise.
    @SuppressWarnings("java:S2925")
    private void trickle(HttpExchange exchange) {
        openTrickles.incrementAndGet();
        try {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            OutputStream body = exchange.getResponseBody();
            while (!closed) {
                body.write(' ');
                body.flush();
                bytesTrickled.incrementAndGet();
                Thread.sleep(50);
            }
        } catch (IOException _) {
            // The client closed the connection: what a deadline is meant to do.
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        } finally {
            openTrickles.decrementAndGet();
        }
    }

    // A slow upstream is what the timeout tests exercise, so answering late on purpose is the point.
    @SuppressWarnings("java:S2925")
    private static void pause(Duration wait) {
        if (wait.isZero()) {
            return;
        }
        try {
            Thread.sleep(wait);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean methodMatches(String wanted, String method) {
        return wanted.equals(ANY_METHOD) || wanted.equals(method);
    }

    private static boolean pathMatches(String wanted, String path) {
        return wanted.endsWith("/**") ? path.startsWith(wanted.substring(0, wanted.length() - 2)) : wanted.equals(path);
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.testsupport.FakeHttpServerTest'`

Expected: PASS, 17 tests. Check with `grep -o 'tests="[0-9]*" skipped="0" failures="0" errors="0"' build/test-results/test/TEST-dev.andre.homecontrol.testsupport.FakeHttpServerTest.xml`.

- [ ] **Step 7: Commit the server**

```bash
git add src/test/java/dev/andre/homecontrol/testsupport
git commit -m "test: add FakeHttpServer, one in-process HTTP fake for every web API

Routes by method, path and a predicate over the request, answers in
order, records every request, and offers delays, a trickling body, a
fallback, reset() and close()."
```

End the message with your `Co-Authored-By:` trailer.

- [ ] **Step 8: Replace `TricklingServer`**

Run the two trickling tests first and note the count:

`scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.http.*'`, then `cat build/test-results/test/TEST-dev.andre.homecontrol.sources.http.*.xml | grep -c '<testcase '`.

In `src/test/java/dev/andre/homecontrol/sources/http/SlowBodyDeadlineTest.java`:
- the field `private TricklingServer server;` becomes `private FakeHttpServer server;`;
- `server = new TricklingServer();` becomes `server = FakeHttpServer.start().trickle(FakeHttpServer.ANY_METHOD, "/**");`;
- every `server.bytesSent()` becomes `server.bytesTrickled()`;
- add `import dev.andre.homecontrol.testsupport.FakeHttpServer;`.

`server.url(...)` stays as it is.

In `src/test/java/dev/andre/homecontrol/sources/http/BoundedBodyTest.java`:
- `try (TricklingServer trickling = new TricklingServer()) {` becomes `try (FakeHttpServer trickling = FakeHttpServer.start().trickle(FakeHttpServer.ANY_METHOD, "/**")) {`;
- `trickling.bytesSent()` becomes `trickling.bytesTrickled()`;
- `trickling.openStreams()` becomes `trickling.openTrickles()`;
- add the same import.

Then delete the old server:

```bash
git rm src/test/java/dev/andre/homecontrol/sources/http/TricklingServer.java
git grep -n 'TricklingServer' -- src
```

Expected: `git grep` prints nothing.

- [ ] **Step 9: Run the trickling tests**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.http.*'`

Expected: PASS, with the same count as at the start of Step 8.

- [ ] **Step 10: Commit**

```bash
git add src/test/java/dev/andre/homecontrol/sources/http/SlowBodyDeadlineTest.java src/test/java/dev/andre/homecontrol/sources/http/BoundedBodyTest.java
git commit -m "test: trickle from FakeHttpServer instead of TricklingServer

TricklingServer's one behaviour, a body that never ends on every path,
is FakeHttpServer.trickle(ANY_METHOD, \"/**\")."
```

End the message with your `Co-Authored-By:` trailer.

### Task 2: `FakeJellyfinServer` over `FakeHttpServer`

**Files:**
- Modify (rewrite): `src/test/java/dev/andre/homecontrol/sources/jellyfin/FakeJellyfinServer.java`

**Interfaces:**
- Consumes: `FakeHttpServer`, `Request`, `Response` (Task 1).
- Produces: `FakeJellyfinServer` with the same public members as before:
  - `SERVER_ID`, `USER_ID`, `ACCESS_TOKEN`, and the record `Recorded(method, path, query, headers, body)` with `header(String)`;
  - `url()`, `withConnectableServer()`, `respond(...)`, `respondJson(...)`, `respondBytes(...)`;
  - `requests(String, String)`, `last(String, String)`, `requests()`, `static fixture(String)` and `close()`.

- [ ] **Step 1: Run the fake's tests before the change**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.jellyfin.*' --tests 'dev.andre.homecontrol.web.*Jellyfin*'`

Expected: PASS. Note the count from `cat build/test-results/test/TEST-dev.andre.homecontrol.sources.jellyfin.*.xml build/test-results/test/TEST-dev.andre.homecontrol.web.*Jellyfin*.xml | grep -c '<testcase '`.

- [ ] **Step 2: Rewrite the fake as a wrapper**

Replace the whole file with:

```java
package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.testsupport.FakeHttpServer;
import dev.andre.homecontrol.testsupport.Request;
import dev.andre.homecontrol.testsupport.Response;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** In-process Jellyfin: canned responses keyed by "METHOD /path", every request recorded. Unknown routes → 404. */
public final class FakeJellyfinServer implements AutoCloseable {

    public static final String SERVER_ID = "4e1a2b3c4d5e4f60718293a4b5c6d7e8";
    public static final String USER_ID = "a1b2c3d4e5f60718293a4b5c6d7e8f90";
    public static final String ACCESS_TOKEN = "6c1f0e5a9b8d4c7e8f2a3b4c5d6e7f80";

    public record Recorded(String method, String path, Map<String, String> query, Map<String, String> headers, String body) {
        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    private final FakeHttpServer server;

    public FakeJellyfinServer() throws IOException {
        server = FakeHttpServer.start();
    }

    public URI url() {
        return server.url();
    }

    /** System info, AuthenticateByName and the user — enough to connect in password mode. */
    public FakeJellyfinServer withConnectableServer() {
        return respond("GET", "/System/Info/Public", 200, "system-info-public.json")
                .respond("POST", "/Users/AuthenticateByName", 200, "authenticate-by-name.json")
                .respond("GET", "/Users/" + USER_ID, 200, "user.json")
                .respondJson("POST", "/Sessions/Logout", 204, null);
    }

    public FakeJellyfinServer respond(String method, String path, int status, String fixture) {
        return respondBytes(method, path, status, Response.JSON,
                fixture == null ? new byte[0] : fixture(fixture).getBytes(StandardCharsets.UTF_8));
    }

    public FakeJellyfinServer respondJson(String method, String path, int status, String json) {
        return respondBytes(method, path, status, Response.JSON,
                json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8));
    }

    /** A redirect status also sends a Location off the server, which the client must not follow. */
    public FakeJellyfinServer respondBytes(String method, String path, int status, String contentType, byte[] body) {
        Response response = Response.of(status, contentType, body);
        server.respond(method, path, status >= 300 && status < 400
                ? response.withHeader("Location", "http://elsewhere.invalid/") : response);
        return this;
    }

    public List<Recorded> requests(String method, String path) {
        return server.requests(method, path).stream().map(FakeJellyfinServer::recorded).toList();
    }

    public Recorded last(String method, String path) {
        List<Recorded> matching = requests(method, path);
        if (matching.isEmpty()) {
            throw new AssertionError("No " + method + " " + path + " received; got " + requests());
        }
        return matching.getLast();
    }

    public List<Recorded> requests() {
        return server.requests().stream().map(FakeJellyfinServer::recorded).toList();
    }

    public static String fixture(String name) {
        try (InputStream in = FakeJellyfinServer.class.getResourceAsStream("/fixtures/jellyfin/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("No fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Recorded recorded(Request request) {
        return new Recorded(request.method(), request.path(), request.query(), request.headers(), request.body());
    }

    @Override
    public void close() {
        server.close();
    }
}
```

(`Response.JSON` is the same string the old fake used: `application/json; charset=utf-8`.)

- [ ] **Step 3: Run the fake's tests after the change**

Run the command from Step 1.

Expected: PASS, with the same count as in Step 1.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/dev/andre/homecontrol/sources/jellyfin/FakeJellyfinServer.java
git commit -m "test: build FakeJellyfinServer on FakeHttpServer

Same routes, fixtures, recorded requests and redirect Location as
before; the socket, parsing and recording now live in FakeHttpServer."
```

End the message with your `Co-Authored-By:` trailer.

### Task 3: `FakeCalendarServer` over `FakeHttpServer`

**Files:**
- Modify (rewrite): `src/test/java/dev/andre/homecontrol/sources/sports/calendar/FakeCalendarServer.java`

**Interfaces:**
- Consumes: `FakeHttpServer`, `Response` (Task 1).
- Produces: the same public members as before:
  - the record `Recorded(method, path, query, headers)` with `header(String)`;
  - `url(String)`, `respond(String, int, String, String)`, `respondFixture(String, String)` and `respondBytes(String, int, String, byte[])`;
  - `redirect(String, int, String)`, `delay(Duration)`, `requests(String)`, `count(String)` and `close()`.

- [ ] **Step 1: Run the fake's tests before the change**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.sports.calendar.*' --tests 'dev.andre.homecontrol.web.SportsEndToEndTest'`

Expected: PASS. Note the count from `cat build/test-results/test/TEST-dev.andre.homecontrol.sources.sports.calendar.*.xml build/test-results/test/TEST-dev.andre.homecontrol.web.SportsEndToEndTest.xml | grep -c '<testcase '`.

- [ ] **Step 2: Rewrite the fake as a wrapper**

Replace the whole file with:

```java
package dev.andre.homecontrol.sources.sports.calendar;

import dev.andre.homecontrol.testsupport.FakeHttpServer;
import dev.andre.homecontrol.testsupport.Response;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static dev.andre.homecontrol.testsupport.FakeHttpServer.ANY_METHOD;

/** In-process calendar server: canned responses keyed by path, every request recorded. Same design as FakeTmdbServer. */
public final class FakeCalendarServer implements AutoCloseable {

    public record Recorded(String method, String path, Map<String, String> query, Map<String, String> headers) {
        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    private final FakeHttpServer server;

    public FakeCalendarServer() throws IOException {
        server = FakeHttpServer.start().fallback(Response.of(404, "text/plain", "not found"));
    }

    public URI url(String path) {
        return server.url(path);
    }

    public FakeCalendarServer respond(String path, int status, String contentType, String body) {
        return respondBytes(path, status, contentType, body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8));
    }

    public FakeCalendarServer respondFixture(String path, String fixtureName) {
        try (InputStream in = FakeCalendarServer.class.getResourceAsStream("/fixtures/ics/" + fixtureName)) {
            if (in == null) {
                throw new IllegalArgumentException("No fixture " + fixtureName);
            }
            return respondBytes(path, 200, "text/calendar; charset=utf-8", in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public FakeCalendarServer respondBytes(String path, int status, String contentType, byte[] body) {
        server.respond(ANY_METHOD, path, Response.of(status, contentType, body));
        return this;
    }

    public FakeCalendarServer redirect(String path, int status, String location) {
        server.respond(ANY_METHOD, path, Response.of(status, "text/plain", new byte[0]).withHeader("Location", location));
        return this;
    }

    public FakeCalendarServer delay(Duration duration) {
        server.delay(duration);
        return this;
    }

    public List<Recorded> requests(String path) {
        return server.requests(ANY_METHOD, path).stream()
                .map(request -> new Recorded(request.method(), request.path(), request.query(), request.headers()))
                .toList();
    }

    public int count(String path) {
        return server.count(ANY_METHOD, path);
    }

    @Override
    public void close() {
        server.close();
    }
}
```

- [ ] **Step 3: Run the fake's tests after the change**

Run the command from Step 1.

Expected: PASS, with the same count as in Step 1.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/dev/andre/homecontrol/sources/sports/calendar/FakeCalendarServer.java
git commit -m "test: build FakeCalendarServer on FakeHttpServer

Same routes by path, redirects, delay, recorded requests and plain-text
404 as before."
```

End the message with your `Co-Authored-By:` trailer.

### Task 4: `FakeTheSportsDbServer` over `FakeHttpServer`

**Files:**
- Modify (rewrite): `src/test/java/dev/andre/homecontrol/sources/sports/thesportsdb/FakeTheSportsDbServer.java`

**Interfaces:**
- Consumes: `FakeHttpServer`, `Request`, `Response` (Task 1).
- Produces: the same public members as before:
  - `FREE_KEY`, `PERSONAL_KEY`, and the record `Recorded(key, endpoint, query, headers)` with `header(String)`;
  - `apiBase()`, `withStandardResponses()`, `respond(String, Map, int, String)` and `respondJson(String, Map, int, String)`;
  - `delay(Duration)`, `requests(String)`, `count(String)`, `last(String)` and `close()`.

- [ ] **Step 1: Run the fake's tests before the change**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.sports.thesportsdb.*' --tests 'dev.andre.homecontrol.web.SportsEndToEndTest'`

Expected: PASS. Note the count from `cat build/test-results/test/TEST-dev.andre.homecontrol.sources.sports.thesportsdb.*.xml build/test-results/test/TEST-dev.andre.homecontrol.web.SportsEndToEndTest.xml | grep -c '<testcase '`.

- [ ] **Step 2: Rewrite the fake as a wrapper**

The API key is a path segment (`/api/v1/json/{key}/{endpoint}`):
- A route is registered for each of the two valid keys, so any other key finds no route.
- The fallback then answers 400 with TheSportsDB's invalid-key body, as the old fake did before looking at routes.
- A valid key with an unknown endpoint gets 404 with `{}`.
- The per-endpoint defaults are registered first, so any route a test adds is newer and wins, which is how the old fake preferred an exact query over the default.

Replace the whole file with:

```java
package dev.andre.homecontrol.sources.sports.thesportsdb;

import dev.andre.homecontrol.testsupport.FakeHttpServer;
import dev.andre.homecontrol.testsupport.Request;
import dev.andre.homecontrol.testsupport.Response;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static dev.andre.homecontrol.testsupport.FakeHttpServer.ANY_METHOD;

/** In-process TheSportsDB: canned responses keyed by endpoint + query, every request recorded. Same design as FakeTmdbServer. */
public final class FakeTheSportsDbServer implements AutoCloseable {

    public static final String FREE_KEY = "123";
    public static final String PERSONAL_KEY = "9876543210";

    private static final String PREFIX = "/api/v1/json/";
    private static final List<String> KEYS = List.of(FREE_KEY, PERSONAL_KEY);

    public record Recorded(String key, String endpoint, Map<String, String> query, Map<String, String> headers) {
        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    private final FakeHttpServer server;

    public FakeTheSportsDbServer() throws IOException {
        server = FakeHttpServer.start().fallback(request -> KEYS.contains(keyAndEndpoint(request.path())[0])
                ? json(404, "{}".getBytes(StandardCharsets.UTF_8))
                : json(400, fixtureBytes("invalid-key.json")));
        byDefault("lookupleague.php", fixtureBytes("lookupleague-unknown.json"));
        byDefault("eventsday.php", fixtureBytes("eventsday-empty.json"));
    }

    public URI apiBase() {
        return server.url("/api/v1/json");
    }

    public FakeTheSportsDbServer withStandardResponses() {
        respond("lookupleague.php", Map.of("id", "4331"), 200, "lookupleague-4331.json");
        respond("lookupleague.php", Map.of("id", "4328"), 200, "lookupleague-4328.json");
        respond("search_all_leagues.php", Map.of("c", "Germany", "s", "Soccer"), 200, "search_all_leagues-germany-soccer.json");
        respond("eventsday.php", Map.of("d", "2026-09-18", "l", "4331"), 200, "eventsday-2026-09-18-4331.json");
        respond("eventsday.php", Map.of("d", "2026-09-19", "l", "4331"), 200, "eventsday-2026-09-19-4331.json");
        respond("eventsday.php", Map.of("d", "2026-09-19", "l", "4328"), 200, "eventsday-2026-09-19-4328.json");
        return this;
    }

    public FakeTheSportsDbServer respond(String endpoint, Map<String, String> query, int status, String fixture) {
        return route(endpoint, query, json(status, fixtureBytes(fixture)));
    }

    public FakeTheSportsDbServer respondJson(String endpoint, Map<String, String> query, int status, String json) {
        return route(endpoint, query, json(status, json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8)));
    }

    public FakeTheSportsDbServer delay(Duration duration) {
        server.delay(duration);
        return this;
    }

    public List<Recorded> requests(String endpoint) {
        return server.requests().stream()
                .map(FakeTheSportsDbServer::recorded)
                .filter(recorded -> recorded.endpoint().equals(endpoint))
                .toList();
    }

    public int count(String endpoint) {
        return requests(endpoint).size();
    }

    public Recorded last(String endpoint) {
        List<Recorded> matching = requests(endpoint);
        if (matching.isEmpty()) {
            throw new AssertionError("No " + endpoint + " request received");
        }
        return matching.getLast();
    }

    private FakeTheSportsDbServer route(String endpoint, Map<String, String> query, Response response) {
        Map<String, String> expected = Map.copyOf(query);
        for (String key : KEYS) {
            server.respond(ANY_METHOD, PREFIX + key + "/" + endpoint, request -> request.query().equals(expected), response);
        }
        return this;
    }

    private void byDefault(String endpoint, byte[] body) {
        for (String key : KEYS) {
            server.respond(ANY_METHOD, PREFIX + key + "/" + endpoint, json(200, body));
        }
    }

    private static Response json(int status, byte[] body) {
        return Response.of(status, Response.JSON, body);
    }

    /** {key, endpoint} of a path under /api/v1/json/; empty strings for anything else. */
    private static String[] keyAndEndpoint(String path) {
        String remainder = path.startsWith(PREFIX) ? path.substring(PREFIX.length()) : "";
        int slash = remainder.indexOf('/');
        return slash < 0
                ? new String[]{remainder, ""}
                : new String[]{remainder.substring(0, slash), remainder.substring(slash + 1)};
    }

    private static Recorded recorded(Request request) {
        String[] keyAndEndpoint = keyAndEndpoint(request.path());
        return new Recorded(keyAndEndpoint[0], keyAndEndpoint[1], request.query(), request.headers());
    }

    private static byte[] fixtureBytes(String name) {
        try (InputStream in = FakeTheSportsDbServer.class.getResourceAsStream("/fixtures/thesportsdb/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("No fixture " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        server.close();
    }
}
```

- [ ] **Step 3: Run the fake's tests after the change**

Run the command from Step 1.

Expected: PASS, with the same count as in Step 1. `TheSportsDbClientTest` covers the invalid-key 400 and the unknown-league default.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/dev/andre/homecontrol/sources/sports/thesportsdb/FakeTheSportsDbServer.java
git commit -m "test: build FakeTheSportsDbServer on FakeHttpServer

Routes per valid API key and exact query over per-endpoint defaults; an
unknown key still gets 400 with the invalid-key body, an unknown
endpoint 404."
```

End the message with your `Co-Authored-By:` trailer.

### Task 5: `FakeTmdbServer` over `FakeHttpServer`

**Files:**
- Modify (rewrite): `src/test/java/dev/andre/homecontrol/sources/tmdb/FakeTmdbServer.java`

**Interfaces:**
- Consumes: `FakeHttpServer`, `Request`, `Response` (Task 1).
- Produces: the same public members as before:
  - `READ_TOKEN`, `API_KEY`, and the record `Recorded(method, path, query, headers, rawQuery)` with `header(String)`;
  - `url()`, `apiBase()`, `withStandardResponses()`, `respond(...)`, `respondJson(...)`, `respondBytes(...)`;
  - `delay(Duration)` and `redirect(String, String)`;
  - `requests(String, String)`, `last(String, String)`, `count(String, String)`, `requests()`, `static fixture(String)` and `close()`.

- [ ] **Step 1: Run the fake's tests before the change**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.tmdb.*' --tests 'dev.andre.homecontrol.web.StreamingLaunchersEndToEndTest'`

Expected: PASS. Note the count from `cat build/test-results/test/TEST-dev.andre.homecontrol.sources.tmdb.*.xml build/test-results/test/TEST-dev.andre.homecontrol.web.StreamingLaunchersEndToEndTest.xml | grep -c '<testcase '`.

- [ ] **Step 2: Rewrite the fake as a wrapper**

Replace the whole file with:

```java
package dev.andre.homecontrol.sources.tmdb;

import dev.andre.homecontrol.testsupport.FakeHttpServer;
import dev.andre.homecontrol.testsupport.Request;
import dev.andre.homecontrol.testsupport.Response;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** In-process TMDB: canned responses keyed by "METHOD /path", every request recorded. Unknown routes → 404. */
public final class FakeTmdbServer implements AutoCloseable {

    public static final String READ_TOKEN =
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJob21lLWNvbnRyb2wtdGVzdCJ9.c2lnbmF0dXJlLW9mLXRoZS10ZXN0LXRva2Vu";
    public static final String API_KEY = "0123456789abcdef0123456789abcdef";

    public record Recorded(String method, String path, Map<String, String> query, Map<String, String> headers, String rawQuery) {
        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    private final FakeHttpServer server;

    public FakeTmdbServer() throws IOException {
        server = FakeHttpServer.start().fallback(Response.of(404, Response.JSON, fixture("not-found.json")));
    }

    public URI url() {
        return server.url();
    }

    public URI apiBase() {
        return URI.create(url() + "/3");
    }

    public FakeTmdbServer withStandardResponses() {
        respond("GET", "/3/authentication", 200, "authentication.json");
        respond("GET", "/3/configuration", 200, "configuration.json");
        respond("GET", "/3/search/multi", 200, "search-multi.json");
        respond("GET", "/3/trending/all/week", 200, "trending-all-week.json");
        respond("GET", "/3/movie/603", 200, "details-movie-603.json");
        respond("GET", "/3/tv/66732", 200, "details-tv-66732.json");
        respond("GET", "/3/movie/603/watch/providers", 200, "providers-movie-603.json");
        respond("GET", "/3/movie/550/watch/providers", 200, "providers-movie-550.json");
        respond("GET", "/3/tv/66732/watch/providers", 200, "providers-tv-66732.json");
        respond("GET", "/3/tv/76479/watch/providers", 200, "providers-tv-76479.json");
        respond("GET", "/3/tv/94997/watch/providers", 200, "providers-tv-94997.json");
        return this;
    }

    public FakeTmdbServer respond(String method, String path, int status, String fixture) {
        return respondBytes(method, path, status, Response.JSON,
                fixture == null ? new byte[0] : fixture(fixture).getBytes(StandardCharsets.UTF_8));
    }

    public FakeTmdbServer respondJson(String method, String path, int status, String json) {
        return respondBytes(method, path, status, Response.JSON,
                json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8));
    }

    public FakeTmdbServer respondBytes(String method, String path, int status, String contentType, byte[] body) {
        server.respond(method, path, Response.of(status, contentType, body));
        return this;
    }

    /** Every response (until changed) sleeps this long before answering — for timeout tests. */
    public FakeTmdbServer delay(Duration duration) {
        server.delay(duration);
        return this;
    }

    public FakeTmdbServer redirect(String path, String location) {
        server.respond("GET", path, Response.of(302, "text/plain", new byte[0]).withHeader("Location", location));
        return this;
    }

    public List<Recorded> requests(String method, String path) {
        return server.requests(method, path).stream().map(FakeTmdbServer::recorded).toList();
    }

    public Recorded last(String method, String path) {
        List<Recorded> matching = requests(method, path);
        if (matching.isEmpty()) {
            throw new AssertionError("No " + method + " " + path + " received; got " + requests());
        }
        return matching.getLast();
    }

    public int count(String method, String path) {
        return server.count(method, path);
    }

    public List<Recorded> requests() {
        return server.requests().stream().map(FakeTmdbServer::recorded).toList();
    }

    public static String fixture(String name) {
        try (InputStream in = FakeTmdbServer.class.getResourceAsStream("/fixtures/tmdb/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("No fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Recorded recorded(Request request) {
        return new Recorded(request.method(), request.path(), request.query(), request.headers(), request.rawQuery());
    }

    @Override
    public void close() {
        server.close();
    }
}
```

- [ ] **Step 3: Run the fake's tests after the change**

Run the command from Step 1.

Expected: PASS, with the same count as in Step 1.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/dev/andre/homecontrol/sources/tmdb/FakeTmdbServer.java
git commit -m "test: build FakeTmdbServer on FakeHttpServer

Same routes, fixtures, redirect, delay, recorded requests and JSON 404
as before."
```

End the message with your `Co-Authored-By:` trailer.

### Task 6: `FakeGoogleServer` over `FakeHttpServer`

**Files:**
- Modify (rewrite): `src/test/java/dev/andre/homecontrol/sources/youtube/FakeGoogleServer.java`

**Interfaces:**
- Consumes: `FakeHttpServer`, `Request`, `Response` (Task 1).
- Produces: the same public members as before:
  - the record `Recorded(method, path, query, form, headers, body, rawQuery)` with `header(String)`;
  - the record `Canned(status, contentType, body)` with `json(int, String)` and `fixture(int, String)`;
  - `base()`, `properties()`, `static fixture(String)`, `respond(String, String, Canned...)` and `respondWhen(String, String, Predicate<Recorded>, Canned...)`;
  - `requests()`, `requests(String)` and `count(String)`;
  - `oauthApproves()`, `youtubeLibrary()`, `playlist(String, String)`, `loungeAccepts()`, `thumbnails()`, `static decode(String)` and `close()`.

- [ ] **Step 1: Run the fake's tests before the change**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.youtube.*' --tests 'dev.andre.homecontrol.web.YouTube*'`

Expected: PASS. Note the count from `cat build/test-results/test/TEST-dev.andre.homecontrol.sources.youtube.*.xml build/test-results/test/TEST-dev.andre.homecontrol.web.YouTube*.xml | grep -c '<testcase '`.

- [ ] **Step 2: Rewrite the fake as a wrapper**

Replace the whole file with:

```java
package dev.andre.homecontrol.sources.youtube;

import dev.andre.homecontrol.testsupport.FakeHttpServer;
import dev.andre.homecontrol.testsupport.Request;
import dev.andre.homecontrol.testsupport.Response;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/** Google OAuth, YouTube Data API v3, Lounge and thumbnails in one in-process fake. */
public final class FakeGoogleServer implements AutoCloseable {

    public record Recorded(String method, String path, Map<String, String> query, Map<String, String> form,
                           Map<String, String> headers, String body, String rawQuery) {
        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    public record Canned(int status, String contentType, byte[] body) {
        public static Canned json(int status, String json) {
            return new Canned(status, "application/json; charset=UTF-8", json.getBytes(StandardCharsets.UTF_8));
        }

        public static Canned fixture(int status, String name) {
            return json(status, FakeGoogleServer.fixture(name));
        }
    }

    private final FakeHttpServer server;

    public FakeGoogleServer() throws IOException {
        server = FakeHttpServer.start().fallback(Response.of(404, "application/json; charset=UTF-8",
                "{\"error\":{\"code\":404,\"message\":\"no fake route\",\"errors\":[]}}"));
    }

    public URI base() {
        return server.url();
    }

    public YouTubeProperties properties() {
        URI base = base();
        return new YouTubeProperties(true, URI.create(base + "/oauth"), URI.create(base + "/youtube/v3"),
                URI.create(base + "/lounge"), URI.create(base + "/thumbs"), 2, 5, 10000, 20, 30, 30, 5,
                Duration.ofHours(24), 20, Duration.ofMinutes(60), Duration.ofMinutes(15), Duration.ofHours(6));
    }

    public static String fixture(String name) {
        try (InputStream in = FakeGoogleServer.class.getResourceAsStream("/fixtures/youtube/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("No fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Answers {@code method path} (path without query) with the given responses in order; the last one
     * repeats. Later rules win over earlier ones, so a test can override a default.
     */
    public FakeGoogleServer respond(String method, String path, Canned... answers) {
        return respondWhen(method, path, request -> true, answers);
    }

    public FakeGoogleServer respondWhen(String method, String path, Predicate<Recorded> when, Canned... answers) {
        server.respond(method, path, request -> when.test(recorded(request)), Arrays.stream(answers)
                .map(answer -> Response.of(answer.status(), answer.contentType(), answer.body()))
                .toArray(Response[]::new));
        return this;
    }

    public List<Recorded> requests() {
        return server.requests().stream().map(FakeGoogleServer::recorded).toList();
    }

    public List<Recorded> requests(String path) {
        return requests().stream().filter(r -> r.path().equals(path)).toList();
    }

    public int count(String path) {
        return requests(path).size();
    }

    /** OAuth defaults: device code, then pending once, then granted; refresh granted; revoke 200. */
    public FakeGoogleServer oauthApproves() {
        respond("POST", "/oauth/device/code", Canned.fixture(200, "oauth-device-code.json"));
        respond("POST", "/oauth/token", Canned.fixture(428, "oauth-token-pending.json"),
                Canned.fixture(200, "oauth-token-granted.json"));
        respondWhen("POST", "/oauth/token", r -> "refresh_token".equals(r.form().get("grant_type")),
                Canned.fixture(200, "oauth-refresh-granted.json"));
        respond("POST", "/oauth/revoke", Canned.json(200, "{}"));
        return this;
    }

    /** Channel, three subscriptions on two pages, their uploads playlists and their newest videos. */
    public FakeGoogleServer youtubeLibrary() {
        respondWhen("GET", "/youtube/v3/channels", r -> "true".equals(r.query().get("mine")),
                Canned.fixture(200, "channels-mine.json"));
        respondWhen("GET", "/youtube/v3/channels", r -> "contentDetails".equals(r.query().get("part")),
                Canned.fixture(200, "channels-uploads.json"));
        respondWhen("GET", "/youtube/v3/subscriptions", r -> !r.query().containsKey("pageToken"),
                Canned.fixture(200, "subscriptions-page-1.json"));
        respondWhen("GET", "/youtube/v3/subscriptions", r -> "CAIQAA".equals(r.query().get("pageToken")),
                Canned.fixture(200, "subscriptions-page-2.json"));
        playlist("UUsXVk37bltHxD1rDPwtNM8Q", "playlist-items-uploads-kurzgesagt.json");
        playlist("UUSMOQeBJ2RAnuFungnQOxLg", "playlist-items-uploads-blender.json");
        playlist("UULA_DiR1FfKNvjuUpBHmylQ", "playlist-items-uploads-nasa.json");
        respond("GET", "/youtube/v3/videos", Canned.fixture(200, "videos-by-id.json"));
        return this;
    }

    public FakeGoogleServer playlist(String playlistId, String fixture) {
        return respondWhen("GET", "/youtube/v3/playlistItems", r -> playlistId.equals(r.query().get("playlistId")),
                Canned.fixture(200, fixture));
    }

    /** Lounge: token for the fixture screen, a bind answer, setPlaylist accepted. */
    public FakeGoogleServer loungeAccepts() {
        respond("POST", "/lounge/pairing/get_lounge_token_batch", Canned.fixture(200, "lounge-token-batch.json"));
        respondWhen("POST", "/lounge/bc/bind", r -> "1".equals(r.query().get("RID")),
                new Canned(200, "text/plain; charset=utf-8", fixture("lounge-bind.txt").getBytes(StandardCharsets.UTF_8)));
        respondWhen("POST", "/lounge/bc/bind", r -> "2".equals(r.query().get("RID")),
                new Canned(200, "text/plain; charset=utf-8", "7\n[[5,[]]\n".getBytes(StandardCharsets.UTF_8)));
        return this;
    }

    /** A 1×1 JPEG-looking body for any thumbnail. */
    public FakeGoogleServer thumbnails() {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0, (byte) 0xFF, (byte) 0xD9};
        return respondWhen("GET", "/thumbs/vi/aqz-KE-bpKQ/mqdefault.jpg", r -> true, new Canned(200, "image/jpeg", jpeg));
    }

    /** The Google APIs join a repeated name's values with commas, so this decode does too (unlike Request.decode). */
    static Map<String, String> decode(String raw) {
        Map<String, String> values = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return values;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            values.merge(key, value, (a, b) -> a + "," + b);
        }
        return values;
    }

    private static Recorded recorded(Request request) {
        String contentType = request.headers().getOrDefault("content-type", "");
        return new Recorded(request.method(), request.uri().getPath(), decode(request.uri().getRawQuery()),
                contentType.startsWith("application/x-www-form-urlencoded") ? decode(request.body()) : Map.of(),
                request.headers(), request.body(), request.uri().getRawQuery());
    }

    @Override
    public void close() {
        server.close();
    }
}
```

- [ ] **Step 3: Run the fake's tests after the change, and compile the browser tests**

Run: the command from Step 1, then `scripts/gradle.sh compileE2eJava`.

Expected:
- PASS, with the same count as in Step 1;
- `YouTubeOAuthE2eTest`, which uses `FakeGoogleServer`, compiles. CI's browser jobs run it.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/dev/andre/homecontrol/sources/youtube/FakeGoogleServer.java
git commit -m "test: build FakeGoogleServer on FakeHttpServer

Same rules, answer sequences, form and query predicates, recorded
requests and JSON 404 as before; later rules still win."
```

End the message with your `Co-Authored-By:` trailer.

### Task 7: `TestTls`, and the Cast and WebSocket fakes on it

**Files:**
- Create: `src/test/java/dev/andre/homecontrol/testsupport/TestTls.java`
- Test: `src/test/java/dev/andre/homecontrol/testsupport/TestTlsTest.java`
- Modify: `src/test/java/dev/andre/homecontrol/adapters/cast/protocol/FakeCastReceiver.java`
- Modify: `src/test/java/dev/andre/homecontrol/adapters/net/FakeWebSocketServer.java`

**Interfaces:**
- Consumes: `FakeHttpServer.start(SSLContext)`, `Response` (Task 1).
- Produces: `TestTls.serverContext(String commonName)`, which returns an `SSLContext` presenting a self-signed RSA certificate `CN=<commonName>`, valid from a day ago to a day ahead. There is one certificate per common name per JVM.

- [ ] **Step 1: Write the failing test**

`src/test/java/dev/andre/homecontrol/testsupport/TestTlsTest.java`:

```java
package dev.andre.homecontrol.testsupport;

import dev.andre.homecontrol.adapters.net.InsecureTls;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLHandshakeException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.cert.X509Certificate;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestTlsTest {

    @Test
    void aDefaultClientRefusesTheCertificate() throws Exception {
        try (FakeHttpServer server = FakeHttpServer.start(TestTls.serverContext("untrusted.invalid"));
             HttpClient client = HttpClient.newHttpClient()) {
            server.respond("GET", "/", Response.json(200, "{}"));

            assertThat(server.url().getScheme()).isEqualTo("https");
            assertThatThrownBy(() -> client.send(HttpRequest.newBuilder(server.url("/")).build(),
                    HttpResponse.BodyHandlers.ofString())).isInstanceOf(SSLHandshakeException.class);
        }
    }

    @Test
    void aTrustingClientIsAnsweredAndSeesTheCommonName() throws Exception {
        X509Certificate certificate = peerCertificate("unit.invalid");

        assertThat(certificate.getSubjectX500Principal().getName()).isEqualTo("CN=unit.invalid");
    }

    @Test
    void aCommonNameGetsOneCertificatePerRun() throws Exception {
        assertThat(peerCertificate("same.invalid")).isEqualTo(peerCertificate("same.invalid"));
        assertThat(peerCertificate("same.invalid")).isNotEqualTo(peerCertificate("other.invalid"));
    }

    private static X509Certificate peerCertificate(String commonName) throws Exception {
        try (FakeHttpServer server = FakeHttpServer.start(TestTls.serverContext(commonName));
             HttpClient client = InsecureTls.httpClient(Duration.ofSeconds(5))) {
            server.respond("GET", "/", Response.json(200, "{}"));
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(server.url("/")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            return (X509Certificate) response.sslSession().orElseThrow().getPeerCertificates()[0];
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.testsupport.TestTlsTest'`

Expected: FAIL at `compileTestJava`: `cannot find symbol: variable TestTls`.

- [ ] **Step 3: Write `TestTls`**

`src/test/java/dev/andre/homecontrol/testsupport/TestTls.java`:

```java
package dev.andre.homecontrol.testsupport;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side TLS for fakes: a self-signed certificate no client trusts by default. Each common name gets one key and
 * certificate per test JVM, because an RSA key takes long enough to generate to show up in a suite's time.
 */
public final class TestTls {

    private static final Map<String, KeyManager[]> KEYS = new ConcurrentHashMap<>();

    private TestTls() {
    }

    /** A context whose server presents {@code CN=<commonName>}, valid from a day ago to a day ahead. */
    public static SSLContext serverContext(String commonName) {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(KEYS.computeIfAbsent(commonName, TestTls::selfSigned), null, new SecureRandom());
            return context;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not build a TLS context for " + commonName, e);
        }
    }

    private static KeyManager[] selfSigned(String commonName) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048, new SecureRandom());
            KeyPair keys = generator.generateKeyPair();
            X500Name subject = new X500Name("CN=" + commonName);
            Instant now = Instant.now();
            Certificate certificate = new JcaX509CertificateConverter().getCertificate(
                    new JcaX509v3CertificateBuilder(subject, new BigInteger(64, new SecureRandom()),
                            Date.from(now.minus(Duration.ofDays(1))), Date.from(now.plus(Duration.ofDays(1))),
                            subject, keys.getPublic())
                            .build(new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate())));
            char[] password = "test".toCharArray();
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            store.setKeyEntry("server", keys.getPrivate(), password, new Certificate[]{certificate});
            KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            factory.init(store, password);
            return factory.getKeyManagers();
        } catch (GeneralSecurityException | OperatorCreationException | IOException e) {
            throw new IllegalStateException("Could not build a certificate for " + commonName, e);
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run the command from Step 2.

Expected: PASS, 3 tests. If the refusal surfaces as another `IOException` subclass than `SSLHandshakeException` on this JDK, assert the class the JDK throws, and record it in your report.

- [ ] **Step 5: Move `FakeCastReceiver` onto `TestTls`**

Run the Cast tests first and note the count:

`scripts/gradle.sh test --tests 'dev.andre.homecontrol.adapters.cast.*' --tests 'dev.andre.homecontrol.web.CastEndToEndTest'`

In `src/test/java/dev/andre/homecontrol/adapters/cast/protocol/FakeCastReceiver.java`, in the constructor `FakeCastReceiver(int port)`, replace:

```java
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(selfSignedKeyManagers(), null, new SecureRandom());
```

with:

```java
        SSLContext context = TestTls.serverContext("fake-cast-receiver");
```

Then:
- Delete the whole method `private static KeyManager[] selfSignedKeyManagers() throws Exception { ... }`.
- Add `import dev.andre.homecontrol.testsupport.TestTls;`.
- Remove every import that has no use left. Check each of these with `grep -n '<SimpleName>' FakeCastReceiver.java`:
  - `X500Name`, `JcaX509CertificateConverter`, `JcaX509v3CertificateBuilder`, `JcaContentSignerBuilder`;
  - `KeyManager`, `KeyManagerFactory`, `BigInteger`, `KeyPair`, `KeyPairGenerator`, `KeyStore`;
  - `SecureRandom`, `Certificate`, `X509Certificate`, `Date`, `Instant`, `Duration`.

Run the Cast command again. Expected: PASS, with the same count.

- [ ] **Step 6: Commit**

```bash
git add src/test/java/dev/andre/homecontrol/testsupport/TestTls.java src/test/java/dev/andre/homecontrol/testsupport/TestTlsTest.java src/test/java/dev/andre/homecontrol/adapters/cast/protocol/FakeCastReceiver.java
git commit -m "test: add TestTls and give the Cast receiver fake its certificate

One self-signed server certificate per common name and test JVM,
instead of each fake building its own."
```

End the message with your `Co-Authored-By:` trailer.

- [ ] **Step 7: Move `FakeWebSocketServer` onto `TestTls`**

Run the WebSocket users first and note the count:

`scripts/gradle.sh test --tests 'dev.andre.homecontrol.adapters.net.*' --tests 'dev.andre.homecontrol.adapters.webos.*' --tests 'dev.andre.homecontrol.adapters.tizen.*' --tests 'dev.andre.homecontrol.web.WebOsEndToEndTest'`

(The webOS timing tests named in the spec's 1.3c can fail under four JVMs on a busy host. If one fails, run its class alone. If it passes alone, note it in your report and go on.)

In `src/test/java/dev/andre/homecontrol/adapters/net/FakeWebSocketServer.java`, replace:

```java
        return new FakeWebSocketServer(tlsContext().getServerSocketFactory(), handler, true);
```

with:

```java
        return new FakeWebSocketServer(TestTls.serverContext("fake-tv.invalid").getServerSocketFactory(), handler, true);
```

Then:
- Delete the whole method `private static SSLContext tlsContext() { ... }`.
- Add `import dev.andre.homecontrol.testsupport.TestTls;`.
- Remove the imports that have no use left, checking each with `grep`: `dev.andre.homecontrol.adapters.androidtv.protocol.ClientCertificate`, `KeyManagerFactory`, `SSLContext`, `GeneralSecurityException`, `KeyStore`, `SecureRandom` and `Certificate`. `MessageDigest` stays: the WebSocket handshake uses it.

Run the command again. Expected: PASS, with the same count.

- [ ] **Step 8: Commit**

```bash
git add src/test/java/dev/andre/homecontrol/adapters/net/FakeWebSocketServer.java
git commit -m "test: give the WebSocket fake its certificate from TestTls

It no longer borrows the Android TV adapter's ClientCertificate to be a
TLS server."
```

End the message with your `Co-Authored-By:` trailer.

### Task 8: `FakeWorkflowServer` over `FakeHttpServer`

**Files:**
- Modify (rewrite): `src/test/java/dev/andre/homecontrol/sources/workflows/FakeWorkflowServer.java`

**Interfaces:**
- Consumes: `FakeHttpServer`, `Request`, `Response` (Task 1), `TestTls.serverContext(String)` (Task 7).
- Produces the same members as before:
  - public: the constructor, `url(String)`, `respond(String, int, String)`, `count(String)` and `close()`;
  - package-private: `untrustedHttps()`, `redirect(String, int, String)`, `block(String, boolean, CountDownLatch, CountDownLatch)` and `route(String, HttpHandler)`.
- `requests(String)` now returns `List<Request>`. Its only callers use `.header(...)`, which `Request` has with the same meaning.

- [ ] **Step 1: Run the fake's tests before the change**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.workflows.*' --tests 'dev.andre.homecontrol.web.WorkflowEndToEndTest'`

Expected: PASS. Note the count from `cat build/test-results/test/TEST-dev.andre.homecontrol.sources.workflows.*.xml build/test-results/test/TEST-dev.andre.homecontrol.web.WorkflowEndToEndTest.xml | grep -c '<testcase '`.

- [ ] **Step 2: Rewrite the fake as a wrapper**

Replace the whole file with:

```java
package dev.andre.homecontrol.sources.workflows;

import com.sun.net.httpserver.HttpHandler;
import dev.andre.homecontrol.testsupport.FakeHttpServer;
import dev.andre.homecontrol.testsupport.Request;
import dev.andre.homecontrol.testsupport.Response;
import dev.andre.homecontrol.testsupport.TestTls;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static dev.andre.homecontrol.testsupport.FakeHttpServer.ANY_METHOD;

/** Local-only, managed fixture with request recording and deterministic blocking hooks. */
public final class FakeWorkflowServer implements AutoCloseable {

    private final FakeHttpServer server;

    public FakeWorkflowServer() throws IOException {
        this(FakeHttpServer.start());
    }

    private FakeWorkflowServer(FakeHttpServer server) {
        this.server = server;
    }

    /** HTTPS with a self-signed certificate for fixture.invalid, which the fetcher must refuse. */
    static FakeWorkflowServer untrustedHttps() throws IOException {
        return new FakeWorkflowServer(FakeHttpServer.start(TestTls.serverContext("fixture.invalid")));
    }

    public URI url(String path) {
        return server.url(path);
    }

    public void respond(String path, int status, String body) {
        server.respond(ANY_METHOD, path, Response.of(status, "application/json", body));
    }

    void redirect(String path, int status, String location) {
        server.respond(ANY_METHOD, path, Response.empty(status).withHeader("Location", location));
    }

    void block(String path, boolean afterHeaders, CountDownLatch entered, CountDownLatch release) {
        route(path, e -> {
            if (afterHeaders) {
                e.sendResponseHeaders(200, 0);
                e.getResponseBody().write('{');
                e.getResponseBody().flush();
            }
            entered.countDown();
            try { release.await(); } catch (InterruptedException _) { Thread.currentThread().interrupt(); return; }
            if (!afterHeaders) e.sendResponseHeaders(200, 0);
            e.getResponseBody().write('}');
        });
    }

    void route(String path, HttpHandler handler) { server.handle(ANY_METHOD, path, handler); }
    public int count(String path) { return server.count(ANY_METHOD, path); }
    List<Request> requests(String path) { return server.requests(ANY_METHOD, path); }

    @Override public void close() {
        server.close();
    }
}
```

- [ ] **Step 3: Run the fake's tests after the change, and compile the browser tests**

Run: the command from Step 1, then `scripts/gradle.sh compileE2eJava`.

Expected:
- PASS, with the same count as in Step 1. That includes the untrusted-HTTPS test, the redirect tests and the blocking tests, which end because `close()` interrupts the blocked handlers.
- `WorkflowE2eTest` compiles.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/dev/andre/homecontrol/sources/workflows/FakeWorkflowServer.java
git commit -m "test: build FakeWorkflowServer on FakeHttpServer and TestTls

Same routes by path, redirects, blocking hooks and recorded headers;
the untrusted HTTPS variant takes its certificate from TestTls."
```

End the message with your `Co-Authored-By:` trailer.

### Task 9: One `MutableClock`, one `EventStreamReader`

**Files:**
- Create: `src/test/java/dev/andre/homecontrol/testsupport/MutableClock.java`
- Test: `src/test/java/dev/andre/homecontrol/testsupport/MutableClockTest.java`
- Delete: `MutableClock.java` in `src/test/java/dev/andre/homecontrol/sources/youtube/`, `.../sources/tmdb/`, `.../sources/sports/thesportsdb/` and `.../sources/sports/calendar/`
- Modify: every user of those four copies (the 18 files from `git grep -l MutableClock -- src/test`), `SsdpDiscoveryTest.java` (nested copy), `content/RailCacheTest.java` (`TestClock`)
- Move: `src/test/java/dev/andre/homecontrol/web/EventStreamReader.java` → `src/test/java/dev/andre/homecontrol/testsupport/EventStreamReader.java`
- Modify: `web/CastEndToEndTest.java`, `web/BluetoothJellyfinEndToEndTest.java`, `web/DeviceStateStreamEndToEndTest.java`, `web/SpeakerJellyfinEndToEndTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `MutableClock extends Clock`, with `MutableClock(Instant, ZoneId)`, `static at(Instant)` (UTC), `advance(Duration)`, `instant()`, `getZone()` and `withZone(ZoneId)`;
  - `EventStreamReader(HttpResponse<Stream<String>>)`, with `lines()` and `awaitEnd(Duration)`.

- [ ] **Step 1: Write the failing test**

`src/test/java/dev/andre/homecontrol/testsupport/MutableClockTest.java`:

```java
package dev.andre.homecontrol.testsupport;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class MutableClockTest {

    private static final Instant START = Instant.parse("2026-09-16T12:00:00Z");

    @Test
    void standsStillUntilAdvanced() {
        MutableClock clock = MutableClock.at(START);

        assertThat(clock.instant()).isEqualTo(START);
        clock.advance(Duration.ofMinutes(30));
        assertThat(clock.instant()).isEqualTo(START.plus(Duration.ofMinutes(30)));
    }

    @Test
    void atIsInUtcAndTheConstructorKeepsItsZone() {
        assertThat(MutableClock.at(START).getZone()).isEqualTo(ZoneId.of("UTC"));
        assertThat(new MutableClock(START, ZoneOffset.UTC).getZone()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    void withZoneKeepsTheInstant() {
        MutableClock clock = MutableClock.at(START);

        assertThat(clock.withZone(ZoneId.of("America/Los_Angeles")).instant()).isEqualTo(START);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.testsupport.MutableClockTest'`

Expected: FAIL at `compileTestJava`: `cannot find symbol: class MutableClock` in `dev.andre.homecontrol.testsupport`.

- [ ] **Step 3: Write the shared clock**

`src/test/java/dev/andre/homecontrol/testsupport/MutableClock.java`:

```java
package dev.andre.homecontrol.testsupport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/** A {@link Clock} a test advances by hand: poll intervals, token expiry, cache lifetimes. */
public final class MutableClock extends Clock {

    private volatile Instant instant;
    private final ZoneId zone;

    public MutableClock(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    /** A clock at {@code instant}, in UTC. */
    public static MutableClock at(Instant instant) {
        return new MutableClock(instant, ZoneId.of("UTC"));
    }

    public void advance(Duration amount) {
        instant = instant.plus(amount);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    /** A clock at the same instant in another zone; advancing this one does not move it. */
    @Override
    public Clock withZone(ZoneId otherZone) {
        return new MutableClock(instant, otherZone);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
```

Run the command from Step 2. Expected: PASS, 3 tests.

- [ ] **Step 4: Replace the four copies and the two inline clocks**

```bash
git rm src/test/java/dev/andre/homecontrol/sources/youtube/MutableClock.java \
  src/test/java/dev/andre/homecontrol/sources/tmdb/MutableClock.java \
  src/test/java/dev/andre/homecontrol/sources/sports/thesportsdb/MutableClock.java \
  src/test/java/dev/andre/homecontrol/sources/sports/calendar/MutableClock.java
git grep -l 'MutableClock' -- src/test | grep -v testsupport
```

The last command lists the users. When this plan was written there were:
- 12 in `sources/youtube`;
- 2 each in `sources/tmdb` and `sources/sports/thesportsdb`;
- 1 in `sources/sports/calendar`;
- `discovery/ssdp/SsdpDiscoveryTest.java`.

Then:
- **Each user** except `SsdpDiscoveryTest` gets `import dev.andre.homecontrol.testsupport.MutableClock;` among its imports. Nothing else changes: `MutableClock.at(...)`, `new MutableClock(...)` and `advance(...)` keep their meaning.
- **`SsdpDiscoveryTest`:** delete the nested `private static final class MutableClock extends Clock { ... }` and add the same import. Its `new MutableClock(Instant.parse(...), ZoneOffset.UTC)` stays. Remove any import only the nested class used (check `Clock`, `ZoneId`).
- **`content/RailCacheTest`:**
  - Delete `static final class TestClock extends Clock { ... }`.
  - Replace `final TestClock clock = new TestClock();` with `final MutableClock clock = new MutableClock(Instant.parse("2026-09-16T09:00:00Z"), ZoneOffset.UTC);`.
  - Replace `clock.now` with `clock.instant()`.
  - Add the import, and remove imports only `TestClock` used (check `Clock`, `ZoneId`).

```bash
git grep -n 'TestClock\|extends Clock' -- src/test
```

Expected: only `src/test/java/dev/andre/homecontrol/testsupport/MutableClock.java`.

- [ ] **Step 5: Run the clock users**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.*' --tests 'dev.andre.homecontrol.discovery.ssdp.*' --tests 'dev.andre.homecontrol.content.RailCacheTest' --tests 'dev.andre.homecontrol.testsupport.*'`

Expected: PASS.

- [ ] **Step 6: Commit the clock**

```bash
git add src/test/java/dev/andre/homecontrol/testsupport/MutableClock.java src/test/java/dev/andre/homecontrol/testsupport/MutableClockTest.java src/test/java/dev/andre/homecontrol/sources src/test/java/dev/andre/homecontrol/discovery/ssdp/SsdpDiscoveryTest.java src/test/java/dev/andre/homecontrol/content/RailCacheTest.java
git commit -m "test: one MutableClock in testsupport instead of six

Replaces the YouTube, TMDB, TheSportsDB and calendar copies, the class
nested in SsdpDiscoveryTest and RailCacheTest.TestClock."
```

End the message with your `Co-Authored-By:` trailer. (`git add` of the `sources` directory stages only files already changed in this step: the deleted copies and the users' new imports. Check `git status --short` first, and stage by path if anything else shows up.)

- [ ] **Step 7: Move `EventStreamReader`**

```bash
git mv src/test/java/dev/andre/homecontrol/web/EventStreamReader.java src/test/java/dev/andre/homecontrol/testsupport/EventStreamReader.java
```

In the moved file:
- the package becomes `dev.andre.homecontrol.testsupport`;
- `final class EventStreamReader` becomes `public final class EventStreamReader`;
- the constructor and the methods `lines()` and `awaitEnd(Duration)` become `public`.

In `web/CastEndToEndTest.java`, `web/BluetoothJellyfinEndToEndTest.java`, `web/DeviceStateStreamEndToEndTest.java` and `web/SpeakerJellyfinEndToEndTest.java`, add `import dev.andre.homecontrol.testsupport.EventStreamReader;`.

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.web.CastEndToEndTest' --tests 'dev.andre.homecontrol.web.BluetoothJellyfinEndToEndTest' --tests 'dev.andre.homecontrol.web.DeviceStateStreamEndToEndTest' --tests 'dev.andre.homecontrol.web.SpeakerJellyfinEndToEndTest'`

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/test/java/dev/andre/homecontrol/testsupport/EventStreamReader.java src/test/java/dev/andre/homecontrol/web/EventStreamReader.java src/test/java/dev/andre/homecontrol/web/CastEndToEndTest.java src/test/java/dev/andre/homecontrol/web/BluetoothJellyfinEndToEndTest.java src/test/java/dev/andre/homecontrol/web/DeviceStateStreamEndToEndTest.java src/test/java/dev/andre/homecontrol/web/SpeakerJellyfinEndToEndTest.java
git commit -m "test: move EventStreamReader to testsupport"
```

End the message with your `Co-Authored-By:` trailer.

### Task 10: `RecordingStateListener` in the six session tests

**Files:**
- Create: `src/test/java/dev/andre/homecontrol/testsupport/RecordingStateListener.java`
- Test: `src/test/java/dev/andre/homecontrol/testsupport/RecordingStateListenerTest.java`
- Modify, all under `src/test/java/dev/andre/homecontrol/adapters/`: `cast/CastSessionTest.java`, `webos/WebOsSessionTest.java`, `tizen/TizenSessionTest.java`, `upnp/UpnpSessionTest.java`, `sonos/SonosSessionTest.java`, `bluetooth/BluetoothSpeakerSessionTest.java`

**Interfaces:**
- Consumes: `dev.andre.homecontrol.core.DeviceState`, `DeviceStatus`, `DeviceState.status()`.
- Produces: `RecordingStateListener implements Consumer<DeviceState>`, with `accept(DeviceState)`, `all()`, `last()`, `clear()`, `awaitStatus(DeviceStatus)` and `awaitStatus(DeviceStatus, Duration)`.

- [ ] **Step 1: Write the failing test**

`src/test/java/dev/andre/homecontrol/testsupport/RecordingStateListenerTest.java`. A state with a given status is `DeviceState.initial().withStatus(status)`, as elsewhere in the tests:

```java
package dev.andre.homecontrol.testsupport;

import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecordingStateListenerTest {

    private final RecordingStateListener states = new RecordingStateListener();

    private static DeviceState state(DeviceStatus status) {
        return DeviceState.initial().withStatus(status);
    }

    @Test
    void recordsEveryStateInOrder() {
        states.accept(state(DeviceStatus.CONNECTING));
        states.accept(state(DeviceStatus.CONNECTED));

        assertThat(states.all()).extracting(DeviceState::status)
                .containsExactly(DeviceStatus.CONNECTING, DeviceStatus.CONNECTED);
        assertThat(states.last().status()).isEqualTo(DeviceStatus.CONNECTED);
    }

    @Test
    void lastFailsBeforeTheFirstStateAndClearForgets() {
        assertThatThrownBy(states::last).isInstanceOf(NoSuchElementException.class);

        states.accept(state(DeviceStatus.CONNECTED));
        states.clear();

        assertThat(states.all()).isEmpty();
    }

    @Test
    void awaitStatusReturnsTheFirstStateWithThatStatus() {
        states.accept(state(DeviceStatus.CONNECTING));
        Thread.ofVirtual().start(() -> states.accept(state(DeviceStatus.DISCONNECTED)));

        assertThat(states.awaitStatus(DeviceStatus.DISCONNECTED, Duration.ofSeconds(5)).status())
                .isEqualTo(DeviceStatus.DISCONNECTED);
    }

    @Test
    void awaitStatusFailsWhenTheStatusNeverComes() {
        states.accept(state(DeviceStatus.CONNECTED));

        assertThatThrownBy(() -> states.awaitStatus(DeviceStatus.UNPAIRED, Duration.ofMillis(200)))
                .isInstanceOf(ConditionTimeoutException.class);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.testsupport.RecordingStateListenerTest'`

Expected: FAIL at `compileTestJava`: `cannot find symbol: class RecordingStateListener`.

- [ ] **Step 3: Write the recorder**

`src/test/java/dev/andre/homecontrol/testsupport/RecordingStateListener.java`:

```java
package dev.andre.homecontrol.testsupport;

import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.awaitility.Awaitility.await;

/** Records every state a session publishes, in order, for tests that assert on what was published. */
public final class RecordingStateListener implements Consumer<DeviceState> {

    private final List<DeviceState> states = new CopyOnWriteArrayList<>();

    @Override
    public void accept(DeviceState state) {
        states.add(state);
    }

    /** Every state so far, oldest first. */
    public List<DeviceState> all() {
        return List.copyOf(states);
    }

    /** The newest state. Throws {@link java.util.NoSuchElementException} before the first one. */
    public DeviceState last() {
        return states.getLast();
    }

    public void clear() {
        states.clear();
    }

    /** {@link #awaitStatus(DeviceStatus, Duration)} with Awaitility's default of 10 s. */
    public DeviceState awaitStatus(DeviceStatus status) {
        return awaitStatus(status, Duration.ofSeconds(10));
    }

    /** Waits until some state so far has {@code status}, and returns the first that has. */
    public DeviceState awaitStatus(DeviceStatus status, Duration atMost) {
        await().atMost(atMost).until(() -> states.stream().anyMatch(state -> state.status() == status));
        return states.stream().filter(state -> state.status() == status).findFirst().orElseThrow();
    }
}
```

Run the command from Step 2. Expected: PASS, 4 tests.

- [ ] **Step 4: Use it in the six session tests**

First run the six classes and note the count:

`scripts/gradle.sh test --tests 'dev.andre.homecontrol.adapters.cast.CastSessionTest' --tests 'dev.andre.homecontrol.adapters.webos.WebOsSessionTest' --tests 'dev.andre.homecontrol.adapters.tizen.TizenSessionTest' --tests 'dev.andre.homecontrol.adapters.upnp.UpnpSessionTest' --tests 'dev.andre.homecontrol.adapters.sonos.SonosSessionTest' --tests 'dev.andre.homecontrol.adapters.bluetooth.BluetoothSpeakerSessionTest'`

In each class:
- Replace the recorded list's declaration with `private final RecordingStateListener states = new RecordingStateListener();`. In `CastSessionTest`, the field is `seen`: keep the name `seen`.
- Add `import dev.andre.homecontrol.testsupport.RecordingStateListener;`.
- Then rewrite the uses as in this table:

| Old | New |
| --- | --- |
| `states::add` (`seen::add`) passed as the listener | `states` (`seen`); where the parameter is not a `Consumer<DeviceState>`, `states::accept` |
| `states.add(state)` inside a listener lambda | `states.accept(state)` |
| `states.getLast()` | `states.last()` |
| `states.getFirst()` | `states.all().getFirst()` |
| `states.stream()` (`seen.stream()`), where it is not one of the waits below | `states.all().stream()` |
| `assertThat(states)` (`assertThat(seen)`) | `assertThat(states.all())` |
| `seen.size()` | `seen.all().size()` |
| `states.clear()` | stays |
| `until(states::isEmpty)` | `until(() -> states.all().isEmpty())` |

These waits, and only these, become `awaitStatus`:

| Class | Old | New |
| --- | --- | --- |
| `CastSessionTest` | `await().until(() -> seen.stream().anyMatch(state -> state.status() == DeviceStatus.DISCONNECTED));` | `seen.awaitStatus(DeviceStatus.DISCONNECTED);` |
| `CastSessionTest` | `await().atMost(Duration.ofSeconds(10))` + `.until(() -> seen.stream().anyMatch(state -> state.status() == DeviceStatus.DISCONNECTED));` | `seen.awaitStatus(DeviceStatus.DISCONNECTED, Duration.ofSeconds(10));` |
| `TizenSessionTest` | `await().atMost(Duration.ofSeconds(5)).until(() -> states.stream()` + `.anyMatch(state -> state.status() == DeviceStatus.DISCONNECTED));` | `states.awaitStatus(DeviceStatus.DISCONNECTED, Duration.ofSeconds(5));` |
| `WebOsSessionTest` (two places) | the same wait with 5 s and with 6 s | `states.awaitStatus(DeviceStatus.DISCONNECTED, Duration.ofSeconds(5));` and `..., Duration.ofSeconds(6));` |

The waits with a second condition stay as they are, with `seen.all().stream()`. These are Cast's "`DISCONNECTED` while `CONNECTING` was seen" wait and its "`DISCONNECTED` with no `nowPlaying`" wait. The classes' own `awaitStatus(...)` helpers, which poll `session.state()`, stay unchanged.

Then:
- Remove the imports each class no longer uses: `CopyOnWriteArrayList`, `List` and `Consumer`. Check each with `grep`; `Consumer` stays wherever a helper still takes a `Consumer<DeviceState>` parameter.
- Run `git grep -n 'List<DeviceState>\|List<dev.andre.homecontrol.core.DeviceState>' -- src/test/java/dev/andre/homecontrol/adapters/*/*SessionTest.java`. Expected: nothing.

Run the six classes again. Expected: PASS, with the same count. (If a webOS or Tizen timing test fails, run its class alone, as in Task 7 Step 7, and report it.)

- [ ] **Step 5: Commit**

```bash
git add src/test/java/dev/andre/homecontrol/testsupport/RecordingStateListener.java src/test/java/dev/andre/homecontrol/testsupport/RecordingStateListenerTest.java src/test/java/dev/andre/homecontrol/adapters/cast/CastSessionTest.java src/test/java/dev/andre/homecontrol/adapters/webos/WebOsSessionTest.java src/test/java/dev/andre/homecontrol/adapters/tizen/TizenSessionTest.java src/test/java/dev/andre/homecontrol/adapters/upnp/UpnpSessionTest.java src/test/java/dev/andre/homecontrol/adapters/sonos/SonosSessionTest.java src/test/java/dev/andre/homecontrol/adapters/bluetooth/BluetoothSpeakerSessionTest.java
git commit -m "test: record session states with RecordingStateListener

Replaces the hand-written List<DeviceState> recorders in the Cast,
webOS, Tizen, UPnP, Sonos and Bluetooth session tests; waits for a
recorded status use awaitStatus."
```

End the message with your `Co-Authored-By:` trailer.

### Task 11: Documentation, the measurements, and the whole build

**Files:**
- Modify: `docs/dev/testing.md` (section "Fakes and fixtures")
- Modify: `AGENTS.md` (section "Where things live")
- Modify: `docs/dev/architecture.md` (section "Progress measures")

**Interfaces:**
- Consumes: the `testsupport` classes from Tasks 1, 7, 9 and 10.
- Produces: nothing.

- [ ] **Step 1: Describe `testsupport` in the testing guide**

In `docs/dev/testing.md`, under `## Fakes and fixtures`, insert this bullet before the one about recorded responses:

```markdown
- Shared helpers live in `dev.andre.homecontrol.testsupport`: `FakeHttpServer` for any HTTP or HTTPS fake,
  `TestTls` for a self-signed server certificate, `MutableClock`, `RecordingStateListener` and `EventStreamReader`.
  A fake of a web API (Jellyfin, TMDB, Google, TheSportsDB, calendars, workflows) is a thin wrapper over
  `FakeHttpServer` that keeps the service's own vocabulary; a fake of a socket protocol (UPnP, Tizen, Cast, Android
  TV, mpv) stays protocol-specific.
```

- [ ] **Step 2: Point to it from `AGENTS.md`**

In `AGENTS.md`, under `## Where things live`, insert after the bullet about fakes and fixtures:

```markdown
- Shared test helpers: `src/test/java/dev/andre/homecontrol/testsupport/`. Build a new web-API fake on
  `FakeHttpServer` instead of opening a server of its own.
```

- [ ] **Step 3: Update the progress measure**

In `docs/dev/architecture.md`, in the `## Progress measures` table, the row `Copies of MutableClock` gets `1` in the "Now" column.

- [ ] **Step 4: Measure the fake and helper code again**

```bash
wc -l src/test/java/dev/andre/homecontrol/sources/jellyfin/FakeJellyfinServer.java \
  src/test/java/dev/andre/homecontrol/sources/sports/calendar/FakeCalendarServer.java \
  src/test/java/dev/andre/homecontrol/sources/sports/thesportsdb/FakeTheSportsDbServer.java \
  src/test/java/dev/andre/homecontrol/sources/tmdb/FakeTmdbServer.java \
  src/test/java/dev/andre/homecontrol/sources/youtube/FakeGoogleServer.java \
  src/test/java/dev/andre/homecontrol/sources/workflows/FakeWorkflowServer.java \
  src/test/java/dev/andre/homecontrol/testsupport/FakeHttpServer.java \
  src/test/java/dev/andre/homecontrol/testsupport/Request.java \
  src/test/java/dev/andre/homecontrol/testsupport/Response.java \
  src/test/java/dev/andre/homecontrol/testsupport/TestTls.java \
  src/test/java/dev/andre/homecontrol/testsupport/MutableClock.java \
  src/test/java/dev/andre/homecontrol/testsupport/EventStreamReader.java \
  src/test/java/dev/andre/homecontrol/testsupport/RecordingStateListener.java | tail -1
```

Expected: a total below Task 1 Step 1's 1,281, although this total also counts `TestTls` and `RecordingStateListener`, which replace code the baseline did not count. Write both numbers into the pull request description.

- [ ] **Step 5: Check the guarantees**

```bash
git diff --stat origin/main -- src/main
git grep -n 'com.sun.net.httpserver.HttpServer;' -- src/test/java/dev/andre/homecontrol/sources
scripts/gradle.sh build
cat build/test-results/test/*.xml | grep -c '<testcase '
scripts/gradle.sh compileE2eJava
```

Expected:
- the first two commands print nothing: no production change, and no web-API fake opens its own server;
- the build is green;
- the test count is Task 1 Step 1's count plus 27 (17 `FakeHttpServerTest`, 3 `TestTlsTest`, 3 `MutableClockTest`, 4 `RecordingStateListenerTest`);
- the browser tests compile.

- [ ] **Step 6: Commit**

```bash
git add docs/dev/testing.md AGENTS.md docs/dev/architecture.md
git commit -m "docs: describe the shared test helpers in testsupport

The testing guide and AGENTS.md point new web-API fakes at
FakeHttpServer; the MutableClock progress measure is now 1."
```

End the message with your `Co-Authored-By:` trailer.
