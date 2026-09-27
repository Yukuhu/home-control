# Dynamic Dashboard Workflows Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a household configure JSON-to-URL workflows that provide either a saved Dashboard tile or a generated list of tiles and resolve fresh media URLs when Play starts Chromecast.

**Architecture:** A `sources.workflows` module owns validated definitions, encrypted persistence, bounded HTTP fetching, JSON selection, URL templates, catalogs, and setup forms. Dashboard items carry an opaque workflow reference; a pure Cast strategy previews it and a dedicated route executor resolves its URL only on Play. Existing rails, login, device selection, and Cast transport remain the integration points.

**Tech Stack:** Java 25, Spring Boot 4.1.1, Jackson 3 (`tools.jackson`), Gradle, Thymeleaf, vanilla JavaScript, JUnit/AssertJ/Mockito and the existing Playwright source set. Add `org.apache.httpcomponents.client5:httpclient5` using the Spring Boot BOM version, currently 5.6.4, for a connection-time validating DNS resolver. The cached BOM and jar API were inspected locally; do not copy a second pinned version into Gradle.

**Spec:** [Accepted workflow design](../specs/2026-09-22-dynamic-workflows-design.md).

## Global Constraints

- One HTTP GET returning JSON, then one Cast action; URL B is a direct media address. No scripts, graph engine, pagination, extra request steps, or media proxy.
- Both single and generated tile modes; source ID `workflows`, one rail per workflow, default refresh 15 minutes.
- Dashboard preview and `ContentSource.item()` make no workflow HTTP requests; Setup Test explicitly fetches without casting.
- One encrypted `SecretStore` value per definition, named `workflow.<workflowId>`; IDs are `w-` plus 12 random hexadecimal characters. No plaintext manifest.
- At most 50 workflows, 16,384 characters per serialized definition, 32 mappings, 16 headers, 200 catalog entries, and a 2 MiB JSON response.
- Pointer length 512 characters, JSON nesting 64 levels, configured and expanded URLs 8,192 characters, names/titles 120 characters, subtitles 240 characters, MIME type 100 ASCII characters.
- Connection timeout 5 seconds, total fetch/body deadline 15 seconds, at most three same-origin redirects within that deadline, four active workflow fetches shared by refresh/test/play.
- Default HTTP(S) policy permits private LAN addresses but rejects unspecified, loopback, link-local, and multicast destinations; an explicit server setting allows loopback for local feeds.
- Mask stored request URLs, templates, header values, sensitive variables, and literal path/query values in server responses. Never log raw JSON, resolved URLs, tokens, or upstream exception excerpts.
- `home-control.workflows.enabled` defaults to true; disabling retains definitions/login but removes workflow UI, source, tests, and execution.
- Cast uses the selected device and Default Media Receiver `CC1AD845`; no app-link, DLNA, or Bluetooth fallback and no automatic workflow/action retry.

## Review Focus

- An old catalog response finishes after an edit/delete: reject its publication and never restore an obsolete tile or definition (Task 5).
- A slow response dribbles bytes continuously: the total deadline expires, the socket closes, and admission capacity is eventually returned (Task 3).
- Hostname resolution changes between policy validation and connection: the transport cannot connect to a second unvalidated answer, including IPv4-mapped IPv6 cases (Task 3).
- An editor posts a sparse or huge mapping index: reject without allocating a huge list, leaking submitted secrets, or losing ordinary draft rows (Task 6).
- Deleting the final workflow with another source connected: preserve that source's secrets and login; stale edits cannot resurrect the deleted workflow (Task 4).

---

## Starting state and execution order

On 2026-09-22, `git fetch origin main` and `git rebase origin/main` completed.
Local `main` already contained remote `4a2ca31`; `1c93f1d` is the design commit.
The untracked `.codex/` directory belongs to the workspace and is not part of this
feature. Before execution check branch/status again and follow the workspace
isolation skill; do not reset, clean, stage, or overwrite unrelated files.

Tasks 3 and 4 can proceed independently after Tasks 1–2. Task 5 joins them; Task 6
uses the resulting module; Task 7 checks the whole feature. Keep commits scoped
to their task. Commands below assume a local JDK 25 and use `./gradlew`; the
tracked `scripts/gradle.sh` and `scripts/e2e.sh` are alternatives when their Docker
runtime is available. Do not install or run browsers just to check this plan.

## File structure

All Java paths below are relative to `src/main/java/dev/andre/homecontrol/` or,
for unit tests, `src/test/java/dev/andre/homecontrol/`. Browser paths are listed
in full. Each task's Files list identifies its exact ownership.

| Unit | Responsibility |
|---|---|
| `sources/workflows/WorkflowDraft.java`, `WorkflowDefinition.java`, `WorkflowCodec.java`, `WorkflowValidator.java`, `WorkflowException.java` | Immutable configuration, serialization, structural validation, safe stage errors |
| `sources/workflows/WorkflowJson.java`, `WorkflowTemplate.java` | JSON pointers, stable identities, scalar extraction, encoded templates and masked renderings |
| `sources/workflows/WorkflowUrlPolicy.java`, `WorkflowHttpClient.java` | DNS-aware destination policy, bounded GET and shared request admission |
| `sources/workflows/WorkflowStore.java` | Encrypted lifecycle, revision checks, consistent mutation/dispatch and invalid-definition reports |
| `sources/workflows/WorkflowRunner.java`, `WorkflowCatalogs.java`, `WorkflowContentSource.java`, `WorkflowCastRouteExecutor.java` | Fresh execution, immutable catalog publication, Dashboard source and explicit Cast execution |
| `sources/workflows/WorkflowProperties.java`, `WorkflowConfiguration.java` | Conditional module configuration, resource ownership and lifecycle |
| `sources/workflows/WorkflowForm.java`, `WorkflowSetupController.java`, `WorkflowSetupAdvice.java`, `WorkflowTestService.java` | Masked editor, setup summary and safe test results |
| `core/playback/WorkflowCastStrategy.java` | Pure route selection by Cast capability |
| `templates/workflow-editor.html`, `templates/fragments/workflows-setup.html`, `static/js/workflows.js` | Ordered editor, setup list, accessible mapping/header row controls |

Resource paths in the last row are relative to `src/main/resources/`. Modify
existing sealed references/routes, planner explanations, keys, playback dispatch,
module registration, `templates/setup.html`, `static/app.css`, application
properties, and README only where the tasks specify.

### Task 1: Validated workflow definitions and a safe serialization boundary

**Files**

- Create: `sources/workflows/WorkflowDraft.java`, `WorkflowDefinition.java`, `WorkflowCodec.java`, `WorkflowValidator.java`, `WorkflowException.java`.
- Test: `sources/workflows/WorkflowDefinitionTest.java`, `WorkflowFixtures.java`.

**Interfaces**

```java
public record WorkflowDefinition(int schemaVersion, String id, long revision,
                                 WorkflowDraft draft) {}
public record WorkflowDraft(String name, boolean enabled, Mode mode, ContentKind kind,
                            Fetch fetch, Listing listing, Tile tile,
                            List<Variable> variables, Cast cast) {
    public enum Mode { SINGLE, GENERATED }
    public enum Scope { ROOT, ENTRY }
    public record Header(String name, String value) {}
    public record Fetch(String url, List<Header> headers) {}
    public record Listing(String arrayPointer, String idPointer, String titlePointer,
                          String subtitlePointer, String artworkPointer) {}
    public record Tile(String title, String subtitle, String artwork) {}
    public record Variable(String name, Scope scope, String pointer, boolean sensitive) {}
    public record Cast(String template, String mimeType) {}
}
```

All records containing secrets override `toString()` with identifiers/type names
only. Defensively copy lists. `WorkflowCodec` supplies
`encode(WorkflowDefinition): String` and `decode(String): WorkflowDefinition`.
Static `WorkflowValidator.validate(WorkflowDraft): void` performs structural validation
without DNS or HTTP. The codec validates schema `1`, bounded ID, positive revision,
serialized length and the draft. `WorkflowException` extends `RuntimeException`,
has a safe `Stage` enum (`FETCH`, `PARSE`, `SELECT`, `MAP`, `BUILD`, `WORKFLOW`),
and constructs messages without retaining an unsafe cause.

- [ ] **Step 1: Create the failing codec/validation test and fixture.** Define the
  following `single` helper in a public `WorkflowFixtures` class, with static imports
  of the nested draft types; put the test method in `WorkflowDefinitionTest`:

  ```java
  public static WorkflowDraft single(URI source) {
      return new WorkflowDraft("News", true, Mode.SINGLE, ContentKind.VIDEO,
          new Fetch(source.toString(), List.of(new Header("Authorization", "Bearer saved-secret"))),
          null, new Tile("News", null, null),
          List.of(new Variable("A", Scope.ROOT, "/id", false),
                  new Variable("C", Scope.ROOT, "/token", true)),
          new Cast("https://media.example/play?id={A}&token={C}", "video/mp4"));
  }
  @Test void roundTripsWithoutPrintingCredentials() {
      var definition = new WorkflowDefinition(1, "w-0123456789ab", 1,
          WorkflowFixtures.single(URI.create("https://api.example/catalog?key=saved-secret")));
      var codec = new WorkflowCodec();
      assertThat(codec.decode(codec.encode(definition))).isEqualTo(definition);
      assertThat(definition.toString()).doesNotContain("saved-secret", "api.example", "token=");
      assertThat(definition.draft().fetch().toString()).doesNotContain("saved-secret");
  }
  ```

  Add parameterized cases for mode-specific required fields, invalid/duplicate
  variable names, ENTRY scope in single mode, blank names/titles, all length/count
  boundaries, forbidden URL/template grammar, malformed pointer escape sequences,
  unsupported schema/revision, and header CR/LF or case-insensitive duplicates.

- [ ] **Step 2: Run `./gradlew test --tests '*WorkflowDefinitionTest'`.** Confirm
  failure refers to the missing workflow definitions, not toolchain/environment.

- [ ] **Step 3: Implement the records, validator, and codec.** Jackson handles
  the DTO shape; inspect `schemaVersion` before binding and never wrap its parser
  exception with a raw cause. Enforce max serialized size on both encode/decode.
  Validate templates by tokenizing placeholders and parsing a URI with safe
  placeholder sentinels, never by interpolating arbitrary values. Defer actual
  expansion to Task 2. Validate MIME as ASCII `type/subtype` without parameters;
  accept only `VIDEO` and `TRACK` as the workflow kind.

  ```java
  @Override public String toString() {
      return "WorkflowDefinition[id=" + id + ", revision=" + revision + "]";
  }
  ```

  Deny `Host`, `Cookie`, `Connection`, `Content-Length`, `Transfer-Encoding`,
  `TE`, `Trailer`, `Upgrade`, `Keep-Alive`, `Expect`, `Accept-Encoding`, proxy
  headers, and invalid header-name tokens. Response bodies are handled as JSON
  bytes; callers cannot request an unsupported compressed body. Reject URL
  userinfo/fragments, unknown placeholders, placeholders in authority/query keys,
  and literal or encoded dot-only path segments. Optional metadata pointers use
  `null` for absent; the empty string continues to mean the document root.

- [ ] **Step 4: Re-run the Task 1 test.** Confirm the maximum valid definition
  round-trips, one character over fails, and every secret-bearing DTO has a safe
  string representation.
- [ ] **Step 5: Commit the Task 1 files** with message
  `feat: define validated dashboard workflows`.

### Task 2: JSON mappings, stable entry identities, and URL templates

**Files**

- Create: `sources/workflows/WorkflowJson.java`, `WorkflowTemplate.java`.
- Modify: `sources/workflows/WorkflowValidator.java` to delegate template grammar
  checks to `WorkflowTemplate` instead of maintaining a second tokenizer.
- Test: `sources/workflows/WorkflowJsonTest.java`, `WorkflowTemplateTest.java`.
- Extend: `sources/workflows/WorkflowFixtures.java` with the exact two-channel
  response from the spec, plus a reordered response with a new token.

**Interfaces**

`WorkflowJson.parse(byte[]): JsonNode`,
`entries(WorkflowDraft, JsonNode): List<Entry>`,
`values(List<Variable>, JsonNode root, JsonNode entry): Map<String,Value>`, and
`stableKey(JsonNode id): String` are pure static methods. `Entry` contains
`(String key, String title, String subtitle, URI artwork, JsonNode node)`;
`Value` contains `(String text, boolean sensitive)` and always prints masked text.
Entries are transient; only Task 5's metadata records enter catalog snapshots.

`new WorkflowTemplate(String, Set<String> variableNames)` validates/tokenizes the
template. `expand(Map<String,Value>): URI` produces the real media URI;
`preview(Map<String,Value>): String` renders the masked display form directly
from the token sequence. `WorkflowTemplate.encodeComponent(String): String` is a
package-visible pure helper. These functions have no DNS or HTTP side effects.

- [ ] **Step 1: Write the encoding regression.**

  ```java
  @Test void valuesCannotIntroduceQueryParametersOrPathSegments() {
      var template = new WorkflowTemplate("https://media.example/{A}?token={C}", Set.of("A", "C"));
      var values = Map.of("A", new WorkflowJson.Value("a/b & ü", false),
                          "C", new WorkflowJson.Value("x&admin=true%", true));
      assertThat(template.expand(values).toASCIIString()).isEqualTo(
          "https://media.example/a%2Fb%20%26%20%C3%BC?token=x%26admin%3Dtrue%25");
      assertThat(template.preview(values)).doesNotContain("admin", "true%").contains("•••");
  }
  ```

  Add tests for `/a~1b/~0token`, numeric array indexing, root/entry combinations,
  scalar booleans/numbers, missing/null/container fields, repeated placeholders,
  raw pre-encoded values, `.`/`..` path output, unresolved variables, masked literal
  path/query pieces, and the 8,192-character expanded limit. Assert generated ID
  stability across reorder/token/title changes and distinct keys for string
  `"1"` versus integer `1`. Duplicate IDs, 201 entries, malformed required titles,
  invalid optional artwork, and excessive JSON depth each get an explicit case.

- [ ] **Step 2: Run `./gradlew test --tests '*WorkflowJsonTest' --tests '*WorkflowTemplateTest'`**
  and observe the expected missing-behavior failures.

- [ ] **Step 3: Implement JSON selection and template rendering.** Configure a
  dedicated Jackson factory with depth `64` and reject trailing JSON documents.
  Select pointers with the JSON tree API after syntax validation. Hash UTF-8
  `s:` plus a string ID or `n:` plus the canonical integral decimal ID using
  SHA-256; retain all 64 lowercase hexadecimal characters. Match duplicate keys
  before building any catalog. Single mode produces key `single` with configured
  display metadata. For template tokens encode every byte except the URI
  unreserved set; do not use form encoding (`+` for spaces).

  ```java
  static String encodeComponent(String value) {
      StringBuilder encoded = new StringBuilder();
      final String hex = "0123456789ABCDEF";
      for (byte octet : value.getBytes(StandardCharsets.UTF_8)) {
          int b = octet & 255;
          boolean safe = b >= 'a' && b <= 'z' || b >= 'A' && b <= 'Z'
              || b >= '0' && b <= '9' || b == '-' || b == '.' || b == '_' || b == '~';
          if (safe) encoded.append((char) b);
          else encoded.append('%').append(hex.charAt(b >>> 4)).append(hex.charAt(b & 15));
      }
      return encoded.toString();
  }
  ```

  Check resulting path segments for `.`/`..` after exactly one decoding pass.
  Mask literals by parsed component boundaries, not replacement of substrings
  after expansion. Preview preserves scheme/host, delimiters, query names and
  explicitly public variable values; all other value pieces become `•••`.
  Omit unsupported artwork instead of loading it. Never attach the original
  JsonNode or real expanded URI to a test-result DTO.

- [ ] **Step 4: Re-run both test classes and Task 1's validator tests.**
- [ ] **Step 5: Commit the Task 2 files** as
  `feat: map workflow JSON into encoded media URLs`.

### Task 3: Bounded fetching with connection-time address validation

**Files**

- Modify: `build.gradle.kts`.
- Create: `sources/workflows/WorkflowUrlPolicy.java`, `WorkflowHttpClient.java`, `WorkflowProperties.java`.
- Test: `sources/workflows/WorkflowUrlPolicyTest.java`, `WorkflowHttpClientTest.java`, `FakeWorkflowServer.java`.

**Interfaces**

`WorkflowUrlPolicy` accepts `(boolean allowLoopback, HostResolver resolver)`;
`HostResolver.resolve(String): InetAddress[]` may throw `UnknownHostException`.
Expose `parse(String): URI`, `addresses(String): InetAddress[]` (throws
`UnknownHostException`), and
`sameOrigin(URI, URI): boolean`. `addresses` resolves once, validates every answer,
and returns only that checked array. `WorkflowHttpClient implements AutoCloseable`
with constructor `(WorkflowProperties, WorkflowUrlPolicy)` and exposes
`fetch(WorkflowDraft.Fetch): byte[]` and `checkMedia(URI): void`.
`checkMedia` performs bounded address validation without making an HTTP request.

`WorkflowProperties` binds `enabled=true`, `allowLoopback=false`,
`connectTimeout=5s`, `requestTimeout=15s`, `maxConcurrentFetches=4`,
`maxBytes=2097152`, and `maxRedirects=3` under `home-control.workflows`.
Validate positive finite timeout/count/size values; structural definition limits
remain constants in the validator. Tests use constructor overrides for short
deadlines and loopback, never loosen production defaults.

- [ ] **Step 1: Build a counting fixture server using `HttpServer`.** Give it
  `url(String): URI`, `respond(String,int,String): void`,
  `redirect(String,int,String): void`, `count(String): int`, and `close()`.
  Use a managed virtual-thread executor and close it with the server. Add latch
  hooks for headers-delivered/body-blocked and slow-body cases. Copy the request
  recording pattern from `sources/sports/calendar/FakeCalendarServer.java`.

  ```java
  @Test void aCrossOriginRedirectDoesNotForwardAuthorization() throws Exception {
      try (var first = new FakeWorkflowServer(); var other = new FakeWorkflowServer()) {
          first.redirect("/feed", 302, other.url("/stolen").toString());
          var request = new WorkflowDraft.Fetch(first.url("/feed").toString(),
              List.of(new WorkflowDraft.Header("Authorization", "Bearer secret-marker")));
          assertThatThrownBy(() -> client.fetch(request))
              .isInstanceOf(WorkflowException.class).hasMessageContaining("Fetch JSON")
              .hasMessageNotContaining("secret-marker");
          assertThat(other.count("/stolen")).isZero();
      }
  }
  ```

  Here `client` is constructed in test setup from loopback-allowing policy and
  short properties. Test mixed safe/blocked DNS answers, IPv6/mapped addresses,
  invalid schemes/userinfo/fragments, redirect limit, non-200 statuses, malformed
  Location, 2 MiB+1 body, slow headers, slow body, interruption, four concurrent
  requests/fifth rejected, and capacity recovery after failures. Use a hostname
  such as `fixture.invalid` resolving only through the injected resolver to prove
  the socket uses its checked result. A resolver alternating allowed/blocked
  addresses must never cause a second unchecked lookup on the same connection.

- [ ] **Step 2: Run `./gradlew test --tests '*WorkflowUrlPolicyTest' --tests '*WorkflowHttpClientTest'`**
  and record expected failures.

- [ ] **Step 3: Add the BOM-managed client dependency and implement the transport.**

  ```kotlin
  implementation("org.apache.httpcomponents.client5:httpclient5")
  ```

  ```java
  DnsResolver dns = new DnsResolver() {
      public InetAddress[] resolve(String host) throws UnknownHostException {
          return policy.addresses(host);
      }
      public String resolveCanonicalHostname(String host) {
          return host;
      }
  };
  var manager = PoolingHttpClientConnectionManagerBuilder.create()
      .setDnsResolver(dns)
      .setDefaultConnectionConfig(ConnectionConfig.custom()
          .setConnectTimeout(Timeout.ofMilliseconds(properties.connectTimeout().toMillis()))
          .build())
      .build();
  var http = HttpClients.custom().setConnectionManager(manager)
      .disableAutomaticRetries().disableRedirectHandling().disableCookieManagement()
      .disableAuthCaching().disableContentCompression()
      .setConnectionReuseStrategy((request, response, context) -> false)
      .build();
  ```

  Keep the original URI hostname for TLS verification and Host/SNI. Check numeric
  IP literals explicitly because a transport may bypass DNS for them. Set no
  system proxy or credentials provider. Perform redirects manually after closing
  the response; compare normalized scheme, host, and effective port, reject every
  cross-origin hop, and count a maximum of three. Reject response compression
  rather than passing a compressed body into the JSON parser.

  Admission uses a semaphore and immediate `tryAcquire()`. A worker owns the
  permit until its `finally`, even if DNS outlives the caller's deadline. The
  caller waits only for the remaining total deadline, cancels the worker and
  active `HttpGet` on timeout, and maps failures to sanitized stage errors.
  Keep the request cancellation handle active while reading/closing the body;
  socket idle timeouts alone do not satisfy the total deadline. Read at most
  `maxBytes + 1`, then reject overflow; cap response draining by aborting failed
  requests. Check interruption/deadline after DNS before connecting. `checkMedia`
  shares bounded worker admission so slow DNS cannot create unlimited threads.
  Client/executor/connection-manager resources close with the module.

- [ ] **Step 4: Run both test classes.** Prove deadline behavior with latches
  and generous test timeout margins; do not use arbitrary sleeps to synchronize
  concurrency assertions. Include a local HTTPS fixture rejecting an untrusted
  certificate to ensure DNS changes never disable TLS verification.
- [ ] **Step 5: Commit Task 3 files** as
  `feat: fetch workflow JSON with bounded validated connections`.

### Task 4: Encrypted workflow lifecycle and revision-safe dispatch

**Files**

- Create: `sources/workflows/WorkflowStore.java`.
- Test: `sources/workflows/WorkflowStoreTest.java`.

**Interfaces**

Constructor dependencies: `SecretStore`, `LoginService`, `WorkflowCodec`,
`ApplicationEventPublisher`, `SecureRandom`. Expose:

```java
List<WorkflowDefinition> all();
Map<String,String> problems(); // workflow ID -> safe load failure
Optional<WorkflowDefinition> find(String id);
WorkflowDefinition create(WorkflowDraft draft, String password, String confirmation,
                          HttpServletRequest request);
WorkflowDefinition update(String id, long expectedRevision, WorkflowDraft draft,
                          HttpServletRequest request);
void setEnabled(String id, long expectedRevision, boolean enabled, HttpServletRequest request);
void remove(String id, long expectedRevision, HttpServletRequest request);
void removeInvalid(String id, HttpServletRequest request);
void ifCurrent(String id, long revision, Runnable operation);
```

`ifCurrent` verifies the definition exists, is enabled and has the expected
revision, then runs the operation under that workflow's mutation lock. Task 5
uses it for catalog publication and final Cast dispatch. Invalid stored values
appear only in `problems()` and can be removed through the editor with an
explicit corrupted-definition removal action; they are never overwritten by an
ordinary create/update. This recovery action authenticates and removes exactly
the displayed secret key, without decoding the invalid payload.

- [ ] **Step 1: Write persistence tests with a real temporary SecretStore.**
  Reuse `storage/SecretStoreTest`'s key-source construction and
  `security/LoginServiceTest`'s service setup. Cover first-save password validation,
  authenticated edits, no network on save, restart, bad/future schema isolation,
  max workflow count, random-ID collision retry, and optimistic revisions.

  ```java
  @Test void deletingTheLastWorkflowKeepsOtherSourcesAndLogin() {
      var saved = workflows.create(WorkflowFixtures.single(URI.create("https://api.example/feed")),
          "long-enough-password", "long-enough-password", request);
      secrets.putSecrets(Map.of("jellyfin.token", "other-source-token"));
      workflows.remove(saved.id(), saved.revision(), request);
      assertThat(workflows.find(saved.id())).isEmpty();
      assertThat(secrets.secret("jellyfin.token")).contains("other-source-token");
      assertThat(login.loginRequired()).isTrue();
      assertThatThrownBy(() -> workflows.update(saved.id(), saved.revision(), saved.draft(), request))
          .hasMessageContaining("changed");
  }
  ```

  In this class's setup define `workflows`, `secrets`, `login`, and a
  `MockHttpServletRequest request`; first-save uses the real login/session flow.
  Assert saved file content lacks URL/header/template secret markers. Capture
  events and assert unsuccessful writes do not publish `ContentChangedEvent`.

- [ ] **Step 2: Run `./gradlew test --tests '*WorkflowStoreTest'`** and confirm red.

- [ ] **Step 3: Implement storage over the existing secret API.**

  ```java
  static String secretName(String id) {
      if (!id.matches("w-[0-9a-f]{12}")) {
          throw new IllegalArgumentException("Unknown workflow");
      }
      return "workflow." + id;
  }
  ```

  Read only keys with the workflow prefix, decode valid definitions into an
  immutable local snapshot, and report corrupt entries separately. Initialize
  that snapshot on module creation so `all()/find()` never do filesystem I/O.
  Mutations serialize capacity/identity allocation and use bounded per-ID lock
  stripes for edit/delete/`ifCurrent`; retain no unbounded lock map. Validate and
  encode before `login.storeSecrets`; advance in-memory state only after the
  atomic write succeeds. Use `login.isAuthenticated` before every mutation
  except first create, where `storeSecrets` establishes the login. Hold no
  mutation lock across upstream fetches. Publish content events after releasing
  locks and after installing the saved revision. Source preference checks stay
  in Task 5, so there is no circular source/store dependency.

- [ ] **Step 4: Re-run Task 4 tests plus `*SecretStoreTest` and `*LoginServiceTest`.**
  Add a latch test proving an edit before `ifCurrent` prevents its callback and
  that an edit waits for a callback already authorized at final dispatch.
- [ ] **Step 5: Commit Task 4 files** as
  `feat: persist encrypted workflows with revision checks`.

### Task 5: Dashboard catalogs and deferred Cast execution

**Files**

- Create: `sources/workflows/WorkflowRunner.java`, `WorkflowCatalogs.java`, `WorkflowContentSource.java`, `WorkflowCastRouteExecutor.java`, `WorkflowConfiguration.java`, `core/playback/WorkflowCastStrategy.java`.
- Modify: `core/playback/PlayableRef.java`, `Route.java`, `RouteKeys.java`, `PlaybackPlanner.java`, `playback/PlaybackService.java`, `HomeControlConfiguration.java`, `src/main/resources/application.yaml`, `src/test/resources/application.yaml`.
- Test: `sources/workflows/WorkflowRunnerTest.java`, `WorkflowContentSourceTest.java`, `WorkflowCastRouteExecutorTest.java`, `WorkflowModuleSwitchTest.java`, `core/playback/WorkflowCastStrategyTest.java`; extend `playback/PlaybackServiceTest.java`, `web/ContentPlayPreviewTest.java`.

**Interfaces**

Add `PlayableRef.WorkflowCast(String workflowId,long revision,String entryKey)`
and matching `Route.WorkflowCast`. `WorkflowRunner(WorkflowHttpClient)` uses
the pure Task 2 helpers. It exposes `catalog(WorkflowDefinition): List<CatalogEntry>`
and `resolve(WorkflowDefinition,String entryKey): ResolvedMedia`. Nested
`CatalogEntry(String key,String title,String subtitle,URI artwork)` is public
metadata; `ResolvedMedia(URI url,String mimeType,String title)` has a fully
redacted `toString`. Each call performs exactly one upstream fetch (plus allowed
redirects); catalog never evaluates media mappings.

`WorkflowCatalogs` exposes `begin(String workflowId): long`,
`publish(WorkflowDefinition,long generation,List<CatalogEntry>): boolean`,
`find(String itemId): Optional<ContentItem>`, and `invalidate(): void`. Begin a
generation before each generated fetch; publishing succeeds only if it remains
the latest generation and passes `store.ifCurrent`. Invalidation clears snapshots
and retires in-flight generations. Publishing produces immutable revision-tagged items. Generated
item IDs are `<workflowId>.<64-hex-entryKey>`, while single item IDs are the
workflow ID. Source `item()` reads local single metadata or catalogs only.

- [ ] **Step 1: Write the pure route test and no-fetch preview regression.**

  ```java
  @Test void workflowOnlyRoutesToCastEvenOnMergedDevices() {
      var item = new ContentItem("w-0123456789ab", "workflows", ContentKind.VIDEO,
          "News", null, null, List.of(new PlayableRef.WorkflowCast("w-0123456789ab", 1, "single")));
      var strategy = new WorkflowCastStrategy();
      assertThat(strategy.route(item, Set.of(Capability.APP_LINK))).isEmpty();
      var route = strategy.route(item, Set.of(Capability.APP_LINK, Capability.CAST_RECEIVER)).orElseThrow();
      assertThat(RouteKeys.key(route)).isEqualTo("workflow-cast");
      assertThat(route.describe()).isEqualTo("Cast with the Default Media Receiver");
  }
  ```

  With a real workflow source and mocked runner assert single `rail()`, `item()`,
  playback `plan()` and `preview()` never invoke `runner.resolve/catalog`. For
  generated mode only `rail()` calls `catalog`; opening the item reuses metadata.
  Add MockMvc preview tests asserting route key and no URL/token in JSON. In
  executor tests capture `Action.CastLoad` and check receiver, contentUrl,
  contentId, configured MIME, autoplay and title. Change the fixture token between
  catalog and Play and assert only the fresh token reaches the device.

- [ ] **Step 2: Run focused tests for `*Workflow*` and `*ContentPlayPreviewTest`**
  and confirm failures identify missing routing/source behavior.

- [ ] **Step 3: Implement pure routing and execution delegation.**

  ```java
  @Override public Optional<Route> route(ContentItem item, Set<Capability> capabilities) {
      if (!capabilities.contains(Capability.CAST_RECEIVER)) return Optional.empty();
      return item.playables().stream().filter(PlayableRef.WorkflowCast.class::isInstance)
          .map(PlayableRef.WorkflowCast.class::cast).findFirst()
          .map(ref -> new Route.WorkflowCast(ref.workflowId(), ref.revision(), ref.entryKey()));
  }
  ```

  Register the strategy beside existing Cast strategies. Extend every exhaustive
  switch (search for `case PlayableRef.` and `case Route.`), including planner
  explanations, route keys and `PlaybackService.execute()`. The latter delegates
  to `RouteExecutor` as Jellyfin/YouTube routes do; no `PlayableResolver` is added.
  Keep non-workflow route order and behavior unchanged.

  The executor validates current workflow/revision, source preference and Cast
  capability, calls `runner.resolve`, then uses `store.ifCurrent` to send:

  ```java
  var stream = new PlayableRef.StreamUrl(resolved.url(), resolved.mimeType());
  var action = new Action.CastLoad(CastLoads.DEFAULT_MEDIA_RECEIVER,
      CastLoads.defaultMediaReceiver(stream, resolved.title()));
  store.ifCurrent(route.workflowId(), route.revision(),
      () -> devices.execute(device.id(), action));
  ```

  Translate workflow runtime failures into `ActionFailedException` using only
  their safe message so `PlayAttempt.Failed` catches them. Preserve device-offline
  and unsupported-capability exception types. Validate the final media address
  without GET/HEADing it. Never feed workflow refs through ordinary StreamUrl
  routing, which would offer other devices.

- [ ] **Step 4: Implement source, snapshot publication, configuration, and races.**
  Subscribe to workflow content changes with an ordered listener that invalidates
  catalogs and calls `RailCache.invalidateSource("workflows")` before the normal
  rail content-change listener reconciles/refreshes. Resolve RailCache through an
  `ObjectProvider` inside the listener to avoid a source/cache construction cycle.
  Reuse the existing stale row/error/SSE behavior. A completed old
  refresh must pass `ifCurrent` before publishing; returning old results as a
  successful rail load is also prohibited. On revision mismatch fail the load
  and let the scheduled/content-change refresh reload current metadata. Before
  replacing a catalog, check that the definition/revision remains enabled.

  Register conditional beans with `@ConditionalOnProperty` and ensure the
  HTTP client's `close()` is called at shutdown. Bind the enabled setting using
  the repository's `HOME_CONTROL_WORKFLOWS_ENABLED` convention and
  `HOME_CONTROL_WORKFLOWS_ALLOW_LOOPBACK`; production loopback defaults false.
  Tests enable loopback explicitly only in the fixture contexts.

  Test old-refresh-after-edit/delete, out-of-order refreshes at the same revision,
  edit-during-fetch, duplicate/disappeared entries, disabled source/workflow, wrong
  device, failed fetch and stale snapshot. Add a monotonically increasing load
  generation per workflow so a later-started load wins within one revision. Use
  latches to control ordering and verify zero device commands on every failure.

- [ ] **Step 5: Run `./gradlew test --tests '*Workflow*' --tests '*PlaybackServiceTest' --tests '*ContentPlayPreviewTest' --tests '*RailCacheTest'`.**
- [ ] **Step 6: Commit Task 5 files** as
  `feat: show workflow tiles and resolve URLs when casting`.

### Task 6: Setup editor, masked credentials, and explicit Test action

**Files**

- Create: `sources/workflows/WorkflowForm.java`, `WorkflowSetupController.java`, `WorkflowSetupAdvice.java`, `WorkflowTestService.java`.
- Create: `src/main/resources/templates/workflow-editor.html`, `src/main/resources/templates/fragments/workflows-setup.html`, `src/main/resources/static/js/workflows.js`.
- Modify: `src/main/resources/templates/setup.html`, `src/main/resources/static/app.css`.
- Test: `sources/workflows/WorkflowSetupControllerTest.java`, `WorkflowTestServiceTest.java`; extend `WorkflowModuleSwitchTest.java`, `web/StaticAssetsTest.java`.

**Interfaces**

`WorkflowForm` is a mutable MVC binding type, distinct from persisted definitions.
Its public fields/properties mirror name/enabled/mode/kind, tile metadata, listing
pointers, and indexed mapping rows `variables[i].name/scope/pointer/sensitive`.
Use `urlMode/templateMode/headersMode` enums `KEEP/REPLACE`, URL/template strings,
and indexed header rows `headers[i].name/value`. Include `expectedRevision`, MIME,
and first-save `loginPassword/loginPasswordConfirmation`. No client may choose
schema version or change workflow ID/revision. `toDraft(WorkflowDefinition saved)`
merges explicit Keep choices with the saved definition; `clearSecrets()` blanks
all URL/template/header/password values before rendering an error.

`WorkflowTestService.test(String id,long revision,HttpServletRequest): Result`
loads a saved definition after authentication and revision checks. Result carries
only `List<StageView> stages`, `int totalEntries`, `List<SampleView> samples`, and
`List<String> warnings`. Samples contain public display metadata, masked variable
strings and a masked URL, at most five entries. These DTOs never contain a
WorkflowDefinition, JsonNode, real URI, HTTP request, or headers.

| Method/path | Result |
|---|---|
| GET `/setup/workflows/new` | Empty editor; first-save password if needed |
| GET `/setup/workflows/{id}` | Safe editor with Keep controls and current revision |
| POST `/setup/workflows` | Create and redirect to saved editor |
| POST `/setup/workflows/{id}` | Update or return editor with field errors |
| POST `/setup/workflows/{id}/test` | Safe test panel, no device action |
| POST `/setup/workflows/{id}/enabled` | Expected revision + enabled value, then redirect |
| POST `/setup/workflows/{id}/remove` | Expected revision, then redirect to Setup |
| POST `/setup/workflows/{id}/remove-invalid` | Authenticated removal of an unreadable saved definition |

- [ ] **Step 1: Write controller/service tests.** Follow
  `TmdbSetupControllerTest` for MVC setup, `YouTubeSetupController` for preserving
  nonsecret drafts, and `PinnedModuleSwitchTest` for absent beans/routes.

  ```java
  @Test void openingASavedEditorDoesNotRenderStoredSecrets() throws Exception {
      given(store.find("w-0123456789ab")).willReturn(Optional.of(savedDefinition));
      String html = mvc.perform(get("/setup/workflows/w-0123456789ab"))
          .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
      assertThat(html).doesNotContain("saved-secret", "api.example", "token={C}");
      assertThat(html).contains("Keep saved URL", "Keep saved template");
      verifyNoInteractions(testService);
  }
  ```

  Supply `savedDefinition` from the Task 1 fixture and use a logged-in mock
  session or the existing controller-slice security setup. Test explicit
  Keep/Replace semantics, failed first-save passwords, row order retention,
  password/header scrubbing on every error, stale/missing IDs, test authentication
  before HTTP, five-sample cap and total count, malformed pointers/JSON errors,
  and disabled routes. Post `variables[999999].name` and `variables[32].name` and
  assert safe rejection; do the equivalent for `headers[16].value`.

- [ ] **Step 2: Run `./gradlew test --tests '*WorkflowSetupControllerTest' --tests '*WorkflowTestServiceTest' --tests '*WorkflowModuleSwitchTest'`.**

- [ ] **Step 3: Implement bounded form binding and sanitized editor models.**

  ```java
  @InitBinder("workflowForm")
  void bindWorkflow(WebDataBinder binder) {
      binder.setAutoGrowCollectionLimit(32);
      binder.setAllowedFields("name", "enabled", "mode", "kind", "title", "subtitle", "artwork",
          "arrayPointer", "idPointer", "titlePointer", "subtitlePointer", "artworkPointer",
          "variables[*].name", "variables[*].scope", "variables[*].pointer", "variables[*].sensitive",
          "urlMode", "url", "templateMode", "template", "mimeType", "headersMode",
          "headers[*].name", "headers[*].value", "expectedRevision", "loginPassword",
          "loginPasswordConfirmation");
  }
  ```

  Independently reject header count over 16, sparse rows and oversized indexes
  before list growth/binding; a collection limit alone is not a user-facing
  error handler. Reject unknown fields/suppressed fields. Do not let Spring's
  BindingResult rejected-value strings reinsert secrets after `clearSecrets()`:
  rebuild safe field errors and the sanitized form, omit unsafe causes, and keep
  real saved values only in service memory. Use `Cache-Control: no-store` for
  editor/test responses and no secrets in flash attributes or validation logs.

- [ ] **Step 4: Implement the ordered editor and explicit test panel.** Reuse
  the Setup header/navigation and styles, with a small scoped CSS block for the
  workflow form. Render JSON Pointer examples/root-versus-entry context, mapping
  Add/Remove buttons and sensitivity checkboxes. Labels must target unique IDs
  after row deletion/reindexing. JavaScript adds rows from a `<template>` and
  reindexes existing rows using DOM APIs; it never builds HTML from API data.

  ```html
  <label for="workflow-url-mode">Source URL</label>
  <select id="workflow-url-mode" name="urlMode">
      <option value="KEEP">Keep saved URL</option>
      <option value="REPLACE">Replace URL</option>
  </select>
  <label for="workflow-url">New source URL</label>
  <input id="workflow-url" name="url" type="url" maxlength="8192" autocomplete="off">
  ```

  New workflows force REPLACE; saved ones default KEEP with empty inputs. Use the
  same pattern for template/header replacement. Keep errors in an accessible
  alert and link them to fields. Single/generated mode toggles only relevant
  sections while preserving draft rows. Save itself never fetches; the saved
  editor's Test POST states “Fetch fresh data and preview; does not start playback.”
  Test uses Task 2's transient entries/values and renders a sanitized result; no
  call to `DeviceManager` or runner Cast dispatch is allowed. On Setup, add the
  workflow summary even when every other source module is disabled. The editor
  explains first-save password, direct media MIME, receiver reachability and the
  lack of custom media-download headers without exposing implementation details.

- [ ] **Step 5: Re-run the Task 6 tests and `*StaticAssetsTest`.** Inspect failed
  form responses and masked test outputs for each explicit secret marker.
- [ ] **Step 6: Commit Task 6 files** as
  `feat: configure and preview workflows from setup`.

### Task 7: End-to-end acceptance and user documentation

**Files**

- Create: `src/test/java/dev/andre/homecontrol/web/WorkflowEndToEndTest.java`, `src/e2e/java/dev/andre/homecontrol/e2e/WorkflowE2eTest.java`, `docs/superpowers/reviews/2026-09-22-dynamic-workflows-acceptance.md`.
- Modify: `src/e2e/java/dev/andre/homecontrol/e2e/E2eApplicationTest.java` only for reusable protected helpers if needed, and `README.md`.

**Interfaces**

Reuse `FakeWorkflowServer` for upstream responses. Reuse browser test
`FakeDeviceAdapter.recorded(deviceId): List<Action>` and
`E2eApplicationTest.adopt(id,name,caps,fail)` with `CAST_RECEIVER`; do not turn the
existing fixed `FakeContentSource` into the workflow implementation. Use a
dedicated context/property override for workflow loopback fixture access. All
source/workflow settings, catalogs, credentials, devices and request counters are
cleared between scenarios using the fixture services' public APIs.

- [ ] **Step 1: Add real-context integration assertions for the entire flow.**
  Create the first saved workflow through HTTP and verify the login cookie;
  load a single tile with zero upstream requests; GET route preview with zero
  upstream requests; POST Play and assert one request plus one Cast LOAD. For
  generated mode refresh the catalog, change response order and token, preview
  the original tile, Play, and assert the same media ID/new token. Exercise
  failed fetch, disappeared row and wrong device as no-Cast cases. Verify Test
  never casts and every GET/test/error response excludes secret markers.

  ```java
  int beforePreview = upstream.count("/feed");
  mvc.perform(get("/devices/" + deviceId + "/route-preview")
      .param("source", "workflows").param("item", itemId).session(session))
      .andExpect(status().isOk()).andExpect(jsonPath("$.route.key").value("workflow-cast"));
  assertThat(upstream.count("/feed")).isEqualTo(beforePreview);
  assertThat(fakeDevices.recorded(deviceId)).isEmpty();
  ```

  In the web integration test provide a recording DeviceAdapter in a test
  configuration, following the existing fake adapter's public behavior; do not
  import e2e classes into the unit test source set. Define `upstream`, `mvc`,
  `deviceId`, `itemId`, `session`, and `fakeDevices` in fixture setup.

- [ ] **Step 2: Run `./gradlew test --tests '*WorkflowEndToEndTest'`** and fix
  integration defects before adding browser coverage.

- [ ] **Step 3: Add browser scenarios using accessible labels and roles.**
  Cover create/edit both modes, add/remove/reindex mappings, Keep credential
  behavior after a failed save, masked Test panel, tile/Play device selection,
  and failure followed by an explicit user retry with a fresh token. Copy the
  route-sheet locator and action assertions from `PlaySheetE2eTest`, and the
  320/390/1440-width bounds check from `InterfaceE2eTest`. Use the existing
  `BrowserSession`/`@BrowserTest` lifecycle, not a second browser bootstrap.

  ```java
  page.getByLabel("Workflow name", new Page.GetByLabelOptions().setExact(true)).fill("News");
  page.getByLabel("New source URL").fill(upstream.url("/feed").toString());
  page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Save workflow")).click();
  assertThat(page.getByRole(AriaRole.BUTTON,
      new Page.GetByRoleOptions().setName("Test workflow"))).isVisible();
  ```

  Complete the form fields required for each mode before the save in the actual
  scenario. These label strings become stable UI contracts. Capture request
  counts around opening the play sheet as well as Java-side recorded actions.

- [ ] **Step 4: Document the feature and hardware acceptance.** README includes
  the two tile modes, the spec's example JSON/pointer mappings, Save versus Test
  versus Play behavior, selected-device Cast, password/encrypted storage,
  default limits, source/loopback switches, and direct-media limitations.
  The acceptance file records automated command/results and separate unchecked
  real-device steps: save each mode, preview without playback, play on a Cast
  device, refresh a token, and confirm on-screen/audio output. Do not mark a
  hardware check passed without performing it.

- [ ] **Step 5: Run final verification once after changes stabilize.**

  ```bash
  ./gradlew build
  ./gradlew e2eTest --tests '*WorkflowE2eTest' --tests '*PlaySheetE2eTest' --tests '*LoginGatingE2eTest' --tests '*InterfaceE2eTest'
  git diff --check
  ```

  Browser task defaults to Chromium and WebKit. If prerequisites are unavailable,
  report that exact limitation and keep the acceptance result honest; ordinary
  `build` must continue to avoid browser dependencies. Inspect feature diff for
  secrets, obsolete snapshot publication, and accidental changes to other
  sources. A final independent code review should focus on the HTTP boundary,
  GET-preview side effects, form sanitization and edit/dispatch races.

- [ ] **Step 6: Commit Task 7 changes** as
  `test: verify dynamic workflow dashboard and cast flows`.

## Plan review and execution handoff

The feature spans seven tasks with shared model/routing interfaces and a new
outbound HTTP boundary. Recommend **subagent-driven execution** for per-task
implementation/review, with a final whole-branch review. Native execution remains
an option if the user prefers one implementer and a review at the end. The written
plan must be reviewed and the execution method selected before implementation.
