# Phase 1: Guardrails

Date: 2026-09-27

Status: Design approved section by section by the user on 2026-09-27; written spec awaiting review.

This spec refines Phase 1 of the [architecture and maintainability roadmap](2026-09-27-architecture-roadmap-design.md).
Where the two differ, this document wins for Phase 1.

## Purpose and agreed scope

Phase 1 adds the guardrails the roadmap's later phases rely on:
- architecture rules the build enforces;
- documentation that matches the code;
- a test suite that is faster, fails with a name instead of hanging, and does not depend on wall-clock luck.

**What the user decided:**

- **Two test targets, not one.** The roadmap's measure was "under 10 Spring context starts". Measured on `main`, context starts cost 40 s of a 495 s suite. The user chose to pursue both that measure and suite wall time.
- **Small reset methods.** Where a stateful bean has no way to clear its state, it gets one small, documented reset method. That lets a shared test context isolate test classes.
- **Six pull requests,** delivered in dependency order: 1.1, 1.2, 1.3a, 1.3b, 1.3c and 1.3d.
- **Test timing goes through seams inside the sessions.** The `*-seconds` configuration keys do not change in Phase 1; converting them to `Duration` stays in roadmap workstream 2A.

**Assumptions:**

- The Phase 0 pull requests are merged before the test work starts, #108 in particular: the test configuration it moves to `src/test/resources/config/application.yaml` is what 1.3c and 1.3d build on.
- Production behaviour does not change anywhere in Phase 1. The only production edits are:
  - the timing seams (1.3b);
  - constructor defaults for hard-coded delays (1.3b);
  - the reset methods (1.3d).

## Baseline

Measured with `scripts/gradle.sh test` on `main` at `7766d6e`. Every class runs in one JVM, one after another; this is the Gradle default.

| Measure | Value |
| --- | --- |
| Tests | 2,667 |
| Summed test-class time | 495 s |
| Spring context starts | 67, with 40.2 s of start time in total (0.6 s each) |
| Contexts forced by `@DirtiesContext` | `LoginGatingTest` restarts its context after each of its 14 tests |
| Context cache keys | Each full-application test class declares its own `@DynamicPropertySource`, and Spring treats every such method as a distinct context. 28 `@WebMvcTest` classes use about 21 distinct mock sets. |

The slowest classes (class time in seconds, number of tests):

| Class | s | Tests | Where the time goes |
| --- | ---: | ---: | --- |
| `AndroidTvSessionTest` | 43 | 20 | Chains of reconnects, each at least 1 s. `AndroidTvProperties` takes whole seconds, and `AndroidTvSession` schedules its backoff in whole seconds. |
| `CastSessionTest` | 35 | 37 | Heartbeat, stale-timeout and backoff at 1–3 s. `CastSession` schedules its backoff in whole seconds. |
| `TizenSessionTest` | 35 | 26 | 2 s request timeouts, handshake backoff, `during(2–3 s)` checks. |
| `YouTubeEndToEndTest` | 34 | 1 | Not yet explained. The OAuth poll has a hard 1 s floor (`GoogleOAuthClient`), but that accounts for only a few seconds. |
| `UpnpSessionTest` | 31 | 26 | Whole-second `UpnpProperties`, and about 16 s of `during(1.5–3 s)` checks. The poller itself schedules in milliseconds. |
| `WebOsSessionTest` | 27 | 23 | Whole-second `WebOsProperties`. Liveness and wake-grace are scheduled in seconds. |
| `RemoteConnectionTest` | 22 | 17 | TLS round trips close to their practical floor. |
| `BluetoothSpeakerSessionTest` | 14 | 22 | Whole-second polling, and a fixed 5 s wait in one test. |
| `BluetoothClassLoadingTest` | 11 | 2 | Two child JVMs, deliberately. |
| `SonosDiscoveryTest` | 10 | 8 | Whole-second SSDP search interval. |
| `CastConnectionTest` | 10 | 11 | Conservative durations chosen by the test. |
| `DeviceManagerTest` | 9 | 21 | An RSA-2048 key pair generated per test. |
| `JellyfinRouteExecutorTest` | 9 | 17 | Hard-coded 2 s retry and 250 ms pause step. |

Architecture violations today, counted from imports: sources importing adapters in 3 files, adapters importing web in 1 file, `jakarta.servlet` in 15 files outside `web` and `security` (some of them controllers the rule allows), none in `core`, none for network libraries. Package cycles are counted by ArchUnit in 1.1.

## 1.1 ArchUnit rules with a frozen store (S)

- Add `com.tngtech.archunit:archunit-junit5` as a `testImplementation` dependency, with its version pinned in `gradle/libs.versions.toml` at the latest 1.x release verified when the plan is written.
- One test class, `src/test/java/dev/andre/homecontrol/ArchitectureTest.java`, analyses production classes only (`@AnalyzeClasses(packages = "dev.andre.homecontrol", importOptions = ImportOption.DoNotIncludeTests.class)`). It holds these rules as `@ArchTest` fields:
  1. `core` depends only on the JDK and `core`.
  2. `..protocol..` packages depend on neither Spring nor any application package, other than `adapters.net` and other `protocol` packages.
  3. The top-level packages under `dev.andre.homecontrol` are free of cycles.
  4. `sources` does not depend on `adapters`.
  5. `adapters` depends on neither `sources` nor `web`.
  6. `web` does not depend on any `adapters` package.
  7. No source depends on another source except the shared `sources.http`. No adapter depends on another adapter except `adapters.net`, the future `adapters.support`, and `adapters.sonos` using `adapters.upnp.protocol`.
  8. `jakarta.servlet` is used only in `web`, `security`, and classes annotated `@Controller`, `@RestController` or `@ControllerAdvice`.
  9. `java.net.http`, `org.apache.hc`, jmDNS and the D-Bus libraries are used only in `adapters`, `sources` and `discovery`.
- Rules that have violations today are wrapped in `FreezingArchRule.freeze(...)`. Rules without violations are strict from the first commit.
- The store is committed in `src/test/archunit-store/`. `src/test/resources/archunit.properties` sets:

  ```properties
  freeze.store.default.path=src/test/archunit-store
  freeze.store.default.allowStoreCreation=false
  freeze.refreeze=false
  ```

  The store is created once, in this PR, with store creation temporarily allowed. When a later change fixes a violation, ArchUnit removes that entry from the store on the next run, and the change commits the smaller store.
- CI fails when the build changes the store: a step after "Build and run the full suite" runs `git diff --exit-code src/test/archunit-store`. This forces a fixed violation to be committed and keeps the frozen count honest.
- The frozen-violation count per rule is recorded in `docs/dev/architecture.md`.

## 1.2 Documentation (M)

The target layout:

```text
README.md                    ~150 lines: what it is, run it (Compose, CasaOS), first steps, links into docs/
docs/user/devices.md         pairing, the phone/PWA, opening links, Cast, Smart TVs, Wi-Fi speakers, discovery troubleshooting
docs/user/bluetooth-speakers.md   moved from docs/bluetooth-speakers.md
docs/user/sources.md         login, dynamic workflows, Jellyfin, play routes, Netflix/Prime/DAZN, YouTube, sport and DAZN
docs/user/configuration.md   the property and environment-variable table
docs/user/security.md        login, secrets, HTTPS through a reverse proxy, allowed hosts, trusted origins
docs/dev/architecture.md     the real package map, the ArchUnit rules, a dependency diagram, the roadmap's progress measures
docs/dev/testing.md          test layers, fakes and test support, browser tests, timeouts
docs/dev/ci-and-releases.md  CI jobs, the quality gate, conventional commits, releases
docs/adr/README.md           ADR index
docs/adr/0001-cast-sender.md moved from docs/superpowers/specs/2026-09-16-cast-sender-adr.md
docs/superpowers/archive/    merged implementation plans; specs and reviews stay where they are
AGENTS.md                    working rules for people and coding agents
CLAUDE.md                    a single line: @AGENTS.md
```

- **Sections move word for word.** The review can then read the moves as moves.
- **The README is the only rewrite:** an overview with links into `docs/`. Nothing in the app, its templates or the CasaOS manifests links to a README section, so the split breaks no links.
- **Stale facts are fixed as they move:** the "CI quality gate" section (SonarCloud is its own job), and the concept document's package map, which gets a pointer to `docs/dev/architecture.md`.
- **`AGENTS.md` distils the working rules** that exist today only in the gitignored `.superpowers/sdd/*-common.md`:
  - build and test only through `scripts/gradle.sh`, because there is no local JDK;
  - Java 25, Spring Boot 4.1.1, Jackson 3 (`tools.jackson.*`);
  - the product constraints: LAN appliance, no Node build, ephemeral commands, atomic `/data` writes, the `shield.*` keys and the CasaOS app id kept, every module switchable;
  - where adapters, sources, fakes and fixtures live;
  - conventional commits and the PR template;
  - staging only your own files;
  - the ArchUnit store rule from 1.1.

  It points to `scripts/`, not `.superpowers/`, and names no session, branch or model.
- **Plans are archived:** every implementation plan whose work is merged moves to `docs/superpowers/archive/`, the Phase 0 plan included. 1.2 starts after #113 merges.

## 1.3 Test infrastructure

### 1.3a Shared test support (M)

- **A new package, `dev.andre.homecontrol.testsupport`, in `src/test/java`.** The `e2e` source set already has the test output on its classpath, so both suites can use it. Gradle's `java-test-fixtures` waits for the Phase 4 module split.
- **`FakeHttpServer`,** on `com.sun.net.httpserver`, plain HTTP or HTTPS:
  - routes by method and path, with an optional query matcher;
  - canned responses (status, content type, headers including `Location`, body) or a custom `HttpHandler` per route;
  - a delay per route or for the whole server;
  - a trickling response (a body that never ends), which replaces Phase 0's `sources.http.TricklingServer`;
  - every request recorded (method, path, query, headers, body);
  - 404 for unknown routes, `reset()` for routes and records, and `close()`.
- **Six HTTP fakes become thin wrappers** over `FakeHttpServer`: `FakeJellyfinServer`, `FakeCalendarServer`, `FakeTheSportsDbServer`, `FakeTmdbServer`, `FakeGoogleServer` and `FakeWorkflowServer`. Each keeps its current test-facing methods, so its test classes barely change. `FakeUpnpRenderer`, `FakeTizenServer`, `FakeCastReceiver`, the Android TV fakes and `FakeMpv` stay protocol-specific.
- **Single copies:**
  - `MutableClock` replaces four copies (YouTube, TMDB, TheSportsDB, calendar);
  - `EventStreamReader` moves from the `web` tests;
  - `TestTls.serverContext(String commonName)` builds one self-signed server certificate, replacing the three ways the Cast, WebSocket and Android TV fakes build one today. Android TV's client-certificate pinning is not touched.
- **`RecordingStateListener`** is a `Consumer<DeviceState>` that records every state and offers `awaitStatus(DeviceStatus)`, `last()` and `all()`. It replaces the hand-written recorders in the Cast, webOS, Tizen, UPnP, Sonos and Bluetooth session tests.
- **Guarantees:**
  - no production change, and the same test count before and after;
  - each fake moves in its own commit, with its tests green;
  - the lines of fake and helper code are measured before and after.

### 1.3b Timeouts and slow waits (M)

**Safety nets.**
- `src/test/resources/junit-platform.properties` sets `junit.jupiter.execution.timeout.default = 60 s`. The few tests that need longer declare `@Timeout` themselves.
- Every job in `.github/workflows/ci.yml` gets `timeout-minutes`, sized at about twice its current duration.

**Wall-clock assertions.** These tests assert an upper bound on elapsed time:
- `SearchServiceTest`
- `TizenRestTest`
- `SsapConnectionTest` (two)
- `DeviceFetchTest`
- `WorkflowHttpClientTest`
- `DeepLinkTestServiceTest`
- `TmdbClientTest`
- the timing use in `TizenSessionTest`

Each assertion becomes one on the outcome: the timeout exception, or the order of events. The JUnit timeout is the guard against hanging.

**Timing seams.**
- `AndroidTvSession`, `CastSession`, `TizenSession`, `WebOsSession`, `BluetoothSpeakerSession` and `SsdpDiscovery` each take their timings as a small record of `Duration`s. Their adapter or configuration builds that record from the unchanged `*Properties`, with `Duration.ofSeconds(...)`, so production values and configuration keys stay the same.
- Scheduling moves to milliseconds. This removes the whole-second rounding in `AndroidTvSession` and `CastSession`, and it changes nothing in production, whose values are whole seconds.
- The UPnP and Sonos sessions already schedule in milliseconds through `ReconnectingPoller`. Their tests only get smaller values.

**Constructor defaults for hard-coded delays.** Each becomes a constructor parameter whose default is today's value:
- `JellyfinRouteExecutor`'s 2 s retry and 250 ms pause step;
- `GoogleOAuthClient`'s 1 s polling floor;
- `TizenSession`'s five-minute handshake-backoff cap.

**Test values.**
- Tests use 50–200 ms timings, and their "nothing happens for a while" checks shrink to match.
- `CastConnectionTest` picks shorter durations; it already takes `Duration`s.
- `DeviceManagerTest` reuses one pre-generated key pair from `testsupport` instead of generating RSA-2048 keys per test.

**Investigated, then fixed or recorded:** `YouTubeEndToEndTest`'s 34 s is profiled.

**Out of scope,** because the cost is the work itself, not waiting:
- `BluetoothClassLoadingTest` and `BluetoothSpeakerEndToEndTest`, which start child JVMs on purpose;
- `RemoteConnectionTest`'s TLS round trips.

**Expected result.** The slowest adapter tests drop from about 250 s to about 60 s, and the summed suite time from 495 s to about 300 s, before parallel forks.

### 1.3c Parallel test forks (S, after 1.3a)

- `tasks.test` sets `maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)`: 2 forks on CI's 4-vCPU runners. Each fork is its own JVM, so tests stay single-threaded within a fork.
- **Isolation:**
  - The test data directory becomes per fork, via `shield.data-dir: build/test-data/${org.gradle.test.worker:0}` in `config/application.yaml`.
  - The fixed paths in `SmartTvModulesOffTest` (`build/tmp/tv-modules-off-test`) and `AndroidTvSessionTest` (`./build/test-data`) become temporary directories.
  - Bluetooth's runtime directory (`java.io.tmpdir/home-control-bluetooth`) becomes per fork in tests.
  - Before the switch is turned on, the suite runs three times in a row with 4 forks, and every fixed port, UDP bind or shared file that fails is made per fork.
- **Measured:** the `test` task's wall time locally and CI's "Build and test" job time.

### 1.3d Shared Spring contexts (L, after 1.3a and 1.3c)

**Target:** under 10 context starts per fork.

**Web slice, 1 context.**
- An abstract `WebSliceTest` carries `@WebMvcTest` over all controllers. Every collaborator any controller needs is declared once as a `@MockitoBean`, with the module properties switched on.
- The 28 `@WebMvcTest` classes extend it and keep their own stubbing. Mockito beans reset after each test, as they do today.

**Full application, 1 context.**
- An abstract `FullAppTest` uses `@SpringBootTest(webEnvironment = RANDOM_PORT)` and `@AutoConfigureMockMvc`, so MockMvc tests and real-HTTP tests share it.
- It declares one `@DynamicPropertySource`, in the base class. Every subclass inherits the same method, so they share a cache key, which is what `E2eApplicationTest` already relies on.
- That property source registers the per-fork data directory and the URLs of the fakes from 1.3a. The fakes start once per JVM.
- These classes move onto it:
  - the end-to-end tests, including `LoginGateEndToEndTest` and `StaticAssetsTest`;
  - the workflow setup tests;
  - `HomeControlApplicationTest` and `TmdbModuleEnabledTest`;
  - `LoginGatingTest`, whose per-method `@DirtiesContext` becomes a reset in `@AfterEach`.

**`ApplicationContextRunner`, 0 cached contexts.** Every test that switches a module off moves to `ApplicationContextRunner`, like the five module-switch tests that already use it:
- the three nested `SportsModuleSwitchTest` classes;
- `JellyfinModuleSwitchTest`, `YouTubeModuleSwitchTest`, `TmdbModuleSwitchTest` and `PinnedModuleSwitchTest`;
- `CastDisabledSmokeTest`, `SmartTvModulesOffTest` and `WorkflowDisabledSetupTest`.

**Own contexts, because their beans differ:**
- Bluetooth switched on with the fake BlueZ (`BluetoothSpeakerEndToEndTest`, `BluetoothJellyfinEndToEndTest`);
- `WorkflowEndToEndTest`, which uses a recording device adapter.

**Reset between test classes.** A JUnit extension on `FullAppTest` runs after each class, and `LoginGatingTest` also calls it after each test. It uses existing operations where they exist:
- forget every device (`DeviceManager.forget`);
- remove every secret (`SecretStore.removeSecrets(names())`), which also removes the login;
- invalidate every source's rails (`RailCache.invalidateSource`);
- delete `sources.json`, which is re-read on every call;
- reset the shared fakes.

Where no operation exists, the bean gets a small public reset method, documented as existing for the shared test context:
- `LoginRateLimiter`
- `JsonFileSportsStore`
- `JsonFilePinStore`
- `WorkflowStore`, but only if removing the secrets does not also drop its in-memory definitions

Roadmap workstream 2B folds the store resets into its shared store type.

**Out of scope:** the Playwright suite (`src/e2e`) keeps its fresh context per class. Its time goes into the browsers, and some of its tests rely on a fresh context.

**Measured:** context starts and summed start time per fork.

## Delivery and sequencing

| PR | Content | Starts after |
| --- | --- | --- |
| 1.1 | ArchUnit and the frozen store | now |
| 1.2 | Documentation | #113 (roadmap docs) merged |
| 1.3a | Shared test support | #108 (test configuration) merged |
| 1.3b | Timeouts and slow waits | #108 merged; independent of 1.3a |
| 1.3c | Parallel test forks | 1.3a |
| 1.3d | Shared Spring contexts | 1.3a and 1.3c |

- Every pull request starts from `main` and keeps `./gradlew build` green.
- The test pull requests keep the test count equal or higher. Each moved or deleted test names its replacement in the commit message.

## Progress measures

Recorded in `docs/dev/architecture.md` after each pull request.

| Measure | Baseline (`7766d6e`) | Target |
| --- | --- | --- |
| Frozen ArchUnit violations | counted in 1.1 | only falls |
| Summed test-class time | 495 s | about 300 s after 1.3b |
| `test` task wall time | measured in 1.3c | measured; no target |
| CI "Build and test" job time | about 9 min | measured after 1.3c; no target |
| Spring context starts | 67 (single fork) | under 10 per fork after 1.3d |
| Wall-clock upper-bound assertions | 9 | 0 |
| Copies of `MutableClock` | 4 | 1 |

## Out of scope

- Converting the `*-seconds` configuration keys to `Duration`, which is roadmap workstream 2A.
- Parallel test execution inside one JVM.
- Speeding up the Playwright suite.
- Consolidating the protocol-specific fakes (UPnP, Tizen, Cast, Android TV, mpv).

## Decisions deferred to the implementation plan

- The ArchUnit version, verified against Maven Central.
- Why `YouTubeEndToEndTest` takes 34 s, and whether the fix fits 1.3b.
- The exact list of per-fork resources, found by the three 4-fork runs in 1.3c.
- Whether `WorkflowStore` needs its own reset method (1.3d).
