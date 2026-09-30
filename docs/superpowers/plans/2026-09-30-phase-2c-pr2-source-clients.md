# Phase 2C, PR 2: The Source Clients Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Jellyfin, TMDB, TheSportsDB and YouTube reach the network through `GuardedHttpClient`, every class outside
the adapters reads and writes JSON with one hardened mapper, and ArchUnit keeps it that way.

**Architecture:** Each of the four clients builds a `GuardedHttpClient` from a profile and keeps only its protocol:
request shapes, headers, credentials and status sentences. `BoundedBody` goes with its last user. `config.Json.MAPPER`
replaces sixteen separately built mappers. Two strict ArchUnit rules forbid new HTTP stacks in sources and new
mapper builders outside `config` and the adapters.

**Tech Stack:** Java 25, Apache HttpClient 5, Jackson 3 (`tools.jackson.*`), ArchUnit, JUnit 5, AssertJ,
`testsupport.FakeHttpServer`.

**Spec:** `docs/superpowers/specs/2026-09-30-phase-2c-outbound-http-design.md` (sections 2–4, PR 2). PR 1 merged as
#150 (`d0bdb89`).

## Global Constraints

- Branch `refactor/source-http-clients`, from main `d0bdb89`.
- Behaviour stays the same unless the spec names the change. PR 2's visible changes:
  1. Jellyfin, TMDB, TheSportsDB and YouTube connect only to addresses the policy approves, pinned. A Jellyfin server
     at a link-local `169.254.x.x` address stops working, and a public API host that DNS points at loopback, any-local
     or link-local is reported as blocked.
  4. TMDB treats 410 like 404.
  5. Transport failures of these four read the shared way ("Could not reach Jellyfin at media.lan (request timed
     out)"). Messages about statuses and content keep their wording.
- Loopback: Jellyfin always; TMDB, TheSportsDB and YouTube only with their new `allow-loopback` setting (default
  false). No source with a bearer token or a key uses `CHECKED` redirects: all four use `NONE`.
- YouTube (every call) and TheSportsDB (its "api key" error text) read error bodies with `withErrorBody()`; TMDB and
  Jellyfin do not.
- No failure message contains a path, query, header value, key or token, and no failure carries a cause.
- `ContentSourceException` subclasses keep their types; `ErrorAdvice` still answers 502.
- `scripts/gradle.sh build` green after every task; `scripts/e2e.sh -Pe2eBrowsers=chromium` green at the end. Frozen
  violations stay 42; never refreeze. The two new rules start strict.
- Stage only the files you changed. Conventional Commits with the session trailer.

**Rulings made while planning (the spec left these open):**
- **`allow-loopback` for TMDB, TheSportsDB and YouTube.** The spec lets these three reach loopback only through "their
  existing allow-loopback setting", but they have none, and every test runs their fakes on 127.0.0.1. Each gets
  `home-control.tmdb.allow-loopback`, `home-control.sports.the-sports-db.allow-loopback` and
  `home-control.youtube.allow-loopback` (default false), like calendars and workflows. The test configuration and the
  browser tests turn them on.
- **One mapper as a constant, not a bean.** Thirteen of the sixteen classes hold their mapper in a static field used
  from static code (records, static helpers). A bean would change about 260 constructor calls, most in tests, for no
  difference in behaviour. `config.Json.MAPPER` is defined once, hardened, and the ArchUnit rule allows
  `JsonMapper.builder()` only in `config` and the adapters.
- **A POST without a content type sends none.** Jellyfin's session commands post an empty body. The JDK client sent no
  `Content-Type`; the guarded client sent `application/octet-stream`. It now sends none when the request names none.
- **The source exceptions lose their cause constructors.** After this PR nothing passes a cause, and the spec says no
  transport failure carries one: `TmdbException`, `TheSportsDbException` and `CalendarFetchException` keep only
  `(Kind, String)`.

## Review Focus

1. **Jellyfin on the same machine** (`http://localhost:8096`, `http://127.0.0.1:8096`) keeps working with no setting.
   Pinned in Task 2 (`JellyfinClientTest.aServerOnThisMachineIsReachedWithoutASetting`).
2. **A Jellyfin image between 2 MB and 10 MB** still loads: images have their own cap. Pinned in Task 2
   (`JellyfinClientTest.anImageMayBeLargerThanAnAnswer`).
3. **TheSportsDB's "api key" error text** (a 400 or 404 whose body mentions the key) still means UNAUTHORIZED, which
   needs the error body. Pinned in Task 1 (`TheSportsDbClientTest.aRejectedKeyIsReadFromTheErrorBody`).
4. **Google's OAuth errors** (`invalid_grant` → REVOKED) still come from the error body. `GoogleOAuthClientTest` pins
   it; Task 3 checks that it still passes unchanged.
5. **The production defaults refuse loopback** for TMDB, TheSportsDB and YouTube, so a DNS answer of 127.0.0.1 for a
   public API host is blocked. Pinned in Tasks 1 and 3 (`…ClientTest.refusesLoopbackByDefault`).

---

### Task 1: TMDB and TheSportsDB on the guarded client

**Files:**
- Modify: `sources/tmdb/TmdbClient.java`, `sources/tmdb/TmdbProperties.java`,
  `sources/sports/thesportsdb/TheSportsDbClient.java`, `sources/sports/SportsProperties.java` (`TheSportsDb`),
  `sources/tmdb/TmdbException.java`, `sources/sports/thesportsdb/TheSportsDbException.java`,
  `sources/sports/calendar/CalendarFetchException.java` (cause constructors go), `src/main/resources/application.yaml`,
  `src/test/resources/config/application.yaml`
- Modify tests: every `new TmdbProperties(…)` and `new SportsProperties.TheSportsDb(…)` gains a final `true`
  (`allowLoopback`; the fakes run on loopback); expectations follow visible changes 4 and 5
- Test: `TmdbClientTest`, `TheSportsDbClientTest`, `sources/http/SlowBodyDeadlineTest`

**Interfaces:**
- Consumes: `GuardedHttpClient(Profile, OutboundAddressPolicy, Function<OutboundFailure, ? extends RuntimeException>)`,
  `OutboundRequest.get/header/withErrorBody`, `OutboundResponse.status/body/contentType`, `Statuses.kindOf`,
  `OutboundFailure.describe(String)`.
- Produces: `TmdbProperties.allowLoopback()`, `SportsProperties.TheSportsDb.allowLoopback()` (last component,
  `@DefaultValue("false")`); `TmdbClient` and `TheSportsDbClient` implement `AutoCloseable` (Spring closes the beans);
  their other constructors (taking a JDK `HttpClient`) go.

- [ ] **Step 1: Write the failing tests**
  - `TmdbClientTest.aGoneTitleIsNotFound` (visible change 4): the fake answers 410 → `TmdbException` with kind
    `NOT_FOUND` and the message "TMDB does not know this title".
  - `TmdbClientTest.refusesLoopbackByDefault` and `TheSportsDbClientTest.refusesLoopbackByDefault`: properties with
    `allowLoopback` false against the loopback fake → kind `BLOCKED`, and the fake receives nothing.
  - `TheSportsDbClientTest.aRejectedKeyIsReadFromTheErrorBody`: a 404 whose body says "Invalid API key" → kind
    `UNAUTHORIZED`, "TheSportsDB rejected the API key".
  - `TmdbClientTest.noFailureRevealsTheKey` and `TheSportsDbClientTest.noFailureRevealsTheKey`: against a
    `FakeHttpServer`, with the API key in the query (TMDB) or the path (TheSportsDB), for a too-large body, a
    compressed body, a trickled body, a refused connection and a blocked address, the message contains neither the
    key nor the path, and the exception has no cause.

- [ ] **Step 2: Run them.** `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.tmdb.TmdbClientTest'
  --tests 'dev.andre.homecontrol.sources.sports.thesportsdb.TheSportsDbClientTest'`. Expected: compilation fails
  (`allowLoopback` does not exist); after adding only the property component, `aGoneTitleIsNotFound`,
  `refusesLoopbackByDefault` and the leak tests fail.

- [ ] **Step 3: Implement**

```java
// TmdbClient — the transport becomes the guarded client; request shapes and sentences stay.
public class TmdbClient implements AutoCloseable {

    static final int MAX_BODY_BYTES = 2 * 1024 * 1024;
    /** Rail refreshes, the setup page and pinned items share these. */
    private static final int MAX_CONCURRENT = 8;
    private static final HttpUrls.Rules API_URLS = new HttpUrls.Rules(true, false, true, false, 0);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final TmdbProperties properties;
    private final GuardedHttpClient http;

    public TmdbClient(TmdbProperties properties) {
        this.properties = properties;
        this.http = new GuardedHttpClient(new GuardedHttpClient.Profile("TMDB", GuardedHttpClient.Redirects.NONE, 0,
                MAX_BODY_BYTES, properties.connectTimeout(), properties.requestTimeout(), MAX_CONCURRENT, API_URLS),
                new OutboundAddressPolicy(properties.allowLoopback()),
                failure -> new TmdbException(failure.kind(), failure.describe("TMDB")));
    }

    public JsonNode get(TmdbCredential credential, String path, Map<String, String> query) {
        OutboundRequest request = OutboundRequest.get(uri(credential, path, query)).header("Accept", "application/json");
        if (credential.kind() == TmdbCredential.Kind.BEARER) {
            request = request.header("Authorization", "Bearer " + credential.value());
        }
        OutboundResponse response = http.send(request);
        int status = response.status();
        if (status != 200) {
            ContentSourceException.Kind kind = Statuses.kindOf(status);
            throw new TmdbException(kind, switch (kind) {
                case UNAUTHORIZED -> "TMDB rejected the API key or read access token";
                case NOT_FOUND -> "TMDB does not know this title";
                case RATE_LIMITED -> "TMDB is limiting requests; try again in a moment";
                case SERVER_ERROR -> "TMDB had a server error (HTTP " + status + ")";
                default -> "TMDB answered HTTP " + status;
            });
        }
        JsonNode node;
        try {
            node = JSON.readTree(response.body());
        } catch (JacksonException _) {
            throw new TmdbException(ContentSourceException.Kind.BAD_RESPONSE, "TMDB answered with something that is not JSON");
        }
        if (node == null || !node.isObject()) {
            throw new TmdbException(ContentSourceException.Kind.BAD_RESPONSE, "TMDB answered with something unexpected");
        }
        return node;
    }

    @Override
    public void close() {
        http.close();
    }

    // uri(…), withoutTrailingSlashes(…), encode(…) unchanged; unreachable() goes.
}
```

```java
// TheSportsDbClient.get — the key check and the endpoint helpers stay.
    public JsonNode get(String key, String endpoint, Map<String, String> query) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new TheSportsDbException(ContentSourceException.Kind.UNAUTHORIZED, "That does not look like a TheSportsDB API key");
        }
        // The error body is read: TheSportsDB says "api key" in it when it refuses the key with a 400 or 404.
        OutboundResponse response = http.send(OutboundRequest.get(uri(key, endpoint, query))
                .header("Accept", "application/json").header("User-Agent", "HomeControl").withErrorBody());
        int status = response.status();
        boolean mentionsApiKey = new String(response.body(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT).contains("api key");
        if (status == 401 || status == 403 || ((status == 400 || status == 404) && mentionsApiKey)) {
            throw new TheSportsDbException(ContentSourceException.Kind.UNAUTHORIZED, "TheSportsDB rejected the API key");
        }
        if (status != 200) {
            ContentSourceException.Kind kind = Statuses.kindOf(status);
            throw new TheSportsDbException(kind, switch (kind) {
                case RATE_LIMITED -> "TheSportsDB is limiting requests; try again in a minute";
                case SERVER_ERROR -> "TheSportsDB had a server error (HTTP " + status + ")";
                default -> "TheSportsDB answered HTTP " + status;
            });
        }
        JsonNode node;
        try {
            node = JSON.readTree(response.body());
        } catch (JacksonException _) {
            throw new TheSportsDbException(ContentSourceException.Kind.BAD_RESPONSE, "TheSportsDB answered with something that is not JSON");
        }
        if (node == null || !node.isObject()) {
            throw new TheSportsDbException(ContentSourceException.Kind.BAD_RESPONSE, "TheSportsDB answered with something unexpected");
        }
        return node;
    }
```

`TheSportsDbClient` builds its client like TMDB's: name "TheSportsDB", `NONE`, `MAX_BODY_BYTES`, the properties'
timeouts, 8 slots, `new OutboundAddressPolicy(properties.allowLoopback())`, and
`failure -> new TheSportsDbException(failure.kind(), failure.describe("TheSportsDB"))`; it implements
`AutoCloseable`. The properties records gain `@DefaultValue("false") boolean allowLoopback` as their last component;
`application.yaml` lists `allow-loopback: false` under `tmdb` and `sports.the-sports-db`, and the test configuration
sets both to `true`. Kinds that change with the shared mapping (TheSportsDB's 404 without the key becomes `NOT_FOUND`;
an oversized body becomes `TOO_LARGE`) are not branched on anywhere; their sentences follow visible change 5.

- [ ] **Step 4: Update the other tests' expectations** only where visible change 5 alters a transport or size
  message: "Could not reach TMDB at host" → "Could not reach TMDB at host (reason)"; "Could not reach TheSportsDB" →
  "Could not reach TheSportsDB at host (reason)"; "answered with more data than expected" → "TMDB at host sent more
  than 2 MB" with kind `TOO_LARGE`; causes are gone.

- [ ] **Step 5: Run the build.** `scripts/gradle.sh build`. Expected: green.

- [ ] **Step 6: Commit** `refactor: fetch TMDB and TheSportsDB through the guarded client`.

---

### Task 2: Jellyfin on the guarded client

**Files:**
- Modify: `sources/jellyfin/JellyfinClient.java`; `sources/http/GuardedHttpClient.java` (a POST without a content
  type sends none)
- Test: `JellyfinClientTest`, `JellyfinClientFailuresTest`, `sources/http/GuardedHttpClientTest`,
  `sources/http/SlowBodyDeadlineTest`; expectations elsewhere follow visible change 5

**Interfaces:**
- Consumes: Task 1's pattern; `OutboundRequest.post(URI, byte[], String)`, `.limitedTo(int)`.
- Produces: `JellyfinClient` implements `AutoCloseable`; its public methods keep their signatures.

- [ ] **Step 1: Write the failing tests**
  - `GuardedHttpClientTest.aPostWithoutAContentTypeSendsNone`: `OutboundRequest.post(uri, new byte[0], null)` → the
    fake records no `content-type` header and an empty body.
  - `JellyfinClientTest.aServerOnThisMachineIsReachedWithoutASetting`: the loopback fake answers `/System/Info/Public`
    → `publicInfo` succeeds (the default properties have no loopback setting).
  - `JellyfinClientTest.aLinkLocalServerIsBlocked` (visible change 1): a server URL `http://169.254.10.20:8096` →
    `JellyfinException` kind `BLOCKED`, "Home Control does not connect to 169.254.10.20 (address not allowed)".
  - `JellyfinClientTest.anImageMayBeLargerThanAnAnswer`: a 3 MB `image/jpeg` → `image(…)` returns it.
  - `JellyfinClientFailuresTest.noFailureRevealsTheToken`: with a token in the `Authorization` header and a query on
    the path, for a too-large answer, a compressed answer, a trickled answer and a refused connection, the message
    contains neither the token nor the path and has no cause; an unreachable message still ends with "Check the
    address and that Home Control can reach it."

- [ ] **Step 2: Run them.** Expected: the POST test fails (`application/octet-stream` is sent); the link-local test
  fails (the JDK client tries to connect and reports it unreachable).

- [ ] **Step 3: Implement.** In `GuardedHttpClient.once`, a request body without a content type gets an entity with
  no content type (`new ByteArrayEntity(body, null)`). `JellyfinClient`:

```java
    /** Artwork and session pages load many at once from the LAN server. */
    private static final int MAX_CONCURRENT = 16;
    private final GuardedHttpClient http;

    JellyfinClient(JellyfinProperties properties, String version) {
        this.properties = properties;
        this.version = version;
        // Jellyfin is often on this machine: loopback is allowed. Redirects are refused, as they would replay the
        // Authorization header.
        this.http = new GuardedHttpClient(new GuardedHttpClient.Profile("Jellyfin", GuardedHttpClient.Redirects.NONE,
                0, MAX_JSON_BYTES, properties.connectTimeout(), properties.requestTimeout(), MAX_CONCURRENT, SERVER_URLS),
                new OutboundAddressPolicy(true), JellyfinClient::failure);
    }

    private static JellyfinException failure(OutboundFailure failure) {
        String message = failure.describe("Jellyfin");
        return new JellyfinException(failure.kind(), failure.kind() == ContentSourceException.Kind.UNREACHABLE
                ? message + ". Check the address and that Home Control can reach it." : message);
    }

    private static URI uri(URI serverUrl, String path, Map<String, String> query) {
        return URI.create(serverUrl + path + queryString(query));
    }

    private OutboundRequest signed(OutboundRequest request, String deviceId, String token) {
        return request.header("Accept", APPLICATION_JSON).header("Authorization", authorization(deviceId, token));
    }

    private JsonNode send(URI serverUrl, OutboundRequest request) {
        OutboundResponse response = http.send(request);
        requireSuccess(serverUrl, response.status());
        byte[] bytes = response.body();
        return bytes.length == 0 ? MissingNode.getInstance() : parse(serverUrl, bytes);
    }
```

  - `publicInfo`: `send(serverUrl, signed(OutboundRequest.get(uri(serverUrl, "/System/Info/Public", Map.of())), null, null))`.
  - `authenticateByName`: `OutboundRequest.post(uri(…"/Users/AuthenticateByName"…), mapper.writeValueAsBytes(body),
    APPLICATION_JSON)`, signed with the device id.
  - `get`: `OutboundRequest.get(uri(…))`, signed with the connection's device id and token.
  - `post`: `OutboundRequest.post(uri(…), body == null ? new byte[0] : mapper.writeValueAsBytes(body), body == null ?
    null : APPLICATION_JSON)`, signed.
  - `image`: `OutboundRequest.get(uri(…"/Items/{id}/Images/{type}"…)).header("Accept", "image/*").limitedTo(MAX_IMAGE_BYTES)`;
    404 → empty; not 200 or not an allowed type → "Jellyfin at {serverUrl} sent no image".

  `exchange`, `contentLengthExceeds`, `unreachable` and the `java.net.http` imports go; `requireSuccess`, `parse`,
  `notJellyfin` and every status sentence stay. `JellyfinClient` implements `AutoCloseable`.

- [ ] **Step 4: Update expectations** only for visible change 5: "Could not reach Jellyfin at http://host:port
  (reason). Check …" → "Could not reach Jellyfin at host (reason). Check …"; "sent an oversized response" and "sent an
  oversized image" → "Jellyfin at host sent more than 2 MB" / "… 10 MB" with kind `TOO_LARGE`.

- [ ] **Step 5: Run the build.** `scripts/gradle.sh build`. Expected: green.

- [ ] **Step 6: Commit** `refactor: fetch Jellyfin through the guarded client`.

---

### Task 3: YouTube on the guarded client; `BoundedBody` goes

**Files:**
- Modify: `sources/youtube/YouTubeHttp.java`, `sources/youtube/YouTubeProperties.java`,
  `src/main/resources/application.yaml`, `src/test/resources/config/application.yaml`,
  `src/test/java/.../sources/youtube/FakeGoogleServer.java` (its properties), `src/e2e/.../YouTubeOAuthE2eTest.java`
  (`home-control.youtube.allow-loopback`)
- Delete: `sources/http/BoundedBody.java`, `src/test/.../sources/http/BoundedBodyTest.java`
- Test: `YouTubeHttpTest`, `sources/http/SlowBodyDeadlineTest`

**Interfaces:**
- Produces: `YouTubeHttp` keeps `get(URI, Map)`, `postForm(URI, Map, Map)`, `uri(…)`, `form(…)` and its `Response`
  record; it implements `AutoCloseable`. `YouTubeProperties.allowLoopback()` (last component, default false).

- [ ] **Step 1: Write the failing tests**
  - `YouTubeHttpTest.refusesLoopbackByDefault`: properties with `allowLoopback` false against the loopback fake →
    `YouTubeException` kind `BLOCKED`.
  - `YouTubeHttpTest.readsAnErrorBody`: a 403 with a JSON body → `Response.status()` 403 and the body readable.
  - `YouTubeHttpTest.noFailureRevealsATokenOrTheQuery`: a bearer token header and a form with `client_secret`; a
    too-large, compressed, trickled and refused answer → no token, secret or query in the message, no cause.

- [ ] **Step 2: Run them.** Expected: compilation fails (`allowLoopback`); then `refusesLoopbackByDefault` and the
  leak test's cause assertion fail.

- [ ] **Step 3: Implement**

```java
public class YouTubeHttp implements AutoCloseable {

    static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    /** Thumbnails are proxied for the dashboard, many at once. */
    private static final int MAX_CONCURRENT = 16;
    private static final HttpUrls.Rules GOOGLE_URLS = new HttpUrls.Rules(true, false, true, false, 0);

    // Response record unchanged.

    private final GuardedHttpClient http;

    public YouTubeHttp(YouTubeProperties properties) {
        // Every caller reads Google's error bodies (OAuth error codes, quota reasons), so every request asks for them.
        this.http = new GuardedHttpClient(new GuardedHttpClient.Profile("Google", GuardedHttpClient.Redirects.NONE, 0,
                MAX_RESPONSE_BYTES, properties.connectTimeout(), properties.requestTimeout(), MAX_CONCURRENT,
                GOOGLE_URLS), new OutboundAddressPolicy(properties.allowLoopback()),
                failure -> new YouTubeException(failure.kind(), failure.describe("Google")));
    }

    public Response get(URI uri, Map<String, String> headers) {
        return send(withHeaders(OutboundRequest.get(uri), headers));
    }

    public Response postForm(URI uri, Map<String, String> form, Map<String, String> headers) {
        return send(withHeaders(OutboundRequest.post(uri, form(form).getBytes(StandardCharsets.UTF_8),
                "application/x-www-form-urlencoded").header("Accept", "application/json"), headers));
    }

    private static OutboundRequest withHeaders(OutboundRequest request, Map<String, String> headers) {
        OutboundRequest with = request.withErrorBody();
        for (Map.Entry<String, String> header : headers.entrySet()) {
            with = with.header(header.getKey(), header.getValue());
        }
        return with;
    }

    private Response send(OutboundRequest request) {
        OutboundResponse response = http.send(request);
        return new Response(response.status(), response.contentType() == null ? "" : response.contentType(),
                response.body());
    }

    @Override
    public void close() {
        http.close();
    }

    // uri(…), form(…), encode(…) unchanged.
}
```

`YouTubeProperties` gains `@DefaultValue("false") boolean allowLoopback` as its last component; `application.yaml`
lists it under `youtube`; the test configuration, `FakeGoogleServer.properties()`, `SlowBodyDeadlineTest` and
`YouTubeOAuthE2eTest` turn it on. `BoundedBody` and `BoundedBodyTest` are deleted: nothing uses them.

- [ ] **Step 4: Update expectations** only for visible change 5: "Could not reach host" and "Interrupted while
  calling host" → "Could not reach Google at host (reason)"; "Google sent an oversized response" → "Google at host
  sent more than 2 MB" with kind `TOO_LARGE`. `GoogleOAuthClientTest`, `YouTubeApiClientTest` and `LoungeClientTest`
  must pass unchanged otherwise (Review Focus 4).

- [ ] **Step 5: Run the build.** `scripts/gradle.sh build`. Expected: green; `BoundedBody` gone.

- [ ] **Step 6: Commit** `refactor: fetch YouTube and Google through the guarded client`.

---

### Task 4: One JSON mapper

**Files:**
- Create: `src/main/java/dev/andre/homecontrol/config/Json.java`
- Modify: the sixteen classes outside `adapters` that build a mapper (`device/JsonFileDeviceRegistry`,
  `sources/jellyfin/{JellyfinClient,JellyfinStreams,JellyfinVlcExecutor}`, `sources/pinned/JsonFilePinStore`,
  `sources/sports/JsonFileSportsStore`, `sources/sports/thesportsdb/TheSportsDbClient`, `sources/tmdb/TmdbClient`,
  `sources/workflows/{WorkflowCodec,WorkflowJson}`, `sources/youtube/{LoungeClient,QuotaLedger,YouTubeHttp}`,
  `storage/{JsonFileSourceSettings,SecretStore,VersionedJsonFile}`)
- Test: `src/test/java/dev/andre/homecontrol/config/JsonTest.java`, `TmdbClientTest.refusesDeeplyNestedJson`

**Interfaces:**
- Produces: `dev.andre.homecontrol.config.Json.MAPPER` (a `tools.jackson.databind.json.JsonMapper`).

- [ ] **Step 1: Write the failing tests**

```java
// File: src/test/java/dev/andre/homecontrol/config/JsonTest.java
package dev.andre.homecontrol.config;

import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The one mapper refuses JSON built to exhaust the stack or the parser. */
class JsonTest {

    @Test
    void readsNestingUpTo64Levels() {
        assertThat(Json.MAPPER.readTree("[".repeat(64) + "]".repeat(64)).isArray()).isTrue();
    }

    @Test
    void refusesDeeperNesting() {
        String deep = "[".repeat(65) + "]".repeat(65);

        assertThatThrownBy(() -> Json.MAPPER.readTree(deep)).isInstanceOf(JacksonException.class);
    }

    @Test
    void refusesANumberLongerThan1000Characters() {
        String huge = "1".repeat(1_001);

        assertThatThrownBy(() -> Json.MAPPER.readTree(huge)).isInstanceOf(JacksonException.class);
    }
}
```

  `TmdbClientTest.refusesDeeplyNestedJson`: the fake answers 200 with a 100-level nested object → `TmdbException`
  "TMDB answered with something that is not JSON" (today TMDB parses it).

- [ ] **Step 2: Run them.** Expected: compilation fails (`Json`), and `refusesDeeplyNestedJson` fails once it compiles.

- [ ] **Step 3: Implement**

```java
// File: src/main/java/dev/andre/homecontrol/config/Json.java
package dev.andre.homecontrol.config;

import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one JSON mapper the application's own code uses: for its data files and for every content source's answers. It
 * refuses input nested deeper than 64 levels or with a number longer than 1,000 characters, so neither a hostile
 * server nor a damaged file can exhaust the stack or the parser. Device protocols in {@code adapters} keep their own.
 */
public final class Json {

    public static final JsonMapper MAPPER = JsonMapper.builder(JsonFactory.builder()
                    .streamReadConstraints(StreamReadConstraints.builder()
                            .maxNestingDepth(64).maxNumberLength(1_000).build())
                    .build())
            .build();

    private Json() {
    }
}
```

  Each of the sixteen classes keeps its field and its name but initializes it with `Json.MAPPER` (import
  `dev.andre.homecontrol.config.Json`). `WorkflowJson` derives its own: `Json.MAPPER.rebuild()
  .enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS).build()`. Check the Jackson 3 package names against
  `WorkflowJson`'s current imports before writing them.

- [ ] **Step 4: Run the build.** `scripts/gradle.sh build`. Expected: green; every store test still reads its old
  fixtures (nothing stored nests that deep).

- [ ] **Step 5: Commit** `refactor: read and write JSON with one hardened mapper`.

---

### Task 5: Two strict ArchUnit rules

**Files:**
- Modify: `src/test/java/dev/andre/homecontrol/ArchitectureTest.java`

- [ ] **Step 1: Add the rules**

```java
    @ArchTest
    static final ArchRule sourcesReachTheNetworkOnlyThroughTheGuardedClient = noClasses()
            .that().resideInAPackage("dev.andre.homecontrol.sources..")
            .and().resideOutsideOfPackage("dev.andre.homecontrol.sources.http..")
            .should().dependOnClassesThat().resideInAnyPackage("java.net.http..", "org.apache.hc..")
            .because("the guarded client pins addresses, bounds bodies and time, and keeps URLs out of errors; "
                    + "see ADR 0005");

    @ArchTest
    static final ArchRule onlyTheConfigurationBuildsAJsonMapper = noClasses()
            .that().resideOutsideOfPackages("dev.andre.homecontrol.config..", "dev.andre.homecontrol.adapters..")
            .should().callMethodWhere(target(name("builder")).and(target(owner(assignableTo(JsonMapper.class)))))
            .because("one mapper, hardened against hostile JSON, reads every data file and every source's answer; "
                    + "device protocols keep their own");
```

  Imports: `com.tngtech.archunit.core.domain.JavaCall.Predicates.target`,
  `com.tngtech.archunit.core.domain.properties.HasName.Predicates.name`,
  `com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner`,
  `com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo`, `tools.jackson.databind.json.JsonMapper`. If
  the generics of `target(…).and(…)` do not line up, express the same condition with one `DescribedPredicate` over
  `JavaCall<?>`.

- [ ] **Step 2: Watch each rule fail.** Put `JsonMapper.builder().build()` back into one source class and a
  `java.net.http.HttpClient` field into another, run `scripts/gradle.sh test --tests
  'dev.andre.homecontrol.ArchitectureTest'`, and see both rules fail; then restore both classes.

- [ ] **Step 3: Run the build.** `scripts/gradle.sh build`. Expected: green; the frozen store is unchanged.

- [ ] **Step 4: Commit** `test: keep sources on the guarded client and JSON on one mapper`.

---

### Task 6: The guides

**Files:**
- Modify: `docs/dev/architecture.md` (the `config` and `sources` rows, the rules table, the measures table),
  `docs/user/security.md`, `docs/dev/testing.md` if it names `BoundedBody`

- [ ] **Step 1: Write the edits**
  - Architecture guide:
    - the `sources` row: every source reaches the network through `GuardedHttpClient` (drop PR 1's "Jellyfin, TMDB,
      TheSportsDB and YouTube still use the JDK client" sentence);
    - the `config` row: `Json`, the one JSON mapper;
    - the rules table: the two new strict rules;
    - the measures table: "HTTP request loops in sources 6 → 1" and "`JsonMapper` builders 20 → 5 (`config.Json`
      and four adapter mappers)".
  - Security guide, a new section "What content sources may connect to":
    - any-local, link-local (cloud metadata) and multicast addresses are always refused, and the LAN is allowed;
    - loopback: Jellyfin always, and for the others only through `home-control.tmdb.allow-loopback`,
      `home-control.sports.the-sports-db.allow-loopback`, `home-control.youtube.allow-loopback`,
      `home-control.sports.calendar.allow-loopback` and `home-control.workflows.allow-loopback`
      (`HOME_CONTROL_WORKFLOWS_ALLOW_LOOPBACK`), all off by default;
    - every connection goes to the address that was checked, and redirects are refused except for calendars
      (checked hop by hop) and workflows (the same origin only).
- [ ] **Step 2: Run the checks.** `scripts/gradle.sh build`, `scripts/gradle.sh compileE2eJava`,
  `scripts/e2e.sh -Pe2eBrowsers=chromium`. Expected: green.
- [ ] **Step 3: Commit** `docs: describe what content sources may connect to`.
