# Phase 1.3b Timeouts and Slow Waits Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Tests stop waiting on whole-second production timings and on wall-clock bounds. Every device session takes its waits as a small record of `Duration`s, so tests can run it at 50–500 ms. Every test has a default timeout, so a hang fails fast.

**Architecture:**
- **Timing records.** Each device session gets a package-level record, such as `AndroidTvTimings` or `CastTimings`, holding the `Duration`s it waits on. Its static `from(properties)` builds that record from the unchanged `*Properties` with `Duration.ofSeconds(...)`, so production values and configuration keys stay the same.
- **Session constructors.** The session's existing constructor keeps its signature and calls `from(properties)`. A second constructor takes the record, and the tests use that one.
- **Scheduling.** Scheduling moves to `TimeUnit.MILLISECONDS`, and backoff doubling works on `Duration`s. That removes the whole-second rounding.
- **Hard-coded delays.** They become constructor parameters whose default is today's value.
- **Wall-clock assertions.** Each one becomes an assertion on the outcome: the exception, its message, or the order of events.

**Tech Stack:** Java 25, Spring Boot 4.1.1, JUnit 6 (Jupiter) with AssertJ and Awaitility, Gradle through `scripts/gradle.sh`.

**Spec:** `docs/superpowers/specs/2026-09-27-phase-1-guardrails-design.md`, section "1.3b Timeouts and slow waits (M)", and "Delivery and sequencing" and "Progress measures".

## Global Constraints

- **Start condition:** #131 (Phase 1.3a) is merged. Branch `test/timeouts-and-slow-waits` from `origin/main`.
- **Production values and configuration keys stay the same.** Every `*Timings.from(properties)` yields exactly the durations the session used before, and no `@ConfigurationProperties` record changes.
- **Test count:** the test pull requests keep the test count equal or higher. Each moved or deleted test names its replacement in the commit message.
- **Build and test only through `scripts/gradle.sh`.** There is no local JDK, and `scripts/gradle.sh build` is green at the end.
- **No wall-clock upper bounds in tests.** A delay may be checked with a lower bound; hangs are guarded by the JUnit timeout. "Nothing happens for a while" checks (`await().during(...)`) stay, sized to at least five of the intervals they watch.
- **Test timings are 50–500 ms.** Stale and read timeouts that must not fire during a test stay at seconds.
- **Waiting:** a helper that waits on purpose uses `Thread.sleep` with `@SuppressWarnings("java:S2925")` and a one-line reason. Every other wait uses Awaitility.
- **Commits:**
  - Conventional Commits, ending with a `Co-Authored-By:` trailer naming the model that wrote them.
  - Stage only the files you changed: `git add <paths>`, never `git add -A`.
- **Known flake:** some webOS, Tizen and Cast timing tests fail under four parallel test JVMs on a busy host. This task set shrinks exactly those waits. If one fails, run its class alone. If it passes alone, record it and go on.

## Plan decisions

1. **UPnP and Sonos get timing records too (Task 11).**
   - The spec says their tests "only get smaller values", but every timing field in `UpnpProperties` and `SonosProperties` is a whole-seconds `int` with a minimum of 1, and their tests already use 1.
   - `ReconnectingPoller` already schedules in milliseconds, so their sessions only change where they turn properties into `Duration`s.
2. **Collaborators keep their seconds.** `TizenRest`, `DialClient`, `TizenRemoteConnection`, `SsapConnection`, the `HttpClient` connect timeouts and `MpvPlayer`'s timeouts keep reading their `*Properties` in whole seconds. Only the waits a session schedules or blocks on itself move to its timing record. The two exceptions are Tizen's handshake wait and webOS's `register` prompt timeout, which the sessions pass in themselves.
3. **Constants that do not come from properties:**
   - Cast's 750 ms custom-message error window joins `CastTimings`, with 750 ms as its production value, because every Cast custom-message test waits it out.
   - These stay as they are, because no test waits them out: `CastConnection`'s 5 s TCP connect timeout, `SsdpDiscovery`'s two 3 s HTTP timeouts, and `TizenRemoteConnection`'s 300 ms late-answer grace.
4. **The JUnit default timeout (Task 1):**
   - `src/test/resources/junit-platform.properties` also reaches the Playwright tests, because the `e2e` source set has the test output on its classpath. That is accepted: their longest designed budget is `PinUpgradeE2eTest`'s 45 s.
   - The timeout mode is `disabled_on_debug`, so stepping through a test in a debugger works.
   - The thread mode stays JUnit's default, `SAME_THREAD`. `SEPARATE_THREAD` would run test methods on a thread other than their `@BeforeEach`, and break thread-bound setup.
   - `BluetoothClassLoadingTest` declares `@Timeout(4 min)`, because it waits up to 3 minutes for a child JVM by design.
5. **Wall-clock assertions:**
   - The spec's nine become outcome assertions.
   - `EventStreamShutdownEndToEndTest`'s bound (application close under 10 s) stays. The elapsed time is itself the behaviour under test: the application no longer waits out a 30 s graceful shutdown for an open event stream.
   - `UpnpSessionTest.closeStopsPolling` and `ReconnectingPollerTest.closeStopsEverything` compare call timestamps with the moment of `close()`. That is an ordering check with a tolerance, not an elapsed-time bound, so they stay, with shorter windows.
6. **`YouTubeEndToEndTest` is recorded, not fixed.**
   - Its test time is about 8 s:
     - 5.1 s of Spring context start;
     - 3.4 s of certificate generation and a real Cast connect before the first request;
     - at least 2 s of OAuth polling, because of `GoogleOAuthClient`'s 1 s floor and `YouTubeAuthorizationService`'s 1 s tick.
   - The rest of the "34 s" is Gradle and JVM start.
   - The floor becomes a constructor parameter (Task 3), but the Spring bean keeps 1 s. Phase 1.3d's shared context removes the context start.
7. **One shared test client certificate (Task 4).** `testsupport.TestCredentials.clientCertificate()` generates one RSA-2048 `ClientCertificate` per JVM. `DeviceManagerTest` stores it under each alias instead of generating seven. No test there compares keys across aliases.

## Review Focus

- **Production timings must not change.** A `from(properties)` that swapped two fields, or used milliseconds where seconds were meant, would change what every installed user gets, and no session test would notice, because the tests pass their own timings. Test: every task that adds a timing record adds a `from` test that maps each property to its duration (`...TimingsTest.fromTakesTheConfiguredSeconds`).
- **Backoff doubling without whole seconds must still stop at its cap.** The old code capped with `Math.min` on seconds. The new code compares `Duration`s, and a wrong comparison would let reconnect delays grow without bound. Test: `AndroidTvTimingsTest.backoffDoublesUpToItsCap` (Task 5) pins the shared `Backoff.next(...)` helper used by Android TV, Cast and webOS.
- **The default timeout must not cut a legitimately long test.** `BluetoothClassLoadingTest` waits for a child JVM. Test: the full `scripts/gradle.sh build` in Task 12 is green with the default in place, and `DefaultTimeoutTest` pins the configured value (Task 1).
- **Short timings under four parallel JVMs:**
  - A 50 ms wait on a loaded host can flake.
  - Every "nothing happens" window in this plan covers at least five of the intervals it watches.
  - Every `atMost` ceiling is left generous: it costs nothing when the condition comes true early.
  - Test: the Task 12 suite runs twice in a row, both green.
- **Tizen's handshake-backoff cap must default to five minutes and be honoured.** Test: `TizenTimingsTest.fromTakesTheConfiguredSeconds` asserts the 5-minute cap.

---

### Task 1: A default timeout for every test

**Files:**
- Create: `src/test/resources/junit-platform.properties`
- Test: `src/test/java/dev/andre/homecontrol/testsupport/DefaultTimeoutTest.java`
- Modify: `src/test/java/dev/andre/homecontrol/adapters/bluetooth/BluetoothClassLoadingTest.java` (class annotation)

**Interfaces:**
- Consumes: nothing.
- Produces: nothing other tasks call.

- [ ] **Step 1: Write the failing test**

`src/test/java/dev/andre/homecontrol/testsupport/DefaultTimeoutTest.java`:

```java
package dev.andre.homecontrol.testsupport;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** Pins the JUnit configuration every test runs under: a hang fails the test within a minute. */
class DefaultTimeoutTest {

    @Test
    void everyTestHasASixtySecondTimeoutUnlessItDeclaresOne() throws IOException {
        Properties properties = new Properties();
        try (InputStream in = DefaultTimeoutTest.class.getResourceAsStream("/junit-platform.properties")) {
            assertThat(in).as("junit-platform.properties on the test classpath").isNotNull();
            properties.load(in);
        }

        assertThat(properties)
                .containsEntry("junit.jupiter.execution.timeout.default", "60 s")
                .containsEntry("junit.jupiter.execution.timeout.mode", "disabled_on_debug");
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.testsupport.DefaultTimeoutTest'`

Expected: FAIL: `junit-platform.properties on the test classpath` / `Expecting actual not to be null`.

- [ ] **Step 3: Add the configuration**

`src/test/resources/junit-platform.properties`:

```properties
# Every test method gets 60 seconds unless it declares a longer @Timeout: a hang fails that test
# instead of holding the build until CI's job limit. Timeouts are off while a debugger is attached.
# The e2e source set has this file on its classpath too; its longest designed wait is 45 s.
junit.jupiter.execution.timeout.default = 60 s
junit.jupiter.execution.timeout.mode = disabled_on_debug
```

In `src/test/java/dev/andre/homecontrol/adapters/bluetooth/BluetoothClassLoadingTest.java`, annotate the class:

```java
// Starts a child JVM and waits up to 3 minutes for it, longer than the 60 s default of junit-platform.properties.
@Timeout(value = 4, unit = TimeUnit.MINUTES)
```

Add the imports `org.junit.jupiter.api.Timeout` and `java.util.concurrent.TimeUnit` if they are missing.

- [ ] **Step 4: Run the test and the long test to verify**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.testsupport.DefaultTimeoutTest' --tests 'dev.andre.homecontrol.adapters.bluetooth.BluetoothClassLoadingTest'`

Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add src/test/resources/junit-platform.properties src/test/java/dev/andre/homecontrol/testsupport/DefaultTimeoutTest.java src/test/java/dev/andre/homecontrol/adapters/bluetooth/BluetoothClassLoadingTest.java
git commit -m "test: give every test a 60 s timeout, and the child-JVM test four minutes"
```

End the message with your `Co-Authored-By:` trailer.

### Task 2: Outcome assertions instead of wall-clock bounds

**Files (all under `src/test/java/dev/andre/homecontrol/`):**
- `content/SearchServiceTest.java` (two tests)
- `adapters/tizen/TizenRestTest.java`
- `adapters/webos/SsapConnectionTest.java` (two tests)
- `discovery/ssdp/DeviceFetchTest.java`
- `sources/workflows/WorkflowHttpClientTest.java`
- `playback/DeepLinkTestServiceTest.java`
- `sources/tmdb/TmdbClientTest.java`

(`TizenSessionTest`'s timing use changes in Task 7, with the Tizen timings.)

**Interfaces:**
- Consumes: nothing.
- Produces: nothing other tasks call.

These are test-only changes. Each test keeps its name and still passes. The step before each change runs the class, and the step after runs it again.

- [ ] **Step 1: Run the eight classes before the change**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.content.SearchServiceTest' --tests 'dev.andre.homecontrol.adapters.tizen.TizenRestTest' --tests 'dev.andre.homecontrol.adapters.webos.SsapConnectionTest' --tests 'dev.andre.homecontrol.discovery.ssdp.DeviceFetchTest' --tests 'dev.andre.homecontrol.sources.workflows.WorkflowHttpClientTest' --tests 'dev.andre.homecontrol.playback.DeepLinkTestServiceTest' --tests 'dev.andre.homecontrol.sources.tmdb.TmdbClientTest'`

Expected: PASS. Note the count from `cat build/test-results/test/*.xml | grep -c '<testcase '` (Gradle clears the folder at every run).

- [ ] **Step 2: `SearchServiceTest.queriesSourcesInParallelAndKeepsSourceOrder` proves parallelism with a latch**

Replace the test with the following. Each source waits until both have started. Queried one after the other, the first would wait out its 10 s and miss the 2 s search timeout, so the outcome itself shows the parallelism.

```java
    @Test
    void queriesSourcesInParallelAndKeepsSourceOrder() {
        CountDownLatch bothStarted = new CountDownLatch(2);
        StubSource first = new StubSource("first", "First", waitForTheOtherThenReturn(bothStarted, List.of(item("i1", "first", "One"))));
        StubSource second = new StubSource("second", "Second", waitForTheOtherThenReturn(bothStarted, List.of(item("i2", "second", "Two"))));
        ContentSources sources = new ContentSources(List.of(first, second));
        SearchService service = new SearchService(sources, new TestPreferences(), properties(Duration.ofSeconds(2)), executor);

        SearchOutcome outcome = service.search("q", 10);

        assertThat(outcome.failures()).isEmpty();
        assertThat(outcome.hits()).hasSize(2);
        assertThat(outcome.hits().get(0).source()).isEqualTo(first);
        assertThat(outcome.hits().get(1).source()).isEqualTo(second);
    }
```

Add this helper next to `sleepThenReturn`:

```java
    /** Answers only once the other source has started too, which it cannot if the sources are queried in turn. */
    private static BiFunction<String, Integer, List<ContentItem>> waitForTheOtherThenReturn(CountDownLatch bothStarted,
                                                                                         List<ContentItem> items) {
        return (query, limit) -> {
            bothStarted.countDown();
            try {
                if (!bothStarted.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the other source was never queried at the same time");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return items;
        };
    }
```

Add the imports `java.util.concurrent.CountDownLatch` and `java.util.concurrent.TimeUnit` if they are missing.

- [ ] **Step 3: `SearchServiceTest.aSlowSourceBecomesAFailureAndOthersStillAnswer` drops its elapsed-time check**

Delete these lines:

```java
        long start = System.nanoTime();
```

```java
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).isLessThan(600);
```

What remains is:
- the failure "Slow did not answer in time", which only the per-source timeout produces;
- the fast source's hit, which shows the others still answer.

- [ ] **Step 4: `TizenRestTest.anUnreachableTvHasNoInfo`**

Delete `long started = System.nanoTime();` and the line `assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));`.

The empty result is the outcome. A request that never ended would now fail on the JUnit timeout instead. Remove the `Duration` import only if nothing else in the file uses it.

- [ ] **Step 5: `SsapConnectionTest`, both tests**

In `anUnansweredRequestTimesOutAsSuch`, replace

```java
        long started = System.nanoTime();

        assertThatThrownBy(() -> opened.request(SsapUris.SET_VOLUME, SsapMessages.empty().put("volume", 5)))
                .isInstanceOf(SsapTimeoutException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isBetween(Duration.ofMillis(1800), Duration.ofSeconds(4));
```

with

```java
        assertThatThrownBy(() -> opened.request(SsapUris.SET_VOLUME, SsapMessages.empty().put("volume", 5)))
                .isInstanceOf(SsapTimeoutException.class)
                .hasMessageContaining("within 2 seconds");
```

In `theTvDroppingTheConnectionIsReportedOnce`, replace

```java
        long started = System.nanoTime();
        assertThatThrownBy(() -> connection.request(SsapUris.SYSTEM_INFO, SsapMessages.empty()))
                .isInstanceOf(IOException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
```

with

```java
        // Refused because the connection is known to be closed, not after a request timeout.
        assertThatThrownBy(() -> connection.request(SsapUris.SYSTEM_INFO, SsapMessages.empty()))
                .isInstanceOf(IOException.class)
                .isNotInstanceOf(SsapTimeoutException.class)
                .hasMessageStartingWith("The TV closed the connection:");
```

- [ ] **Step 6: `DeviceFetchTest.aTricklingDeviceRunsIntoTheDeadline`**

Replace

```java
        long started = System.nanoTime();

        assertThatThrownBy(() -> DeviceFetch.get(http, url("/trickle"), Duration.ofMillis(500), 64 * 1024))
                .isInstanceOf(HttpTimeoutException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
```

with

```java
        assertThatThrownBy(() -> DeviceFetch.get(http, url("/trickle"), Duration.ofMillis(500), 64 * 1024))
                .isInstanceOf(HttpTimeoutException.class)
                .hasMessageContaining("no complete answer within 500 ms");
```

- [ ] **Step 7: `WorkflowHttpClientTest.deadlineCoversBothHeadersAndBody`**

Delete `long start = System.nanoTime();` and `assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));`. Replace

```java
            assertThat(result.get(3, TimeUnit.SECONDS)).isInstanceOf(WorkflowException.class);
```

with

```java
            assertThat(result.get(3, TimeUnit.SECONDS)).isInstanceOf(WorkflowException.class)
                    .hasMessageContaining("request timed out");
```

- [ ] **Step 8: `DeepLinkTestServiceTest.aDeviceThatCannotReportItsAppIsNotObservable`**

Delete `long started = System.nanoTime();` and `assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(200));`.

`NOT_OBSERVABLE` is returned only before the service starts watching. Every path that waits returns `APP_CHANGED` or `NO_CHANGE`, so the outcome assertion already shows no wait happened. Add a comment above it saying so:

```java
        // Only the path that never waits for the app to change returns NOT_OBSERVABLE.
```

- [ ] **Step 9: `TmdbClientTest.aSlowServerTimesOutQuickly`**

Replace

```java
        long start = System.nanoTime();

        assertThatThrownBy(() -> client.get(bearer, "/authentication", Map.of()))
                .isInstanceOf(TmdbException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
```

with

```java
        assertThatThrownBy(() -> client.get(bearer, "/authentication", Map.of()))
                .isInstanceOf(TmdbException.class)
                .hasMessage("Could not reach TMDB at 127.0.0.1")
                .extracting(e -> ((TmdbException) e).kind()).isEqualTo(TmdbException.Kind.UNREACHABLE);
```

- [ ] **Step 10: Run the eight classes after the change**

Run the command from Step 1.

Expected: PASS, with the same count as in Step 1. Also run:

```bash
git grep -n 'nanoTime' -- src/test/java/dev/andre/homecontrol/content/SearchServiceTest.java src/test/java/dev/andre/homecontrol/adapters/tizen/TizenRestTest.java src/test/java/dev/andre/homecontrol/adapters/webos/SsapConnectionTest.java src/test/java/dev/andre/homecontrol/discovery/ssdp/DeviceFetchTest.java src/test/java/dev/andre/homecontrol/sources/workflows/WorkflowHttpClientTest.java src/test/java/dev/andre/homecontrol/playback/DeepLinkTestServiceTest.java src/test/java/dev/andre/homecontrol/sources/tmdb/TmdbClientTest.java
```

Expected: no output. Remove any import that the deletions left unused (`Duration` or `TimeUnit`), checking each with `grep`.

- [ ] **Step 11: Commit**

```bash
git add src/test/java/dev/andre/homecontrol/content/SearchServiceTest.java src/test/java/dev/andre/homecontrol/adapters/tizen/TizenRestTest.java src/test/java/dev/andre/homecontrol/adapters/webos/SsapConnectionTest.java src/test/java/dev/andre/homecontrol/discovery/ssdp/DeviceFetchTest.java src/test/java/dev/andre/homecontrol/sources/workflows/WorkflowHttpClientTest.java src/test/java/dev/andre/homecontrol/playback/DeepLinkTestServiceTest.java src/test/java/dev/andre/homecontrol/sources/tmdb/TmdbClientTest.java
git commit -m "test: assert what a timeout produces, not how long it took

Nine wall-clock bounds become assertions on the outcome: the exception
and its message, a latch that only parallel queries pass, and results
only the non-waiting path returns."
```

End the message with your `Co-Authored-By:` trailer.

### Task 3: Constructor defaults for the Jellyfin startup waits and the OAuth polling floor

**Files:**
- Modify: `src/main/java/dev/andre/homecontrol/sources/jellyfin/JellyfinRouteExecutor.java`
- Modify: `src/test/java/dev/andre/homecontrol/sources/jellyfin/JellyfinRouteExecutorTest.java`
- Modify: `src/main/java/dev/andre/homecontrol/sources/youtube/GoogleOAuthClient.java`
- Modify: `src/test/java/dev/andre/homecontrol/sources/youtube/GoogleOAuthClientTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `JellyfinRouteExecutor(JellyfinSessions, DeviceManager, Duration startupTimeout, Duration retry, Duration pauseStep)`, package-private. The public 3-argument constructor keeps its signature and passes `JellyfinRouteExecutor.RETRY` (2 s) and `JellyfinRouteExecutor.PAUSE_STEP` (250 ms).
  - `GoogleOAuthClient(YouTubeHttp, URI, Clock, Duration minimumPollInterval)`, package-private. The public 3-argument constructor passes `GoogleOAuthClient.MINIMUM_POLL_INTERVAL` (1 s).

- [ ] **Step 1: Write the failing tests**

In `GoogleOAuthClientTest`, add:

```java
    @Test
    void theReportedPollIntervalNeverGoesBelowTheFloor() throws IOException {
        start();
        String immediate = FakeGoogleServer.fixture("oauth-device-code.json").replace("\"interval\": 5", "\"interval\": 0");
        fake.respond("POST", "/oauth/device/code", FakeGoogleServer.Canned.json(200, immediate));

        assertThat(client.requestDeviceCode("cid").interval()).isEqualTo(GoogleOAuthClient.MINIMUM_POLL_INTERVAL);

        GoogleOAuthClient fast = new GoogleOAuthClient(new YouTubeHttp(fake.properties()), URI.create(fake.base() + "/oauth"),
                MutableClock.at(Instant.parse("2026-09-16T10:00:00Z")), Duration.ofMillis(10));
        assertThat(fast.requestDeviceCode("cid").interval()).isEqualTo(Duration.ofMillis(10));
        assertThat(GoogleOAuthClient.MINIMUM_POLL_INTERVAL).isEqualTo(Duration.ofSeconds(1));
    }
```

In `JellyfinRouteExecutorTest`, add:

```java
    @Test
    void productionWaitsAreTwoSecondsBetweenCommandsAndAQuarterSecondPerStep() {
        assertThat(JellyfinRouteExecutor.RETRY).isEqualTo(Duration.ofSeconds(2));
        assertThat(JellyfinRouteExecutor.PAUSE_STEP).isEqualTo(Duration.ofMillis(250));
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.youtube.GoogleOAuthClientTest' --tests 'dev.andre.homecontrol.sources.jellyfin.JellyfinRouteExecutorTest'`

Expected: FAIL at `compileTestJava`: `cannot find symbol` for `MINIMUM_POLL_INTERVAL`, the 4-argument constructor, `RETRY` and `PAUSE_STEP`.

- [ ] **Step 3: `GoogleOAuthClient`**

Add below the class's other constants:

```java
    /** Google may ask for a shorter interval than this; polling faster than once a second earns slow_down answers. */
    static final Duration MINIMUM_POLL_INTERVAL = Duration.ofSeconds(1);
```

Add a field `private final Duration minimumPollInterval;`, then replace the constructor with:

```java
    public GoogleOAuthClient(YouTubeHttp http, URI oauthBaseUrl, Clock clock) {
        this(http, oauthBaseUrl, clock, MINIMUM_POLL_INTERVAL);
    }

    GoogleOAuthClient(YouTubeHttp http, URI oauthBaseUrl, Clock clock, Duration minimumPollInterval) {
        this.http = http;
        this.base = oauthBaseUrl;
        this.clock = clock;
        this.minimumPollInterval = minimumPollInterval;
    }
```

In `requestDeviceCode`, replace

```java
                Duration.ofSeconds(Math.max(1, json.path("interval").asLong(5))));
```

with

```java
                atLeast(Duration.ofSeconds(json.path("interval").asLong(5)), minimumPollInterval));
```

Then add the helper:

```java
    private static Duration atLeast(Duration value, Duration floor) {
        return value.compareTo(floor) < 0 ? floor : value;
    }
```

- [ ] **Step 4: `JellyfinRouteExecutor`**

Replace

```java
    private static final long RETRY_NANOS = Duration.ofSeconds(2).toNanos();
```

with

```java
    /** How long a sent wake or launch gets before it is sent again. */
    static final Duration RETRY = Duration.ofSeconds(2);
    /** The longest single pause while waiting for the Jellyfin session. */
    static final Duration PAUSE_STEP = Duration.ofMillis(250);
```

Add the fields `private final long retryNanos;` and `private final Duration pauseStep;`. Then replace the constructor with:

```java
    public JellyfinRouteExecutor(JellyfinSessions sessions, DeviceManager devices, Duration startupTimeout) {
        this(sessions, devices, startupTimeout, RETRY, PAUSE_STEP);
    }

    JellyfinRouteExecutor(JellyfinSessions sessions, DeviceManager devices, Duration startupTimeout,
                          Duration retry, Duration pauseStep) {
        if (startupTimeout.isNegative() || startupTimeout.isZero()) {
            throw new IllegalArgumentException("Jellyfin startup timeout must be positive");
        }
        this.sessions = sessions;
        this.devices = devices;
        this.startupTimeout = startupTimeout;
        this.retryNanos = retry.toNanos();
        this.pauseStep = pauseStep;
    }
```

Then:
- Replace each `System.nanoTime() + RETRY_NANOS` with `System.nanoTime() + retryNanos`.
- Make `pause(long deadline)` an instance method: remove `static`.
- In it, replace `Duration.ofMillis(250).toNanos()` with `pauseStep.toNanos()`.

- [ ] **Step 5: Shorter waits in `JellyfinRouteExecutorTest`**

Change the three executors the class builds:
- **The field** becomes `new JellyfinRouteExecutor(sessions, devices, Duration.ofMillis(500), Duration.ofMillis(100), Duration.ofMillis(10))`.
- **The executor at the old line 185** (`Duration.ofSeconds(4)`) becomes `new JellyfinRouteExecutor(sessions, devices, Duration.ofSeconds(4), Duration.ofMillis(100), Duration.ofMillis(10))`.
- **The executor at the old line 255** (`Duration.ofMillis(100)`) becomes `new JellyfinRouteExecutor(sessions, devices, Duration.ofMillis(100), Duration.ofMillis(100), Duration.ofMillis(10))`.

A test that waits out the whole startup timeout now waits 500 ms instead of 1 s.

- [ ] **Step 6: Run the tests to verify they pass**

Run the command from Step 2, plus `--tests 'dev.andre.homecontrol.sources.jellyfin.JellyfinPlayableResolverTest' --tests 'dev.andre.homecontrol.sources.youtube.*'`.

Expected: PASS. `JellyfinRouteExecutorTest`'s class time, from `grep -o 'time="[0-9.]*"' build/test-results/test/TEST-dev.andre.homecontrol.sources.jellyfin.JellyfinRouteExecutorTest.xml | head -1`, falls from about 9.7 s to under 3 s.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/sources/jellyfin/JellyfinRouteExecutor.java src/test/java/dev/andre/homecontrol/sources/jellyfin/JellyfinRouteExecutorTest.java src/main/java/dev/andre/homecontrol/sources/youtube/GoogleOAuthClient.java src/test/java/dev/andre/homecontrol/sources/youtube/GoogleOAuthClientTest.java
git commit -m "refactor: take Jellyfin's startup waits and the OAuth poll floor as parameters

The retry and pause step of Jellyfin's startup and the 1 s floor of the
OAuth poll interval become constructor parameters whose defaults are
today's values; the Jellyfin executor's tests run at 10-100 ms."
```

End the message with your `Co-Authored-By:` trailer.

### Task 4: One pre-generated client certificate for `DeviceManagerTest`

**Files:**
- Create: `src/test/java/dev/andre/homecontrol/testsupport/TestCredentials.java`
- Modify: `src/test/java/dev/andre/homecontrol/device/DeviceManagerTest.java`

**Interfaces:**
- Consumes: `dev.andre.homecontrol.adapters.androidtv.protocol.ClientCertificate.generate(String)` and `CertificateStore.save(String, ClientCertificate)`.
- Produces: `TestCredentials.clientCertificate()`, which returns the same `ClientCertificate` (`CN=shield-remote`) for the whole JVM.

- [ ] **Step 1: Run the class before the change**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.device.DeviceManagerTest'`

Expected: PASS. Note the count and the class time: `grep -o 'tests="[0-9]*"\|time="[0-9.]*"' build/test-results/test/TEST-dev.andre.homecontrol.device.DeviceManagerTest.xml | head -2`.

- [ ] **Step 2: Add the shared certificate**

`src/test/java/dev/andre/homecontrol/testsupport/TestCredentials.java`:

```java
package dev.andre.homecontrol.testsupport;

import dev.andre.homecontrol.adapters.androidtv.protocol.ClientCertificate;

/**
 * One Android TV client certificate for a whole test JVM. Generating an RSA-2048 key takes long enough to show in
 * a suite's time; tests that only need some credential stored under an alias share this one.
 */
public final class TestCredentials {

    private TestCredentials() {
    }

    public static ClientCertificate clientCertificate() {
        return Holder.CERTIFICATE;
    }

    private static final class Holder {
        private static final ClientCertificate CERTIFICATE = ClientCertificate.generate("shield-remote");
    }
}
```

- [ ] **Step 3: Store it instead of generating one per alias**

In `DeviceManagerTest`, replace each of the seven `certificates.loadOrCreate("<alias>");` calls with `certificates.save("<alias>", TestCredentials.clientCertificate());`. The aliases are `living` and `bedroom` (twice each), `127-0-0-1`, `gone` and `kept`. Add `import dev.andre.homecontrol.testsupport.TestCredentials;`.

A call whose return value is used, such as `ClientCertificate c = certificates.loadOrCreate(...)`, becomes two lines. The first saves the shared certificate, and the second assigns `TestCredentials.clientCertificate()`.

- [ ] **Step 4: Run the class after the change**

Run the command from Step 1.

Expected: PASS, with the same count, and a class time lower than in Step 1.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/dev/andre/homecontrol/testsupport/TestCredentials.java src/test/java/dev/andre/homecontrol/device/DeviceManagerTest.java
git commit -m "test: share one pre-generated client certificate in DeviceManagerTest

Seven RSA-2048 keys per run become one per JVM; no test there compares
keys across aliases."
```

End the message with your `Co-Authored-By:` trailer.

### Task 5: `AndroidTvTimings`, and the shared backoff step

**Files:**
- Create: `src/main/java/dev/andre/homecontrol/adapters/androidtv/AndroidTvTimings.java`
- Create: `src/main/java/dev/andre/homecontrol/adapters/net/Backoff.java`
- Test: `src/test/java/dev/andre/homecontrol/adapters/androidtv/AndroidTvTimingsTest.java`
- Modify: `src/main/java/dev/andre/homecontrol/adapters/androidtv/AndroidTvSession.java`
- Modify: `src/test/java/dev/andre/homecontrol/adapters/androidtv/AndroidTvSessionTest.java`

**Interfaces:**
- Consumes: `AndroidTvProperties` (unchanged).
- Produces:
  - `record AndroidTvTimings(Duration staleTimeout, Duration reconnectInitialDelay, Duration reconnectMaxDelay)`, with `static AndroidTvTimings from(AndroidTvProperties)`.
  - `AndroidTvSession(Device, ClientCertificate, AndroidTvTimings, Consumer<DeviceState>, ConnectionOpener)`, package-private, where the opener may be null.
  - `dev.andre.homecontrol.adapters.net.Backoff.next(Duration current, Duration max)`, public and static: doubles `current`, and never returns more than `max`. Tasks 6 and 8 use it.

- [ ] **Step 1: Write the failing test**

`src/test/java/dev/andre/homecontrol/adapters/androidtv/AndroidTvTimingsTest.java`:

```java
package dev.andre.homecontrol.adapters.androidtv;

import dev.andre.homecontrol.adapters.net.Backoff;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AndroidTvTimingsTest {

    @Test
    void fromTakesTheConfiguredSeconds() {
        AndroidTvProperties properties = new AndroidTvProperties(Path.of("data"), "shield", false, 10, 1, 4);

        assertThat(AndroidTvTimings.from(properties)).isEqualTo(
                new AndroidTvTimings(Duration.ofSeconds(10), Duration.ofSeconds(1), Duration.ofSeconds(4)));
    }

    @Test
    void backoffDoublesUpToItsCap() {
        Duration max = Duration.ofMillis(300);

        assertThat(Backoff.next(Duration.ofMillis(50), max)).isEqualTo(Duration.ofMillis(100));
        assertThat(Backoff.next(Duration.ofMillis(100), max)).isEqualTo(Duration.ofMillis(200));
        assertThat(Backoff.next(Duration.ofMillis(200), max)).isEqualTo(max);
        assertThat(Backoff.next(max, max)).isEqualTo(max);
        assertThat(Backoff.next(Duration.ofMillis(1500), Duration.ofSeconds(60))).isEqualTo(Duration.ofSeconds(3));
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.adapters.androidtv.AndroidTvTimingsTest'`

Expected: FAIL at `compileTestJava`: `cannot find symbol: class AndroidTvTimings` and `class Backoff`.

- [ ] **Step 3: The record and the backoff step**

`src/main/java/dev/andre/homecontrol/adapters/androidtv/AndroidTvTimings.java`:

```java
package dev.andre.homecontrol.adapters.androidtv;

import java.time.Duration;

/**
 * The waits of an {@link AndroidTvSession}: the idle time after which a connection is stale, and the reconnect
 * backoff. Production builds them from {@link AndroidTvProperties}; tests pass milliseconds.
 */
record AndroidTvTimings(Duration staleTimeout, Duration reconnectInitialDelay, Duration reconnectMaxDelay) {

    static AndroidTvTimings from(AndroidTvProperties properties) {
        return new AndroidTvTimings(Duration.ofSeconds(properties.staleTimeoutSeconds()),
                Duration.ofSeconds(properties.reconnectInitialDelaySeconds()),
                Duration.ofSeconds(properties.reconnectMaxDelaySeconds()));
    }
}
```

`src/main/java/dev/andre/homecontrol/adapters/net/Backoff.java`:

```java
package dev.andre.homecontrol.adapters.net;

import java.time.Duration;

/** The doubling reconnect backoff the device sessions share. */
public final class Backoff {

    private Backoff() {
    }

    /** Twice {@code current}, but never more than {@code max}. */
    public static Duration next(Duration current, Duration max) {
        Duration doubled = current.multipliedBy(2);
        return doubled.compareTo(max) > 0 ? max : doubled;
    }
}
```

- [ ] **Step 4: `AndroidTvSession` takes the record**

In `AndroidTvSession.java`:
1. The field `private final AndroidTvProperties properties;` becomes `private final AndroidTvTimings timings;`.
2. The public constructor keeps its signature, and its body becomes `this(device, credential, AndroidTvTimings.from(properties), onChange, null);`.
3. In the package-private constructor, the parameter `AndroidTvProperties properties` becomes `AndroidTvTimings timings`, and in its body:
   - `this.properties = properties;` becomes `this.timings = timings;`;
   - `properties.staleTimeoutSeconds() * 1000` becomes `Math.toIntExact(timings.staleTimeout().toMillis())`;
   - `this.backoff = Duration.ofSeconds(properties.reconnectInitialDelaySeconds());` becomes `this.backoff = timings.reconnectInitialDelay();`.
4. In `handleReady()`, `backoff = Duration.ofSeconds(properties.reconnectInitialDelaySeconds());` becomes `backoff = timings.reconnectInitialDelay();`.
5. In `scheduleReconnect()`, replace

   ```java
           backoff = Duration.ofSeconds(Math.min(
                   backoff.toSeconds() * 2, properties.reconnectMaxDelaySeconds()));
           scheduler.schedule(this::connect, delay.toSeconds(), TimeUnit.SECONDS);
   ```

   with

   ```java
           backoff = Backoff.next(backoff, timings.reconnectMaxDelay());
           scheduler.schedule(this::connect, delay.toMillis(), TimeUnit.MILLISECONDS);
   ```

   and add `import dev.andre.homecontrol.adapters.net.Backoff;`.
6. Check: `grep -n 'properties' AndroidTvSession.java` shows only the public constructor's parameter and its `from(properties)` call.

`AndroidTvAdapter` needs no change: it calls the public constructor.

- [ ] **Step 5: The session test runs at milliseconds**

In `AndroidTvSessionTest`, replace the three `AndroidTvProperties` constants (`PROPERTIES`, `FAST_RETRY`, `SHORT_TIMEOUT`) with:

```java
    /** A 10 s stale timeout, longer than any test; backoff 50–200 ms. */
    private static final AndroidTvTimings TIMINGS =
            new AndroidTvTimings(Duration.ofSeconds(10), Duration.ofMillis(50), Duration.ofMillis(200));
    /** A flat 50 ms retry ramp, so tests that need several attempts take well under a second. */
    private static final AndroidTvTimings FAST_RETRY =
            new AndroidTvTimings(Duration.ofSeconds(10), Duration.ofMillis(50), Duration.ofMillis(50));
    /** A 1 s stale timeout for the stalled-handshake test, retrying like FAST_RETRY. */
    private static final AndroidTvTimings SHORT_TIMEOUT =
            new AndroidTvTimings(Duration.ofSeconds(1), Duration.ofMillis(50), Duration.ofMillis(50));
```

Then:
- Every `new AndroidTvSession(<device>, <credential>, PROPERTIES|FAST_RETRY|SHORT_TIMEOUT, <listener>)` with four arguments gets a fifth argument, `null`, and the timings constant in place of the properties constant: `PROPERTIES` becomes `TIMINGS`.
- The one call that already passes an opener keeps it.
- The helper `sessionWith(AndroidTvProperties properties)` takes `AndroidTvTimings timings` and passes it with `null`.
- Remove the now-unused `java.nio.file.Path` import if nothing else uses it.

The `await().atMost(...)` ceilings stay as they are.

- [ ] **Step 6: Run the tests**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.adapters.androidtv.*' --tests 'dev.andre.homecontrol.device.*'`

Expected: PASS. `AndroidTvSessionTest`'s class time falls from about 43 s to under 15 s.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/adapters/androidtv/AndroidTvTimings.java src/main/java/dev/andre/homecontrol/adapters/net/Backoff.java src/test/java/dev/andre/homecontrol/adapters/androidtv/AndroidTvTimingsTest.java src/main/java/dev/andre/homecontrol/adapters/androidtv/AndroidTvSession.java src/test/java/dev/andre/homecontrol/adapters/androidtv/AndroidTvSessionTest.java
git commit -m "refactor: give the Android TV session its waits as Durations

AndroidTvTimings is built from the unchanged properties; the backoff
doubles in Durations and schedules in milliseconds, so tests retry every
50 ms instead of every second."
```

End the message with your `Co-Authored-By:` trailer.

### Task 6: `CastTimings`, and shorter `CastConnectionTest` durations

**Files:**
- Create: `src/main/java/dev/andre/homecontrol/adapters/cast/CastTimings.java`
- Test: `src/test/java/dev/andre/homecontrol/adapters/cast/CastTimingsTest.java`
- Modify: `src/main/java/dev/andre/homecontrol/adapters/cast/CastSession.java`
- Modify: `src/test/java/dev/andre/homecontrol/adapters/cast/CastSessionTest.java`
- Modify: `src/test/java/dev/andre/homecontrol/adapters/cast/protocol/CastConnectionTest.java`

**Interfaces:**
- Consumes: `Backoff.next(Duration, Duration)` (Task 5), and `CastProperties` (unchanged).
- Produces:
  - `record CastTimings(Duration heartbeatInterval, Duration staleTimeout, Duration reconnectInitialDelay, Duration reconnectMaxDelay, Duration commandTimeout, Duration loadTimeout, Duration mediaStatusInterval, Duration customMessageErrorWindow)`, with `static CastTimings from(CastProperties)` and the constant `CUSTOM_MESSAGE_ERROR_WINDOW` (750 ms).
  - `CastSession(Device, CastTimings, Consumer<DeviceState>)`, package-private.

- [ ] **Step 1: Write the failing test**

`src/test/java/dev/andre/homecontrol/adapters/cast/CastTimingsTest.java`:

```java
package dev.andre.homecontrol.adapters.cast;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class CastTimingsTest {

    @Test
    void fromTakesTheConfiguredSeconds() {
        CastProperties properties = new CastProperties(true, 5, 15, 1, 60, 5, 20, 5);

        assertThat(CastTimings.from(properties)).isEqualTo(new CastTimings(Duration.ofSeconds(5), Duration.ofSeconds(15),
                Duration.ofSeconds(1), Duration.ofSeconds(60), Duration.ofSeconds(5), Duration.ofSeconds(20),
                Duration.ofSeconds(5), Duration.ofMillis(750)));
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.adapters.cast.CastTimingsTest'`

Expected: FAIL at `compileTestJava`: `cannot find symbol: class CastTimings`.

- [ ] **Step 3: The record**

`src/main/java/dev/andre/homecontrol/adapters/cast/CastTimings.java`:

```java
package dev.andre.homecontrol.adapters.cast;

import java.time.Duration;

/**
 * The waits of a {@link CastSession}. Production builds them from {@link CastProperties}; tests pass milliseconds.
 * {@code customMessageErrorWindow}: receivers validate a custom request synchronously, and media loading continues
 * after the session returns, so a rejection that has not arrived within this window counts as accepted.
 */
record CastTimings(Duration heartbeatInterval, Duration staleTimeout, Duration reconnectInitialDelay,
                   Duration reconnectMaxDelay, Duration commandTimeout, Duration loadTimeout,
                   Duration mediaStatusInterval, Duration customMessageErrorWindow) {

    static final Duration CUSTOM_MESSAGE_ERROR_WINDOW = Duration.ofMillis(750);

    static CastTimings from(CastProperties properties) {
        return new CastTimings(Duration.ofSeconds(properties.heartbeatIntervalSeconds()),
                Duration.ofSeconds(properties.staleTimeoutSeconds()),
                Duration.ofSeconds(properties.reconnectInitialDelaySeconds()),
                Duration.ofSeconds(properties.reconnectMaxDelaySeconds()),
                Duration.ofSeconds(properties.commandTimeoutSeconds()),
                Duration.ofSeconds(properties.loadTimeoutSeconds()),
                Duration.ofSeconds(properties.mediaStatusIntervalSeconds()),
                CUSTOM_MESSAGE_ERROR_WINDOW);
    }
}
```

- [ ] **Step 4: `CastSession` takes the record**

In `CastSession.java`:
1. The field `private final CastProperties properties;` becomes `private final CastTimings timings;`.
2. Replace the constructor with:

   ```java
       public CastSession(Device device, CastProperties properties, Consumer<DeviceState> onChange) {
           this(device, CastTimings.from(properties), onChange);
       }

       CastSession(Device device, CastTimings timings, Consumer<DeviceState> onChange) {
           this.device = device;
           this.settings = CastSettings.of(device);
           this.timings = timings;
           this.onChange = onChange;
           this.backoff = timings.reconnectInitialDelay();
           this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
               Thread thread = new Thread(runnable, "cast-session-" + device.id());
               thread.setDaemon(true);
               return thread;
           });
       }
   ```

3. In `start()`, replace

   ```java
           long interval = properties.mediaStatusIntervalSeconds();
           scheduler.scheduleWithFixedDelay(this::pollMediaPosition, interval, interval, TimeUnit.SECONDS);
   ```

   with

   ```java
           long interval = timings.mediaStatusInterval().toMillis();
           scheduler.scheduleWithFixedDelay(this::pollMediaPosition, interval, interval, TimeUnit.MILLISECONDS);
   ```

4. Delete the constant `CUSTOM_MESSAGE_ERROR_WINDOW` and its Javadoc line; its doc now lives on the record. Replace `rejection.await(CUSTOM_MESSAGE_ERROR_WINDOW)` with `rejection.await(timings.customMessageErrorWindow())`.
5. The body of `commandTimeout()` becomes `return timings.commandTimeout();`, and the body of `loadTimeout()` becomes `return timings.loadTimeout();`.
6. In `connect()`:
   - replace `Duration.ofSeconds(properties.heartbeatIntervalSeconds())` with `timings.heartbeatInterval()`;
   - replace `Duration.ofSeconds(properties.staleTimeoutSeconds())` with `timings.staleTimeout()`;
   - replace `backoff = Duration.ofSeconds(properties.reconnectInitialDelaySeconds());` with `backoff = timings.reconnectInitialDelay();`.
7. In `scheduleReconnect()`:
   - replace `backoff = Duration.ofSeconds(Math.min(backoff.toSeconds() * 2, properties.reconnectMaxDelaySeconds()));` with `backoff = Backoff.next(backoff, timings.reconnectMaxDelay());`;
   - replace `scheduler.schedule(this::connect, delay.toSeconds(), TimeUnit.SECONDS);` with `scheduler.schedule(this::connect, delay.toMillis(), TimeUnit.MILLISECONDS);`;
   - add `import dev.andre.homecontrol.adapters.net.Backoff;`.
8. Check: `grep -n 'properties' CastSession.java` shows only the public constructor.

`CastAdapter` needs no change.

- [ ] **Step 5: The session test runs at milliseconds**

In `CastSessionTest`, replace the `PROPERTIES` constant and its doc comment with:

```java
    /**
     * Heartbeat 200 ms, stale 1 s, backoff 50–100 ms, command 1 s, load 5 s, media poll 100 ms, custom-message
     * window 200 ms.
     */
    static final CastTimings TIMINGS = new CastTimings(Duration.ofMillis(200), Duration.ofSeconds(1),
            Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofSeconds(1), Duration.ofSeconds(5),
            Duration.ofMillis(100), Duration.ofMillis(200));
```

Then:
- Replace `PROPERTIES` with `TIMINGS` in the three `new CastSession(...)` calls. Other classes that reference `CastSessionTest.PROPERTIES`, found with `git grep -n 'CastSessionTest.PROPERTIES'`, use `TIMINGS` too.
- In `aClosedSessionPublishesNothingAndDoesNotReconnect`, the wait with `during(Duration.ofMillis(2_500))` and the comment `// Longer than the 1–2 s reconnect backoff.` becomes `during(Duration.ofMillis(500))` with the comment `// Five times the 50–100 ms reconnect backoff.`. Its `atMost(Duration.ofSeconds(4))` becomes `atMost(Duration.ofSeconds(2))`.

Every other wait stays as it is.

- [ ] **Step 6: Shorter durations in `CastConnectionTest`**

`CastConnection.open(...)` already takes `Duration`s, so there is no production change. In `CastConnectionTest`:
- In `@BeforeEach`, `Duration.ofSeconds(1), Duration.ofSeconds(3)` becomes `Duration.ofMillis(200), Duration.ofSeconds(1)`.
- In the test that opens against an unused port, `Duration.ofSeconds(1), Duration.ofSeconds(3)` becomes `Duration.ofMillis(200), Duration.ofSeconds(1)`.
- The rest stays: the `interval, interval` validation test, the request timeouts, and the `atMost` ceilings.

- [ ] **Step 7: Run the tests**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.adapters.cast.*' --tests 'dev.andre.homecontrol.web.CastEndToEndTest'`

Expected: PASS. The class times of `CastSessionTest` (about 35 s) and `CastConnectionTest` (about 10 s) both fall.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/adapters/cast/CastTimings.java src/test/java/dev/andre/homecontrol/adapters/cast/CastTimingsTest.java src/main/java/dev/andre/homecontrol/adapters/cast/CastSession.java src/test/java/dev/andre/homecontrol/adapters/cast/CastSessionTest.java src/test/java/dev/andre/homecontrol/adapters/cast/protocol/CastConnectionTest.java
git commit -m "refactor: give the Cast session its waits as Durations

CastTimings is built from the unchanged properties plus the 750 ms
custom-message window; scheduling and backoff work in milliseconds, so
the Cast tests run with 50-200 ms timings."
```

End the message with your `Co-Authored-By:` trailer.

### Task 7: `TizenTimings`, with the handshake-backoff cap as a parameter

**Files:**
- Create: `src/main/java/dev/andre/homecontrol/adapters/tizen/TizenTimings.java`
- Test: `src/test/java/dev/andre/homecontrol/adapters/tizen/TizenTimingsTest.java`
- Modify: `src/main/java/dev/andre/homecontrol/adapters/tizen/TizenSession.java`
- Modify: `src/main/java/dev/andre/homecontrol/adapters/tizen/TizenAdapter.java`
- Modify: `src/test/java/dev/andre/homecontrol/adapters/tizen/TizenSessionTest.java`

**Interfaces:**
- Consumes: `TizenProperties` (unchanged).
- Produces:
  - `record TizenTimings(Duration pollInterval, Duration wakeGrace, Duration requestTimeout, Duration handshakeBackoffCap)`, with `static TizenTimings from(TizenProperties)` and the constant `HANDSHAKE_BACKOFF_CAP` (5 minutes).
  - The package-private constructor gains a parameter: `TizenSession(Device, TizenProperties, TizenTimings, HttpClient, DeviceRegistry, LearnedSettings, WakeOnLan, Consumer<DeviceState>, Runnable)`.

- [ ] **Step 1: Write the failing test**

`src/test/java/dev/andre/homecontrol/adapters/tizen/TizenTimingsTest.java`:

```java
package dev.andre.homecontrol.adapters.tizen;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class TizenTimingsTest {

    @Test
    void fromTakesTheConfiguredSecondsAndCapsTheHandshakeBackoffAtFiveMinutes() {
        TizenProperties properties = new TizenProperties(true, 8002, 8001, 8080, "Home Control", 3, 5, 30, 5, 3);

        assertThat(TizenTimings.from(properties)).isEqualTo(new TizenTimings(Duration.ofSeconds(5), Duration.ofSeconds(3),
                Duration.ofSeconds(5), Duration.ofMinutes(5)));
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.adapters.tizen.TizenTimingsTest'`

Expected: FAIL at `compileTestJava`: `cannot find symbol: class TizenTimings`.

- [ ] **Step 3: The record**

`src/main/java/dev/andre/homecontrol/adapters/tizen/TizenTimings.java`:

```java
package dev.andre.homecontrol.adapters.tizen;

import java.time.Duration;

/**
 * The waits of a {@link TizenSession}: how often it polls, the grace after a Wake-on-LAN packet, how long a
 * connection waits for the TV's Allow/Deny, and the cap of the backoff after an unanswered one. Production builds
 * them from {@link TizenProperties}; tests pass milliseconds.
 */
record TizenTimings(Duration pollInterval, Duration wakeGrace, Duration requestTimeout, Duration handshakeBackoffCap) {

    static final Duration HANDSHAKE_BACKOFF_CAP = Duration.ofMinutes(5);

    static TizenTimings from(TizenProperties properties) {
        return new TizenTimings(Duration.ofSeconds(properties.pollIntervalSeconds()),
                Duration.ofSeconds(properties.wakeGraceSeconds()),
                Duration.ofSeconds(properties.requestTimeoutSeconds()),
                HANDSHAKE_BACKOFF_CAP);
    }
}
```

- [ ] **Step 4: `TizenSession` takes the record**

In `TizenSession.java`:
1. Delete `private static final Duration MAX_HANDSHAKE_BACKOFF = Duration.ofMinutes(5);`. Add the field `private final TizenTimings timings;`.
2. The constructor gets `TizenTimings timings` after `TizenProperties properties`, and assigns `this.timings = timings;`. `properties` stays: it still feeds `TizenRest`, `DialClient` and `TizenRemoteConnection.open(...)`.
3. In `start()`, the schedule call becomes `scheduler.scheduleWithFixedDelay(this::poll, 0, timings.pollInterval().toMillis(), TimeUnit.MILLISECONDS);`.
4. The wake-grace schedule becomes `scheduler.schedule(this::poll, timings.wakeGrace().toMillis(), TimeUnit.MILLISECONDS);`.
5. `opened.awaitAuthorization(Duration.ofSeconds(properties.requestTimeoutSeconds()));` becomes `opened.awaitAuthorization(timings.requestTimeout());`.
6. The log line becomes:

   ```java
                   log.info("{} did not answer the connection within {} ms; retrying in {} ms",
                           device.name(), timings.requestTimeout().toMillis(), delay.toMillis());
   ```

7. In `nextHandshakeBackoff()`:
   - `Duration first = Duration.ofSeconds(2L * properties.pollIntervalSeconds());` becomes `Duration first = timings.pollInterval().multipliedBy(2);`;
   - both uses of `MAX_HANDSHAKE_BACKOFF` become `timings.handshakeBackoffCap()`;
   - its Javadoc reads: `/** Doubles from two poll intervals up to the handshake-backoff cap; the next connect waits that long. */`.

In `TizenAdapter.java`, the construction becomes `new TizenSession(device, properties, TizenTimings.from(properties), http, registry, learned, wakeOnLan, onChange, ...)`, with the last argument unchanged.

- [ ] **Step 5: The session test runs at milliseconds**

In `TizenSessionTest`, add:

```java
    /** Poll 200 ms (so the first handshake backoff is 400 ms), no wake grace, Allow/Deny within 500 ms. */
    private static final TizenTimings TIMINGS =
            new TizenTimings(Duration.ofMillis(200), Duration.ZERO, Duration.ofMillis(500), TizenTimings.HANDSHAKE_BACKOFF_CAP);
```

In `start(Map<String, String> settings)`, the construction becomes `new TizenSession(device, TizenRestTest.properties(tv), TIMINGS, InsecureTls.httpClient(Duration.ofSeconds(2)), registry, learned(), new WakeOnLan(receiver.address()), states, () -> { });`.

Change these waits:

| Test | Old | New |
| --- | --- | --- |
| `aHandEnteredMacIsNotOverwritten` | `// Every poll (1 s interval) reads another MAC from the REST API.` + `during(Duration.ofMillis(1500)).atMost(Duration.ofSeconds(3))` | `// Every poll (200 ms interval) reads another MAC from the REST API.` + `during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3))` |
| `aRejectedTokenIsUnpairedAndStopsTrying` | `during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(4))` | `during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(2))` |
| `neverConnectsWithoutPairing` | `during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(3))` | `during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(2))` |
| `closeStopsPolling` | `during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(4))` | `during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(2))` |

Replace `anUnansweredHandshakeIsTransientKeepsTheTokenAndRetriesWithBackoff` with the version below. It has no elapsed-time arithmetic. The retry cannot come earlier than 900 ms after the first connection: 500 ms of request timeout plus a 400 ms backoff. So nothing may happen in the 600 ms after that connection is seen. Without the backoff, the retry would land within the next 200 ms poll after the request timeout.

```java
    @Test
    void anUnansweredHandshakeIsTransientKeepsTheTokenAndRetriesWithBackoff() {
        tv.setAuthorization(FakeTizenServer.Authorization.IGNORE);
        start(Map.of("paired", "true", "token", "999"));

        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(10)).until(() -> tv.connections() == 1);
        // 500 ms request timeout plus a 400 ms backoff before the next attempt.
        await("no retry during the request timeout and the backoff").during(Duration.ofMillis(600))
                .atMost(Duration.ofSeconds(2)).until(() -> tv.connections() == 1);
        assertThat(session.state().status()).isEqualTo(DeviceStatus.DISCONNECTED);
        assertThat(stored("token")).isEqualTo("999");
        assertThat(stored("paired")).isEqualTo("true");

        await().atMost(Duration.ofSeconds(5)).until(() -> tv.connections() >= 2);
        assertThat(states.all()).noneMatch(state -> state.status() == DeviceStatus.UNPAIRED);

        tv.setAuthorization(FakeTizenServer.Authorization.ALLOW);
        await().atMost(Duration.ofSeconds(10)).until(() -> session.state().status() == DeviceStatus.CONNECTED);
        assertThat(stored("token")).isEqualTo(FakeTizenServer.TOKEN);
    }
```

- [ ] **Step 6: Run the tests**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.adapters.tizen.*' --tests 'dev.andre.homecontrol.web.TizenEndToEndTest'`

Expected:
- PASS.
- `git grep -n nanoTime -- src/test/java/dev/andre/homecontrol/adapters/tizen/TizenSessionTest.java` prints nothing.
- `TizenSessionTest`'s class time falls from about 35 s to under 12 s.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/adapters/tizen/TizenTimings.java src/test/java/dev/andre/homecontrol/adapters/tizen/TizenTimingsTest.java src/main/java/dev/andre/homecontrol/adapters/tizen/TizenSession.java src/main/java/dev/andre/homecontrol/adapters/tizen/TizenAdapter.java src/test/java/dev/andre/homecontrol/adapters/tizen/TizenSessionTest.java
git commit -m "refactor: give the Tizen session its waits as Durations

TizenTimings is built from the unchanged properties plus the 5-minute
handshake-backoff cap; polling and the wake grace schedule in
milliseconds. The handshake test checks the order of attempts instead of
their elapsed time."
```

End the message with your `Co-Authored-By:` trailer.

### Task 8: `WebOsTimings`

**Files:**
- Create: `src/main/java/dev/andre/homecontrol/adapters/webos/WebOsTimings.java`
- Test: `src/test/java/dev/andre/homecontrol/adapters/webos/WebOsTimingsTest.java`
- Modify: `src/main/java/dev/andre/homecontrol/adapters/webos/WebOsSession.java`
- Modify: `src/main/java/dev/andre/homecontrol/adapters/webos/WebOsAdapter.java`
- Modify: `src/test/java/dev/andre/homecontrol/adapters/webos/WebOsSessionTest.java`

**Interfaces:**
- Consumes: `Backoff.next(Duration, Duration)` (Task 5), and `WebOsProperties` (unchanged).
- Produces:
  - `record WebOsTimings(Duration reconnectInitialDelay, Duration reconnectMaxDelay, Duration wakeGrace, Duration livenessInterval, Duration registerTimeout)`, with `static WebOsTimings from(WebOsProperties)`.
  - The package-private constructor gains a parameter: `WebOsSession(Device, WebOsProperties, WebOsTimings, HttpClient, DeviceRegistry, LearnedSettings, WakeOnLan, Consumer<DeviceState>, Runnable)`.

- [ ] **Step 1: Write the failing test**

`src/test/java/dev/andre/homecontrol/adapters/webos/WebOsTimingsTest.java`:

```java
package dev.andre.homecontrol.adapters.webos;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class WebOsTimingsTest {

    @Test
    void fromTakesTheConfiguredSeconds() {
        WebOsProperties properties = new WebOsProperties(true, 3000, 3001, 3, 10, 60, 1, 30, 3, 30);

        assertThat(WebOsTimings.from(properties)).isEqualTo(new WebOsTimings(Duration.ofSeconds(1), Duration.ofSeconds(30),
                Duration.ofSeconds(3), Duration.ofSeconds(30), Duration.ofSeconds(10)));
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.adapters.webos.WebOsTimingsTest'`

Expected: FAIL at `compileTestJava`: `cannot find symbol: class WebOsTimings`.

- [ ] **Step 3: The record**

`src/main/java/dev/andre/homecontrol/adapters/webos/WebOsTimings.java`:

```java
package dev.andre.homecontrol.adapters.webos;

import java.time.Duration;

/**
 * The waits of a {@link WebOsSession}: the reconnect backoff, the grace after a Wake-on-LAN packet, how often a
 * connected TV is asked a cheap question, and how long registering with a stored key may take. Production builds
 * them from {@link WebOsProperties}; tests pass milliseconds.
 */
record WebOsTimings(Duration reconnectInitialDelay, Duration reconnectMaxDelay, Duration wakeGrace,
                    Duration livenessInterval, Duration registerTimeout) {

    static WebOsTimings from(WebOsProperties properties) {
        return new WebOsTimings(Duration.ofSeconds(properties.reconnectInitialDelaySeconds()),
                Duration.ofSeconds(properties.reconnectMaxDelaySeconds()),
                Duration.ofSeconds(properties.wakeGraceSeconds()),
                Duration.ofSeconds(properties.livenessIntervalSeconds()),
                Duration.ofSeconds(properties.requestTimeoutSeconds()));
    }
}
```

- [ ] **Step 4: `WebOsSession` takes the record**

In `WebOsSession.java`:
1. Add the field `private final WebOsTimings timings;`. The constructor gets `WebOsTimings timings` after `WebOsProperties properties`, and assigns `this.timings = timings;` before `this.backoff = initialBackoff();`. `properties` stays: it feeds `SsapConnection.open(...)`.
2. In `start()`, replace

   ```java
           long interval = properties.livenessIntervalSeconds();
   ```

   and `..., interval, interval, TimeUnit.SECONDS);` with

   ```java
           long interval = timings.livenessInterval().toMillis();
   ```

   and `..., interval, interval, TimeUnit.MILLISECONDS);`.
3. `pendingConnect = scheduler.schedule(this::connect, properties.wakeGraceSeconds(), TimeUnit.SECONDS);` becomes `pendingConnect = scheduler.schedule(this::connect, timings.wakeGrace().toMillis(), TimeUnit.MILLISECONDS);`.
4. `opened.register(clientKey, Duration.ofSeconds(properties.requestTimeoutSeconds()))` becomes `opened.register(clientKey, timings.registerTimeout())`.
5. In `scheduleReconnect()`, `backoff = Duration.ofSeconds(Math.clamp(backoff.toSeconds() * 2, 1, properties.reconnectMaxDelaySeconds()));` becomes `backoff = Backoff.next(backoff, timings.reconnectMaxDelay());`. The backoff starts at the initial delay and only grows, so the old clamp's lower bound is kept. Add `import dev.andre.homecontrol.adapters.net.Backoff;`.
6. The body of `initialBackoff()` becomes `return timings.reconnectInitialDelay();`.

In `WebOsAdapter.java`, the construction becomes `new WebOsSession(device, properties, WebOsTimings.from(properties), http, registry, learned, wakeOnLan, onChange, ...)`, with the last argument unchanged.

- [ ] **Step 5: The session test runs at milliseconds**

In `WebOsSessionTest`, add:

```java
    /** Backoff 50–100 ms, no wake grace, liveness every 30 s (off for most tests), register within 2 s. */
    private static final WebOsTimings TIMINGS = new WebOsTimings(Duration.ofMillis(50), Duration.ofMillis(100),
            Duration.ZERO, Duration.ofSeconds(30), Duration.ofSeconds(2));
    /** As TIMINGS, but a liveness check every 200 ms, for the liveness tests. */
    private static final WebOsTimings LIVENESS = new WebOsTimings(Duration.ofMillis(50), Duration.ofMillis(100),
            Duration.ZERO, Duration.ofMillis(200), Duration.ofSeconds(1));
```

Then:
- The main helper's `new WebOsSession(device, properties, InsecureTls.httpClient(...), ...)` gets `TIMINGS` after `properties`.
- The two liveness tests' constructions, which use the 10-argument `WebOsProperties`, get `LIVENESS` after `properties`.
- The `WebOsProperties` values stay: `SsapConnection` reads its timeouts from them.

Change these waits:

| Where | Old | New |
| --- | --- | --- |
| the test waiting on `tv.registrations() == 1` | `during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(4))` | `during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(2))` |
| the test waiting on `tv.connections() == 0` | `during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(2))` | `during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2))` |
| the test that closes the session and then waits on `states.all().isEmpty()` | `during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(3))` | `during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(2))` |

The liveness test's `during(Duration.ofMillis(2500))` stays. Each liveness check waits out `SsapConnection`'s 1 s request timeout, which keeps its seconds (plan decision 2). Its comment becomes: `// Two more liveness checks (200 ms interval, 1 s request timeout) come and go.`

- [ ] **Step 6: Run the tests**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.adapters.webos.*' --tests 'dev.andre.homecontrol.web.WebOsEndToEndTest'`

Expected: PASS. `WebOsSessionTest`'s class time (about 27 s) falls.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/adapters/webos/WebOsTimings.java src/test/java/dev/andre/homecontrol/adapters/webos/WebOsTimingsTest.java src/main/java/dev/andre/homecontrol/adapters/webos/WebOsSession.java src/main/java/dev/andre/homecontrol/adapters/webos/WebOsAdapter.java src/test/java/dev/andre/homecontrol/adapters/webos/WebOsSessionTest.java
git commit -m "refactor: give the webOS session its waits as Durations

WebOsTimings is built from the unchanged properties; the liveness check
and the wake grace schedule in milliseconds and the backoff doubles in
Durations, so the webOS tests reconnect every 50-100 ms."
```

End the message with your `Co-Authored-By:` trailer.

### Task 9: `BluetoothTimings`

**Files:**
- Create: `src/main/java/dev/andre/homecontrol/adapters/bluetooth/BluetoothTimings.java`
- Test: `src/test/java/dev/andre/homecontrol/adapters/bluetooth/BluetoothTimingsTest.java`
- Modify: `src/main/java/dev/andre/homecontrol/adapters/bluetooth/BluetoothSpeakerSession.java`
- Modify: `src/test/java/dev/andre/homecontrol/adapters/bluetooth/BluetoothSpeakerSessionTest.java`

**Interfaces:**
- Consumes: `BluetoothProperties` (unchanged).
- Produces:
  - `record BluetoothTimings(Duration pollInterval, Duration playingPollInterval)`, with `static BluetoothTimings from(BluetoothProperties)`.
  - `BluetoothSpeakerSession(Device, BluetoothProperties, BluetoothTimings, BluezClient, MpvPlayer, AudioDeviceResolver, Consumer<DeviceState>)`, package-private. The public 6-argument constructor keeps its signature.

- [ ] **Step 1: Write the failing test**

`src/test/java/dev/andre/homecontrol/adapters/bluetooth/BluetoothTimingsTest.java`:

```java
package dev.andre.homecontrol.adapters.bluetooth;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class BluetoothTimingsTest {

    @Test
    void fromTakesTheConfiguredSeconds() {
        assertThat(BluetoothTimings.from(BluetoothProperties.defaults()))
                .isEqualTo(new BluetoothTimings(Duration.ofSeconds(5), Duration.ofSeconds(1)));
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.adapters.bluetooth.BluetoothTimingsTest'`

Expected: FAIL at `compileTestJava`: `cannot find symbol: class BluetoothTimings`.

- [ ] **Step 3: The record**

`src/main/java/dev/andre/homecontrol/adapters/bluetooth/BluetoothTimings.java`:

```java
package dev.andre.homecontrol.adapters.bluetooth;

import java.time.Duration;

/**
 * How often a {@link BluetoothSpeakerSession} polls BlueZ, idle and while playing. Production builds them from
 * {@link BluetoothProperties}; tests pass milliseconds.
 */
record BluetoothTimings(Duration pollInterval, Duration playingPollInterval) {

    static BluetoothTimings from(BluetoothProperties properties) {
        return new BluetoothTimings(Duration.ofSeconds(properties.pollIntervalSeconds()),
                Duration.ofSeconds(properties.playingPollIntervalSeconds()));
    }
}
```

- [ ] **Step 4: `BluetoothSpeakerSession` takes the record**

In `BluetoothSpeakerSession.java`:
1. Add the field `private final BluetoothTimings timings;`.
2. Replace the constructor with:

   ```java
       public BluetoothSpeakerSession(Device device, BluetoothProperties properties, BluezClient bluez, MpvPlayer player,
                                      AudioDeviceResolver audioDevices, Consumer<DeviceState> onChange) {
           this(device, properties, BluetoothTimings.from(properties), bluez, player, audioDevices, onChange);
       }

       BluetoothSpeakerSession(Device device, BluetoothProperties properties, BluetoothTimings timings, BluezClient bluez,
                               MpvPlayer player, AudioDeviceResolver audioDevices, Consumer<DeviceState> onChange) {
   ```

   and keep the old constructor's body, adding `this.timings = timings;` after `this.properties = properties;`.
3. `nextPoll = loop.schedule(this::poll, nextPollSeconds(), TimeUnit.SECONDS);` becomes `nextPoll = loop.schedule(this::poll, nextPollDelay().toMillis(), TimeUnit.MILLISECONDS);`.
4. Replace the method `nextPollSeconds()` with:

   ```java
       private Duration nextPollDelay() {
           return player.active() ? timings.playingPollInterval() : timings.pollInterval();
       }
   ```

   and add `import java.time.Duration;` if it is missing.

`BluetoothSpeakerAdapter` needs no change.

- [ ] **Step 5: The session test polls every 100 ms**

In `BluetoothSpeakerSessionTest`:
- Add `private static final BluetoothTimings TIMINGS = new BluetoothTimings(Duration.ofMillis(100), Duration.ofMillis(100));`.
- In the `start(BluetoothProperties props, Consumer<...> listener)` helper, the construction becomes `new BluetoothSpeakerSession(device, props, TIMINGS, bluez, player, resolver, listener)`. The properties keep their 1 s poll values: only the player's timeouts are read from them now.

Rewrite `aSlowBluezDoesNotPileUpPolls` at the same ratios, with a 400 ms read against a 100 ms poll over a 1 s window:

```java
    @Test
    void aSlowBluezDoesNotPileUpPolls() {
        bluez.known("AA:BB:CC:DD:EE:FF", "JBL Flip 5").paired(true).connected(true).uuids(BluetoothDeviceInfo.A2DP_SINK);
        bluez.delay("device", Duration.ofMillis(400));
        start(properties);

        // Four times the 100 ms poll interval per read: polls that piled up would read about ten times in a second.
        await().pollDelay(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(bluez.reads()).isLessThanOrEqualTo(4));
    }
```

- [ ] **Step 6: Run the tests**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.adapters.bluetooth.*'`

Expected: PASS. `BluetoothSpeakerSessionTest`'s class time falls from about 14 s to under 7 s.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/adapters/bluetooth/BluetoothTimings.java src/test/java/dev/andre/homecontrol/adapters/bluetooth/BluetoothTimingsTest.java src/main/java/dev/andre/homecontrol/adapters/bluetooth/BluetoothSpeakerSession.java src/test/java/dev/andre/homecontrol/adapters/bluetooth/BluetoothSpeakerSessionTest.java
git commit -m "refactor: give the Bluetooth speaker session its poll intervals as Durations

The session polls in milliseconds from BluetoothTimings, built from the
unchanged properties; its tests poll every 100 ms, and the slow-BlueZ
test keeps its ratios in a 1 s window instead of 5 s."
```

End the message with your `Co-Authored-By:` trailer.

### Task 10: `SsdpTimings`

**Files:**
- Create: `src/main/java/dev/andre/homecontrol/discovery/ssdp/SsdpTimings.java`
- Test: `src/test/java/dev/andre/homecontrol/discovery/ssdp/SsdpTimingsTest.java`
- Modify: `src/main/java/dev/andre/homecontrol/discovery/ssdp/SsdpDiscovery.java`
- Modify, the active discoveries in the tests: `discovery/ssdp/SsdpDiscoveryTest.java`, `adapters/upnp/UpnpDiscoveryTest.java`, `adapters/sonos/SonosDiscoveryTest.java`, `adapters/webos/WebOsAdapterTest.java`, `adapters/tizen/TizenAdapterTest.java`

**Interfaces:**
- Consumes: `SsdpProperties` (unchanged).
- Produces:
  - `public record SsdpTimings(Duration searchInterval)`, with `public static SsdpTimings from(SsdpProperties)`.
  - `public SsdpDiscovery(SsdpProperties properties, SsdpTimings timings)`, public because tests in other packages use it.
  - The package-private constructor becomes `SsdpDiscovery(SsdpProperties, SsdpTimings, Clock, HttpClient)`.

- [ ] **Step 1: Write the failing test**

`src/test/java/dev/andre/homecontrol/discovery/ssdp/SsdpTimingsTest.java`:

```java
package dev.andre.homecontrol.discovery.ssdp;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SsdpTimingsTest {

    @Test
    void fromTakesTheConfiguredSeconds() {
        SsdpProperties properties = new SsdpProperties(true, "239.255.255.250", 1900, 1900, 60, 2);

        assertThat(SsdpTimings.from(properties)).isEqualTo(new SsdpTimings(Duration.ofSeconds(60)));
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.discovery.ssdp.SsdpTimingsTest'`

Expected: FAIL at `compileTestJava`: `cannot find symbol: class SsdpTimings`.

- [ ] **Step 3: The record**

`src/main/java/dev/andre/homecontrol/discovery/ssdp/SsdpTimings.java`:

```java
package dev.andre.homecontrol.discovery.ssdp;

import java.time.Duration;

/** How often {@link SsdpDiscovery} searches. Production builds it from {@link SsdpProperties}; tests pass milliseconds. */
public record SsdpTimings(Duration searchInterval) {

    public static SsdpTimings from(SsdpProperties properties) {
        return new SsdpTimings(Duration.ofSeconds(properties.searchIntervalSeconds()));
    }
}
```

- [ ] **Step 4: `SsdpDiscovery` takes the record**

In `SsdpDiscovery.java`:
1. Add the field `private final SsdpTimings timings;`.
2. Replace the two constructors with:

   ```java
       public SsdpDiscovery(SsdpProperties properties) {
           this(properties, SsdpTimings.from(properties));
       }

       public SsdpDiscovery(SsdpProperties properties, SsdpTimings timings) {
           // Embedded UPnP servers reject the "Upgrade: h2c" header the JDK sends by default; never follow redirects.
           this(properties, timings, Clock.systemUTC(), HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                   .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(3)).build());
       }

       SsdpDiscovery(SsdpProperties properties, SsdpTimings timings, Clock clock, HttpClient http) {
   ```

   The package-private constructor keeps its body and adds `this.timings = timings;`.
3. `scheduler.scheduleWithFixedDelay(this::searchAll, 0, properties.searchIntervalSeconds(), TimeUnit.SECONDS);` becomes `scheduler.scheduleWithFixedDelay(this::searchAll, 0, timings.searchInterval().toMillis(), TimeUnit.MILLISECONDS);`.

`SsdpConfiguration` needs no change: it calls `new SsdpDiscovery(properties)`.

- [ ] **Step 5: The active discoveries in the tests search every 200 ms**

Only the discoveries that run, with `enabled = true` and a search interval of 1, change. The disabled ones stay as they are.

- **`SsdpDiscoveryTest`:**
  - The `@BeforeEach` construction becomes `new SsdpDiscovery(new SsdpProperties(true, "127.0.0.1", responder.port(), 0, 1, 1), new SsdpTimings(Duration.ofMillis(200)), clock, HttpClient.newHttpClient())`.
  - `new SsdpDiscovery(new SsdpProperties(true, "127.0.0.1", responder.port(), 0, 1, 1))` in the test that uses a real discovery gets `, new SsdpTimings(Duration.ofMillis(200))` as a second argument.
  - The disabled-discovery wait, `during(Duration.ofMillis(1500)).atMost(Duration.ofSeconds(3))`, becomes `during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(2))`.
- **`UpnpDiscoveryTest`, `SonosDiscoveryTest`, `WebOsAdapterTest` and `TizenAdapterTest`:** every `new SsdpDiscovery(new SsdpProperties(true, <address>, responder.port(), 0, 1, 1))` gets `, new SsdpTimings(Duration.ofMillis(200))` as a second argument. Find them with `git grep -n 'responder.port(), 0, 1, 1)' -- src/test`. The active discoveries that search every 60 s, `(true, "127.0.0.1", FakeWebSocketServer.closedPort(), 0, 60, 1)`, stay as they are. Add `import dev.andre.homecontrol.discovery.ssdp.SsdpTimings;` where needed.
- **`SonosDiscoveryTest`:** the wait `during(Duration.ofMillis(2500)).atMost(Duration.ofSeconds(4))` becomes `during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(2))`, five searches at 200 ms.

- [ ] **Step 6: Run the tests**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.discovery.*' --tests 'dev.andre.homecontrol.adapters.upnp.*' --tests 'dev.andre.homecontrol.adapters.sonos.*' --tests 'dev.andre.homecontrol.adapters.webos.WebOsAdapterTest' --tests 'dev.andre.homecontrol.adapters.tizen.TizenAdapterTest'`

Expected: PASS. `SonosDiscoveryTest`'s class time falls from about 10 s to under 4 s.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/discovery/ssdp/SsdpTimings.java src/test/java/dev/andre/homecontrol/discovery/ssdp/SsdpTimingsTest.java src/main/java/dev/andre/homecontrol/discovery/ssdp/SsdpDiscovery.java src/test/java/dev/andre/homecontrol/discovery/ssdp/SsdpDiscoveryTest.java src/test/java/dev/andre/homecontrol/adapters/upnp/UpnpDiscoveryTest.java src/test/java/dev/andre/homecontrol/adapters/sonos/SonosDiscoveryTest.java src/test/java/dev/andre/homecontrol/adapters/webos/WebOsAdapterTest.java src/test/java/dev/andre/homecontrol/adapters/tizen/TizenAdapterTest.java
git commit -m "refactor: give SSDP discovery its search interval as a Duration

SsdpTimings is built from the unchanged properties; searches schedule in
milliseconds, and the tests that run discovery search every 200 ms."
```

End the message with your `Co-Authored-By:` trailer.

### Task 11: `UpnpTimings` and `SonosTimings`

**Files:**
- Create: `src/main/java/dev/andre/homecontrol/adapters/upnp/UpnpTimings.java`, `src/main/java/dev/andre/homecontrol/adapters/sonos/SonosTimings.java`
- Test: `src/test/java/dev/andre/homecontrol/adapters/upnp/UpnpTimingsTest.java`, `src/test/java/dev/andre/homecontrol/adapters/sonos/SonosTimingsTest.java`
- Modify: `src/main/java/dev/andre/homecontrol/adapters/upnp/UpnpSession.java`, `src/main/java/dev/andre/homecontrol/adapters/sonos/SonosSession.java`
- Modify: `src/test/java/dev/andre/homecontrol/adapters/upnp/UpnpSessionTest.java`, `src/test/java/dev/andre/homecontrol/adapters/sonos/SonosSessionTest.java`

**Interfaces:**
- Consumes: `UpnpProperties` and `SonosProperties` (unchanged); `ReconnectingPoller` (unchanged, already milliseconds).
- Produces:
  - `record UpnpTimings(Duration pollInterval, Duration idlePollInterval, Duration commandTimeout, Duration reconnectInitialDelay, Duration reconnectMaxDelay)`, with `static UpnpTimings from(UpnpProperties)`.
  - `record SonosTimings(Duration pollInterval, Duration idlePollInterval, Duration topologyInterval, Duration commandTimeout, Duration reconnectInitialDelay, Duration reconnectMaxDelay)`, with `static SonosTimings from(SonosProperties)`.
  - For each session, a constructor that takes the record in place of the properties. The existing constructors keep their signatures and call `from(properties)`.

- [ ] **Step 1: Write the failing tests**

`src/test/java/dev/andre/homecontrol/adapters/upnp/UpnpTimingsTest.java`:

```java
package dev.andre.homecontrol.adapters.upnp;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class UpnpTimingsTest {

    @Test
    void fromTakesTheConfiguredSeconds() {
        assertThat(UpnpTimings.from(new UpnpProperties(true, 2, 10, 5, 3, 1, 60))).isEqualTo(new UpnpTimings(
                Duration.ofSeconds(2), Duration.ofSeconds(10), Duration.ofSeconds(5), Duration.ofSeconds(1),
                Duration.ofSeconds(60)));
    }
}
```

`src/test/java/dev/andre/homecontrol/adapters/sonos/SonosTimingsTest.java`:

```java
package dev.andre.homecontrol.adapters.sonos;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SonosTimingsTest {

    @Test
    void fromTakesTheConfiguredSeconds() {
        assertThat(SonosTimings.from(new SonosProperties(true, 2, 10, 30, 5, 3, 1, 60))).isEqualTo(new SonosTimings(
                Duration.ofSeconds(2), Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(5),
                Duration.ofSeconds(1), Duration.ofSeconds(60)));
    }
}
```

(`connectTimeoutSeconds`, the fifth field of `UpnpProperties` and the sixth of `SonosProperties`, is not in the records. It configures the adapters' `HttpClient`, which is outside the session: plan decision 2.)

- [ ] **Step 2: Run them to verify they fail**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.adapters.upnp.UpnpTimingsTest' --tests 'dev.andre.homecontrol.adapters.sonos.SonosTimingsTest'`

Expected: FAIL at `compileTestJava`: `cannot find symbol` for both records.

- [ ] **Step 3: The records**

`src/main/java/dev/andre/homecontrol/adapters/upnp/UpnpTimings.java`:

```java
package dev.andre.homecontrol.adapters.upnp;

import java.time.Duration;

/** The waits of a {@link UpnpSession}. Production builds them from {@link UpnpProperties}; tests pass milliseconds. */
record UpnpTimings(Duration pollInterval, Duration idlePollInterval, Duration commandTimeout,
                   Duration reconnectInitialDelay, Duration reconnectMaxDelay) {

    static UpnpTimings from(UpnpProperties properties) {
        return new UpnpTimings(Duration.ofSeconds(properties.pollIntervalSeconds()),
                Duration.ofSeconds(properties.idlePollIntervalSeconds()),
                Duration.ofSeconds(properties.commandTimeoutSeconds()),
                Duration.ofSeconds(properties.reconnectInitialDelaySeconds()),
                Duration.ofSeconds(properties.reconnectMaxDelaySeconds()));
    }
}
```

`src/main/java/dev/andre/homecontrol/adapters/sonos/SonosTimings.java`:

```java
package dev.andre.homecontrol.adapters.sonos;

import java.time.Duration;

/** The waits of a {@link SonosSession}. Production builds them from {@link SonosProperties}; tests pass milliseconds. */
record SonosTimings(Duration pollInterval, Duration idlePollInterval, Duration topologyInterval,
                    Duration commandTimeout, Duration reconnectInitialDelay, Duration reconnectMaxDelay) {

    static SonosTimings from(SonosProperties properties) {
        return new SonosTimings(Duration.ofSeconds(properties.pollIntervalSeconds()),
                Duration.ofSeconds(properties.idlePollIntervalSeconds()),
                Duration.ofSeconds(properties.topologyIntervalSeconds()),
                Duration.ofSeconds(properties.commandTimeoutSeconds()),
                Duration.ofSeconds(properties.reconnectInitialDelaySeconds()),
                Duration.ofSeconds(properties.reconnectMaxDelaySeconds()));
    }
}
```

- [ ] **Step 4: The sessions take the records**

In `UpnpSession.java`:
1. The field `properties` becomes `private final UpnpTimings timings;`.
2. Keep the public constructor's signature. Its body becomes a call to a new package-private constructor with identical parameters, except `UpnpTimings timings` in place of `UpnpProperties properties`, passing `UpnpTimings.from(properties)`. The old body moves into the new constructor.
3. `new SoapClient(http, Duration.ofSeconds(properties.commandTimeoutSeconds()))` becomes `new SoapClient(http, timings.commandTimeout())`.
4. The `ReconnectingPoller` construction's `Duration.ofSeconds(properties.reconnectInitialDelaySeconds())` and `Duration.ofSeconds(properties.reconnectMaxDelaySeconds())` become `timings.reconnectInitialDelay()` and `timings.reconnectMaxDelay()`.
5. `Duration timeout = Duration.ofSeconds(properties.commandTimeoutSeconds());` becomes `Duration timeout = timings.commandTimeout();`.
6. The next-poll delay, `return Duration.ofSeconds(transport.active() ? properties.pollIntervalSeconds() : properties.idlePollIntervalSeconds());`, becomes `return transport.active() ? timings.pollInterval() : timings.idlePollInterval();`.

In `SonosSession.java`, the same for both of its constructors:
- the public one delegates with `SonosTimings.from(properties)`;
- the package-private one, which already exists (it adds a clock), takes `SonosTimings timings` in place of `SonosProperties properties`.

Then:
- The `SoapClient`, `ReconnectingPoller` and next-poll-delay replacements are as for UPnP.
- The topology check `Duration.between(topologyReadAt, clock.instant()).toSeconds() >= properties.topologyIntervalSeconds()` becomes `Duration.between(topologyReadAt, clock.instant()).compareTo(timings.topologyInterval()) >= 0`.
- Check each file with `grep -n 'properties' <file>`: only the public constructor remains.

- [ ] **Step 5: The session tests run at milliseconds**

**`UpnpSessionTest`:**
- The field `properties` becomes:

  ```java
      /** Poll 100 ms idle and playing, command 1 s, reconnect 50–200 ms. */
      private final UpnpTimings timings = new UpnpTimings(Duration.ofMillis(100), Duration.ofMillis(100),
              Duration.ofSeconds(1), Duration.ofMillis(50), Duration.ofMillis(200));
  ```

  and the helper's `new UpnpSession(device, properties, ...)` passes `timings`.
- The `pollsFasterWhilePlaying` session, `new UpnpProperties(true, 1, 30, 1, 1, 1, 2)`, becomes `new UpnpTimings(Duration.ofMillis(100), Duration.ofSeconds(30), Duration.ofSeconds(1), Duration.ofMillis(50), Duration.ofMillis(200))`.
- The session with `new UpnpProperties(true, 1, 1, 1, 1, 30, 60)` becomes `new UpnpTimings(Duration.ofMillis(100), Duration.ofMillis(100), Duration.ofSeconds(1), Duration.ofSeconds(30), Duration.ofSeconds(60))`.

Change these "nothing happens" windows:

| Test | Old | New |
| --- | --- | --- |
| `neverFetchesAStoredLocationOffTheDevicesAddress` | `during(Duration.ofMillis(1500)).atMost(Duration.ofSeconds(3))` | `during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2))` |
| `refusesControlUrlsOnAnotherHost` | `during(Duration.ofMillis(1500)).atMost(Duration.ofSeconds(3))` | `during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2))` |
| `garbageAnswersAreIgnoredWhilePolling` | `during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(4))` | `during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(2))` |
| `controlUrlsOnAnotherHostAreRefused` | `during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(4))` | `during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(2))` |
| `aDescriptionThatIsNotXmlIsAConnectFailure` | `during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(3))` | `during(Duration.ofMillis(700)).atMost(Duration.ofSeconds(2))` |
| `anAnnouncedLocationOffTheDevicesHostIsRefused` | `during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(3))` | `during(Duration.ofMillis(700)).atMost(Duration.ofSeconds(2))` |
| `aDescriptionOfAnotherDeviceIsRefused` | `during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(3))` | `during(Duration.ofMillis(700)).atMost(Duration.ofSeconds(2))` |
| `closeStopsPolling` | `during(Duration.ofMillis(2800)).atMost(Duration.ofSeconds(4))` | `during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(2))` |

`closeStopsPolling` keeps its 300 ms tolerance after `close()`.

**`SonosSessionTest`:**
- The field `properties` becomes:

  ```java
      /** Poll 100 ms idle and playing, topology every 1 s, command 1 s, reconnect 50–200 ms. */
      private final SonosTimings timings = new SonosTimings(Duration.ofMillis(100), Duration.ofMillis(100),
              Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofMillis(50), Duration.ofMillis(200));
  ```

  and every `new SonosSession(..., properties, ...)` passes `timings`.
- The session built with `new SonosProperties(true, 1, 1, 3600, ...)` becomes `new SonosTimings(Duration.ofMillis(100), Duration.ofMillis(100), Duration.ofHours(1), Duration.ofSeconds(1), Duration.ofMillis(50), Duration.ofMillis(200))`.

`SonosAdapterTest` and `UpnpAdapterTest` keep their properties: they go through the adapters.

- [ ] **Step 6: Run the tests**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.adapters.upnp.*' --tests 'dev.andre.homecontrol.adapters.sonos.*' --tests 'dev.andre.homecontrol.web.SpeakerJellyfinEndToEndTest'`

Expected: PASS. `UpnpSessionTest`'s class time falls from about 31 s to under 12 s.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/adapters/upnp/UpnpTimings.java src/main/java/dev/andre/homecontrol/adapters/sonos/SonosTimings.java src/test/java/dev/andre/homecontrol/adapters/upnp/UpnpTimingsTest.java src/test/java/dev/andre/homecontrol/adapters/sonos/SonosTimingsTest.java src/main/java/dev/andre/homecontrol/adapters/upnp/UpnpSession.java src/main/java/dev/andre/homecontrol/adapters/sonos/SonosSession.java src/test/java/dev/andre/homecontrol/adapters/upnp/UpnpSessionTest.java src/test/java/dev/andre/homecontrol/adapters/sonos/SonosSessionTest.java
git commit -m "refactor: give the UPnP and Sonos sessions their waits as Durations

Their properties only allow whole seconds of at least 1, so their tests
could not go faster; UpnpTimings and SonosTimings let them poll every
100 ms, while production builds the same values from the properties."
```

End the message with your `Co-Authored-By:` trailer.

### Task 12: Measure, document, and run the whole build twice

**Files:**
- Modify: `docs/dev/architecture.md` (section "Progress measures")
- Modify: `docs/dev/testing.md` (sections "Running tests" and "Fakes and fixtures")

**Interfaces:**
- Consumes: everything above.
- Produces: nothing.

- [ ] **Step 1: Run the whole suite twice and measure**

```bash
scripts/gradle.sh test --rerun
cat build/test-results/test/*.xml | grep -c '<testcase '
grep -ho 'testsuite name="[^"]*" tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*" timestamp="[^"]*" hostname="[^"]*" time="[0-9.]*"' build/test-results/test/*.xml | sed -E 's/.*time="([0-9.]*)"/\1/' | awk '{s+=$1} END {printf "%.0f s\n", s}'
scripts/gradle.sh test --rerun
```

Expected:
- Both runs are green.
- The test count is Phase 1.3a's 2,736, plus the tests this plan adds:
  - 1 `DefaultTimeoutTest`;
  - 2 new tests in Task 3;
  - 2 `AndroidTvTimingsTest`;
  - 1 each in `CastTimingsTest`, `TizenTimingsTest`, `WebOsTimingsTest`, `BluetoothTimingsTest`, `SsdpTimingsTest`, `UpnpTimingsTest` and `SonosTimingsTest`.

  That is 2,748 when this plan was written. Adjust for any test `main` gained since.
- The summed class time is well below the 695 s measured with four JVMs before this phase. The spec's target, measured with one JVM, is about 300 s.

The build runs the unit tests in up to four JVMs, and a class takes longer while it shares the CPUs. So record the sum together with the number of JVMs, as the progress table already does for its baseline.

Measure the `test` task's wall time with `time scripts/gradle.sh test --rerun`.

- [ ] **Step 2: Update the progress measures**

In `docs/dev/architecture.md`'s "Progress measures" table, fill the "Now" column:
- **Summed test-class time:** the number from Step 1, with the number of JVMs.
- **`test` task wall time:** the number from Step 1.
- **Wall-clock upper-bound assertions:** `0` (the nine replaced). Add one sentence below the table: `EventStreamShutdownEndToEndTest` keeps its bound, because the elapsed time of closing the application is the behaviour it tests.

Then add this paragraph under the table:

```markdown
`YouTubeEndToEndTest` takes about 8 s: 5 s of Spring context start, 3.4 s of certificate generation and a real Cast
connect before its first request, and 2 s of OAuth polling at the production floor of one poll a second. Phase 1.3d's
shared context removes the first.
```

- [ ] **Step 3: Describe the rules in the testing guide**

In `docs/dev/testing.md`, under `## Running tests`, add:

```markdown
- Every test method has a 60 s timeout (`src/test/resources/junit-platform.properties`), off while a debugger is
  attached. A test that needs longer declares `@Timeout`.
```

Under `## Fakes and fixtures`, add:

```markdown
- Device sessions take their waits as a `*Timings` record of `Duration`s (`CastTimings`, `TizenTimings`, …), built
  from the `*Properties` in production. Tests pass 50–500 ms; a "nothing happens" check (`await().during(...)`)
  covers at least five of the intervals it watches. Tests assert outcomes (the exception, its message, the order of
  events), never an upper bound on elapsed time.
```

- [ ] **Step 4: Build and check the guarantees**

```bash
scripts/gradle.sh build
scripts/gradle.sh compileE2eJava
git diff --stat origin/main -- src/main/resources
```

Expected:
- The build is green.
- The browser tests compile.
- The last command prints nothing: no configuration default changed.

- [ ] **Step 5: Commit**

```bash
git add docs/dev/architecture.md docs/dev/testing.md
git commit -m "docs: record the test timings after Phase 1.3b and the rules behind them"
```

End the message with your `Co-Authored-By:` trailer.
