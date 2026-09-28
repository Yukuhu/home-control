# Phase 1.3d-3 Shared Full-Application Context Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fifteen full-application test classes share one Spring context per test JVM. Today they start about 30, 14 of them `LoginGatingTest`'s. After each class, a reset brings the application back to a fresh install.

**Architecture:**
- **One base class.** `testsupport.FullAppTest` is a `@SpringBootTest(webEnvironment = RANDOM_PORT)` with `@AutoConfigureMockMvc`.
  - It has one inherited `@DynamicPropertySource`. That registers a data directory per context, and the union of the properties the classes need, including the URLs of three HTTP fakes.
  - The three fakes (TMDB, Google, TheSportsDB) start once per JVM in `testsupport.SharedFakes`.
- **A reset after every class.** `testsupport.FullAppReset` is a JUnit `AfterAllCallback` on the base, with a static `reset(ApplicationContext)` that `LoginGatingTest` also calls after each test. It uses the application's own operations:
  - forget every device;
  - remove every secret, and so the login;
  - disconnect YouTube;
  - reload the workflows;
  - empty the sports settings and the pins;
  - reset the YouTube quota and the login rate limiter;
  - invalidate every rail;
  - delete the settings files;
  - reset the shared fakes.
- **Three small production reset methods,** the only production change (the spec allows reset methods in 1.3d): `LoginRateLimiter.reset()`, `QuotaLedger.reset()` and `WorkflowStore.reload()`.
- **The rules extend to the new base.** `SharedContextRulesTest` covers `FullAppTest`'s subclasses too. A new rule allows a `@SpringBootTest` only on a subclass of a shared base, or on one of the nine named classes that keep a context of their own.

**Tech Stack:** Java 25, Spring Boot 4.1.1 (`org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`, `org.springframework.test.context.bean.override.mockito.MockitoSpyBean`), JUnit 6 (`AfterAllCallback`), ArchUnit 1.5.1, Gradle through `scripts/gradle.sh`.

**Spec:** `docs/superpowers/specs/2026-09-27-phase-1-guardrails-design.md`, section "1.3d Shared Spring contexts", parts "Full application, 1 context", "Own contexts, because their beans differ" and "Reset between test classes", and "Progress measures".

## Global Constraints

- **Start condition:** #135 (1.3d-2) is merged. Branch `test/shared-full-app-context` from `origin/main` (`286f323`).
- **Delivery (the user's decision, 2026-09-28):** 1.3d ships as three pull requests. This is the last of them.
- **Production changes are only the three reset methods** (spec, "Assumptions": "the reset methods (1.3d)"). Each is "a small public reset method, documented as existing for the shared test context". `git diff origin/main -- src/main` shows only `LoginRateLimiter`, `QuotaLedger` and `WorkflowStore`.
- **Test count:** equal or higher. A moved test names its replacement in the commit message. This plan adds 8 tests (3 reset-method tests, 3 fake-reset tests, the reset self-test and 1 rule), so 2,757 becomes 2,765.
- **Build and test only through `scripts/gradle.sh`.** There is no local JDK, and `scripts/gradle.sh build` is green at the end.
- **Commits:**
  - Conventional Commits, ending with a `Co-Authored-By:` trailer naming the model that wrote them.
  - Stage only the files you changed: `git add <paths>`, never `git add -A`.

## What the inventory found

Most of it comes from the full-application inventory made before 1.3d-1 (`scratchpad/p13d-inv-fullapp.md`), checked again on `286f323`.

- **Context starts:** 43 after 1.3d-2. Today every full-application class starts its own context, because each has its own `@DynamicPropertySource` method. `LoginGatingTest` evicts its context after each of its 14 tests.
- **Fifteen classes can share one context with the union of their properties:**
  - `HomeControlApplicationTest` and `TmdbModuleEnabledTest`;
  - `StaticAssetsTest`, `LoginGatingTest`, `LoginGateEndToEndTest` and `CrossOriginEndToEndTest`;
  - `DeviceStateStreamEndToEndTest`;
  - `JellyfinEndToEndTest` and `SpeakerJellyfinEndToEndTest`;
  - `SportsEndToEndTest` and `StreamingLaunchersEndToEndTest`;
  - `YouTubeEndToEndTest` and `YouTubeBrowserOAuthTest`;
  - `WorkflowSetupAuthenticationTest`, in `WorkflowSetupControllerTest.java`;
  - `SportsModuleSwitchTest.OnButUnconfigured`.

  The union is:
  - `home-control.security.allowed-hosts`;
  - Cast's command and load timeouts (3 s and 5 s, the same in Jellyfin and YouTube);
  - UPnP's two poll intervals (1 s);
  - sports' calendar `allow-loopback`;
  - the TheSportsDB, TMDB and four YouTube base URLs.

  No two classes want different values.
- **Nine classes keep a context of their own:**
  - `CastEndToEndTest`: its Cast timings (1 s heartbeat, 3 s stale timeout) would apply to every Cast device in the shared context.
  - `WebOsEndToEndTest`, `TizenEndToEndTest` and `SpeakersEndToEndTest`: SSDP on, TV modules off, and `SpeakersEndToEndTest`'s 30 s UPnP reconnect delay.
  - `BluetoothSpeakerEndToEndTest` and `BluetoothJellyfinEndToEndTest`: a `@Primary` fake BlueZ bean and a fake mpv.
  - `WorkflowEndToEndTest`: a recording device adapter.
  - `SportsModuleSwitchTest.TheSportsDbOff` and `WorkflowOnlySetupTest`: mixed module combinations (1.3d-2).
- **State a class leaves behind,** and the operation that clears it:
  - **Devices:** `DeviceManager.forget` for each of `devices()`. It also removes Android TV certificate aliases and learned settings.
  - **Login and secrets:** `LoginService.removeSecrets(SecretStore.names())`, which runs the change listeners; then delete `secrets.json` and `secret.key`, which `LoginGatingTest` asserts are absent.
  - **Rate limiter:** `LoginRateLimiter` has no clearing operation, so it gets **`reset()`**. `LoginGatingTest` blocks 127.0.0.1 for 15 minutes, which would break `LoginGateEndToEndTest`'s login.
  - **Workflows:** `WorkflowStore` reads its definitions from the secrets once, in its constructor, so removing the secrets does not drop them. It gets **`reload()`**; the spec anticipated this.
  - **YouTube:** `YouTubeSetupService.disconnect()`, called after the secrets are removed, so that no revoke request reaches the shared Google fake. It writes `sources.json`, which is deleted afterwards.
  - **YouTube quota:** `QuotaLedger` loads `youtube-quota.json` only in its constructor, so it gets **`reset()`**. `YouTubeEndToEndTest` asserts "107 of 10000 units".
  - **Sports:** `SportsSettingsService.update(s -> SportsSettings.empty())`, then delete `sports.json`. `JsonFileSportsStore` holds no state, although the spec names it.
  - **Pins:** `PinnedShortcuts.remove` for each of `all()`, then delete `pinned.json`. `JsonFilePinStore` holds no state, although the spec names it. `StreamingLaunchersEndToEndTest` asserts exactly 2 pins, and `SportsEndToEndTest` adds one.
  - **Rails:** `RailCache.invalidateSource` for every source.
  - **Shared fakes:** `FakeHttpServer.reset()` exists, but it drops each wrapper's fallback and default routes, so each wrapper gets a `reset()` that reapplies them. `YouTubeBrowserOAuthTest` asserts that the Google fake received no device-code request.
- **The data directory is created once per context,** never `build/test-data/<worker>`, which old runs leave filled.

## Plan decisions

1. **The nine own-context classes stay as they are.** The spec names the Bluetooth pair and `WorkflowEndToEndTest`. Cast and the SSDP trio join them because sharing them would change their timings or need SSDP and discovery resets (`SsdpDiscovery` and `SonosDiscovery` have no clearing method). Even with them, the count per JVM stays well under the spec's 10: see Task 10.
2. **Sports and pins are reset through their services,** because the stores the spec names hold no state.
3. **`WorkflowSetupAuthenticationTest`'s `@MockitoBean WorkflowHttpClient` becomes one `@MockitoSpyBean WorkflowHttpClient workflowHttp` in the base.** A spy calls the real client, so it changes nothing for the other classes, and its interactions reset after each test. The test keeps its `verifyNoInteractions` check.
4. **The shared fakes are never closed.** Each test JVM already keeps its cached Spring contexts, with Tomcat's threads, until it exits, and the fakes' handlers run on virtual threads.
5. **The reset runs after each class, and `LoginGatingTest` also calls it after each test,** as the spec says. It also runs after a class whose test failed: `AfterAllCallback` always runs.
6. **`SportsModuleSwitchTest.OnButUnconfigured` becomes `sources.sports.SportsUnconfiguredTest`,** a top-level `FullAppTest`. A `@Nested` class takes its enclosing class's configuration, and `SportsModuleSwitchTest` still holds `TheSportsDbOff`.

## Review Focus

- **A class that leaves state the reset misses breaks a later class, but only in some orders.** Classes are spread across four JVMs differently from run to run. Test: `FullAppResetTest` sets up the kinds of state that tests leave and checks one reset clears them (Task 3). Task 10 runs the suite three times with four JVMs and once with a single JVM, so every full-application class shares one context in one order.
- **A shared fake must answer each class as a fresh fake would.** A route or recorded request from an earlier class would satisfy or break a later assertion. Test: each wrapper's `resetRestoresTheFreshFake` test (Task 2), and `YouTubeBrowserOAuthTest`'s zero-request assertions after `YouTubeEndToEndTest` in the single-JVM run.
- **The reset must not leave the application unable to log in.** Test: `FullAppResetTest` checks that the rate limiter no longer blocks 127.0.0.1 and that no login is required. `LoginGateEndToEndTest` logs in after `LoginGatingTest` in the single-JVM run.
- **The reset methods must not change production behaviour.** Nothing in production calls them, and each is tested on its own (Task 1). Test: `src/main` has as many calls of `.reset()` or `.reload()` as on `origin/main` (Task 10, Step 4).
- **A new `@SpringBootTest` must not quietly get a context of its own.** Test: `SharedContextRulesTest.everySpringBootTestSharesAContextOrIsListed` (Task 9), shown failing on a temporary unlisted class.

---

### Task 1: The three production reset methods

**Files:**
- Modify: `src/main/java/dev/andre/homecontrol/security/LoginRateLimiter.java`, `src/main/java/dev/andre/homecontrol/sources/youtube/QuotaLedger.java`, `src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowStore.java`
- Test: `src/test/java/dev/andre/homecontrol/security/LoginRateLimiterTest.java`, `src/test/java/dev/andre/homecontrol/sources/youtube/QuotaLedgerTest.java`, `src/test/java/dev/andre/homecontrol/sources/workflows/WorkflowStoreTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `public synchronized void LoginRateLimiter.reset()`, `public synchronized void QuotaLedger.reset()`, `public void WorkflowStore.reload()`.

- [ ] **Step 1: Write the three failing tests**

In `LoginRateLimiterTest`, which builds `limiter` with 5 failures per address and 50 in total:

```java
    @Test
    void resetForgetsEveryFailure() {
        for (int i = 0; i < 5; i++) {
            limiter.failed("10.0.0.2");
        }
        for (int i = 0; i < 50; i++) {
            limiter.failed("10.0.1." + i);
        }
        assertThat(limiter.blockedFor("10.0.0.2")).isPresent();
        assertThat(limiter.blockedFor("10.0.2.1")).isPresent();

        limiter.reset();

        assertThat(limiter.blockedFor("10.0.0.2")).isEmpty();
        assertThat(limiter.blockedFor("10.0.2.1")).isEmpty();
    }
```

In `QuotaLedgerTest`, which has `file` and `clock` fields:

```java
    @Test
    void resetForgetsTheDaysUsageAndItsFile() {
        QuotaLedger ledger = new QuotaLedger(file, clock, 10000, 20);
        ledger.charge(QuotaLedger.Call.SEARCH_LIST);
        assertThat(file).exists();

        ledger.reset();

        QuotaLedger.Usage usage = ledger.usage();
        assertThat(usage.units()).isZero();
        assertThat(usage.searches()).isZero();
        assertThat(usage.calls()).isEmpty();
        assertThat(file).doesNotExist();
        assertThat(new QuotaLedger(file, clock, 10000, 20).usage().units()).isZero();
    }
```

In `WorkflowStoreTest`, which has `secrets`, `login`, `workflows`, `events` and the helper `first()`:

```java
    @Test void reloadReadsTheDefinitionsAgainAfterTheSecretsWereRemoved() {
        WorkflowDefinition saved = first();
        login.removeSecrets(secrets.names());
        assertThat(workflows.all()).containsExactly(saved);
        int eventCount = events.size();

        workflows.reload();

        assertThat(workflows.all()).isEmpty();
        assertThat(workflows.problems()).isEmpty();
        assertThat(events).hasSize(eventCount + 1).last().isEqualTo(new ContentChangedEvent("workflows"));
    }
```

(Match each file's existing style and imports. The second `assertThat` in the workflow test shows the gap: the store still holds the definition after its secret is gone.)

- [ ] **Step 2: Run them to verify they fail**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.security.LoginRateLimiterTest' --tests 'dev.andre.homecontrol.sources.youtube.QuotaLedgerTest' --tests 'dev.andre.homecontrol.sources.workflows.WorkflowStoreTest'`

Expected: FAIL at `compileTestJava`: `cannot find symbol` for `reset()`, `reset()` and `reload()`.

- [ ] **Step 3: The methods**

In `LoginRateLimiter`, after `failed(...)`:

```java
    /** Forgets every failure. Exists for the shared test context, which reuses one application for many test classes. */
    public synchronized void reset() {
        failuresByAddress.clear();
        allFailures.clear();
        lastSweep = Instant.MIN;
    }
```

In `QuotaLedger`, after `usage()` (its imports already cover `Files`, `IOException` and `UncheckedIOException`):

```java
    /**
     * Forgets today's usage and deletes its file. Exists for the shared test context, which reuses one application
     * for many test classes.
     */
    public synchronized void reset() {
        day = today();
        units = 0;
        searches = 0;
        calls.clear();
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete " + file, e);
        }
    }
```

In `WorkflowStore`, after `find(...)`:

```java
    /**
     * Reads the definitions from the secrets again; this store otherwise reads them only when it is created. Exists
     * for the shared test context, which removes every secret between test classes.
     */
    public void reload() {
        writes.lock();
        try {
            snapshot = load();
        } finally {
            writes.unlock();
        }
        changed();
    }
```

- [ ] **Step 4: Run them**

Run the Step 2 command with `--rerun`. Expected: PASS, and each class has one test more than before.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/security/LoginRateLimiter.java src/main/java/dev/andre/homecontrol/sources/youtube/QuotaLedger.java src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowStore.java src/test/java/dev/andre/homecontrol/security/LoginRateLimiterTest.java src/test/java/dev/andre/homecontrol/sources/youtube/QuotaLedgerTest.java src/test/java/dev/andre/homecontrol/sources/workflows/WorkflowStoreTest.java
git commit -m "feat: let the shared test context reset the rate limiter, the YouTube quota and the workflows

Small reset methods for beans that keep state no public operation
clears, as the Phase 1.3d spec provides; nothing in production calls them."
```

End the message with your `Co-Authored-By:` trailer.

### Task 2: Shared HTTP fakes that reset to fresh

**Files:**
- Modify: `src/test/java/dev/andre/homecontrol/sources/tmdb/FakeTmdbServer.java`, `src/test/java/dev/andre/homecontrol/sources/youtube/FakeGoogleServer.java`, `src/test/java/dev/andre/homecontrol/sources/sports/thesportsdb/FakeTheSportsDbServer.java`
- Create: `src/test/java/dev/andre/homecontrol/testsupport/SharedFakes.java`
- Create (tests): `src/test/java/dev/andre/homecontrol/sources/tmdb/FakeTmdbServerTest.java`, `src/test/java/dev/andre/homecontrol/sources/youtube/FakeGoogleServerTest.java`, `src/test/java/dev/andre/homecontrol/sources/sports/thesportsdb/FakeTheSportsDbServerTest.java` (none exists on `286f323`)

**Interfaces:**
- Consumes: `FakeHttpServer.reset()` and `fallback(...)` (1.3a).
- Produces:
  - `public void reset()` on each of the three wrappers;
  - `public final class SharedFakes`, with `public static synchronized FakeTmdbServer tmdb()`, `FakeGoogleServer google()`, `FakeTheSportsDbServer theSportsDb()`, and a package-private `static synchronized void resetAll()`.

- [ ] **Step 1: Write the failing tests**

Each wrapper gets one test, `resetRestoresTheFreshFake`, in a new test class next to it. The test:
1. sets a route that overrides the fresh behaviour;
2. makes a request through `java.net.http.HttpClient` (in try-with-resources) and checks that the route answered;
3. calls `reset()`;
4. checks that the recorded requests are gone;
5. makes the same request again and checks that the fresh answer is back.

Per wrapper, with its methods on `286f323`:
- **`FakeTmdbServer`:**
  - route `respondJson("GET", "/3/configuration", 200, "{}")`;
  - request `url().resolve("/3/configuration")`;
  - after `reset()`, `requests()` is empty, and the request answers 404 with `FakeTmdbServer.fixture("not-found.json")` as its body.
- **`FakeGoogleServer`:**
  - route `respond("GET", "/probe", Canned.json(200, "{}"))`;
  - request `base()` + `"/probe"`;
  - after `reset()`, `requests()` is empty, and the request answers 404 with `"no fake route"` in its body.
- **`FakeTheSportsDbServer`:**
  - route `respondJson("lookupleague.php", Map.of("id", "4328"), 200, "{\"leagues\":[]}")`;
  - request `apiBase()` + `"/" + FREE_KEY + "/lookupleague.php?id=4328"`;
  - after `reset()`, `count("lookupleague.php")` is 0, and the request answers with the `lookupleague-unknown.json` fixture again (the constructor's default route).
  - A request with the key `"999"` answers 400 (the constructor's fallback).

Read each wrapper before you write its test. If a route method's matching differs from what is written here (for example, how TheSportsDB matches a query), change the route, not the checks after the reset.

- [ ] **Step 2: Run them to verify they fail**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.tmdb.FakeTmdbServerTest' --tests 'dev.andre.homecontrol.sources.youtube.FakeGoogleServerTest' --tests 'dev.andre.homecontrol.sources.sports.thesportsdb.FakeTheSportsDbServerTest'`

Expected: FAIL at `compileTestJava`: `cannot find symbol: method reset()`.

- [ ] **Step 3: The wrappers' `reset()`**

In each wrapper, move the constructor's setup into a private `defaults()`. The constructor calls it, and `reset()` calls it after `server.reset()`. For `FakeTmdbServer`:

```java
    public FakeTmdbServer() throws IOException {
        server = FakeHttpServer.start();
        defaults();
    }

    /** Forgets every route, request and delay, and restores the fresh fake, for a fake shared across test classes. */
    public void reset() {
        server.reset();
        defaults();
    }

    private void defaults() {
        server.fallback(Response.of(404, Response.JSON, fixture("not-found.json")));
    }
```

`FakeGoogleServer.defaults()` sets its JSON 404 fallback. `FakeTheSportsDbServer.defaults()` sets its key-checking fallback and both `byDefault(...)` routes. Both are moved unchanged from the constructors.

- [ ] **Step 4: `SharedFakes`**

`src/test/java/dev/andre/homecontrol/testsupport/SharedFakes.java`:

```java
package dev.andre.homecontrol.testsupport;

import dev.andre.homecontrol.sources.sports.thesportsdb.FakeTheSportsDbServer;
import dev.andre.homecontrol.sources.tmdb.FakeTmdbServer;
import dev.andre.homecontrol.sources.youtube.FakeGoogleServer;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * The web-API fakes that {@link FullAppTest} points the shared application at: started once per test JVM, never
 * closed (the JVM's cached application contexts outlive them anyway), and reset after every test class by
 * {@link FullAppReset}. A test sets the routes it needs in {@code @BeforeAll} or in the test, never in a static
 * initializer, and never closes a shared fake.
 */
public final class SharedFakes {

    private static FakeTmdbServer tmdb;
    private static FakeGoogleServer google;
    private static FakeTheSportsDbServer theSportsDb;

    private SharedFakes() {
    }

    public static synchronized FakeTmdbServer tmdb() {
        if (tmdb == null) {
            tmdb = start(FakeTmdbServer::new);
        }
        return tmdb;
    }

    public static synchronized FakeGoogleServer google() {
        if (google == null) {
            google = start(FakeGoogleServer::new);
        }
        return google;
    }

    public static synchronized FakeTheSportsDbServer theSportsDb() {
        if (theSportsDb == null) {
            theSportsDb = start(FakeTheSportsDbServer::new);
        }
        return theSportsDb;
    }

    /** Resets every shared fake this JVM started. */
    static synchronized void resetAll() {
        if (tmdb != null) {
            tmdb.reset();
        }
        if (google != null) {
            google.reset();
        }
        if (theSportsDb != null) {
            theSportsDb.reset();
        }
    }

    private interface Starter<T> {
        T start() throws IOException;
    }

    private static <T> T start(Starter<T> starter) {
        try {
            return starter.start();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start a shared fake", e);
        }
    }
}
```

- [ ] **Step 5: Run them**

Run the Step 2 command with `--rerun`, plus every test class that uses the three fakes: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.tmdb.*' --tests 'dev.andre.homecontrol.sources.youtube.*' --tests 'dev.andre.homecontrol.sources.sports.*' --rerun`.

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/test/java/dev/andre/homecontrol/sources/tmdb/FakeTmdbServer.java src/test/java/dev/andre/homecontrol/sources/youtube/FakeGoogleServer.java src/test/java/dev/andre/homecontrol/sources/sports/thesportsdb/FakeTheSportsDbServer.java src/test/java/dev/andre/homecontrol/sources/tmdb/FakeTmdbServerTest.java src/test/java/dev/andre/homecontrol/sources/youtube/FakeGoogleServerTest.java src/test/java/dev/andre/homecontrol/sources/sports/thesportsdb/FakeTheSportsDbServerTest.java src/test/java/dev/andre/homecontrol/testsupport/SharedFakes.java
git commit -m "test: let the TMDB, Google and TheSportsDB fakes reset to fresh, and share them per JVM"
```

End the message with your `Co-Authored-By:` trailer.

### Task 3: `FullAppTest`, `FullAppReset` and the reset's own test

**Files:**
- Create: `src/test/java/dev/andre/homecontrol/testsupport/FullAppTest.java`, `src/test/java/dev/andre/homecontrol/testsupport/FullAppReset.java`
- Test: `src/test/java/dev/andre/homecontrol/testsupport/FullAppResetTest.java`

**Interfaces:**
- Consumes: Task 1's methods; `SharedFakes` (Task 2).
- Produces:
  - `public abstract class FullAppTest`, with `protected static Path dataDir()` and `@MockitoSpyBean protected WorkflowHttpClient workflowHttp`;
  - `public final class FullAppReset implements AfterAllCallback`, with `public static void reset(ApplicationContext app)`.

- [ ] **Step 1: Write the reset's test**

`src/test/java/dev/andre/homecontrol/testsupport/FullAppResetTest.java`:

```java
package dev.andre.homecontrol.testsupport;

import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.security.LoginRateLimiter;
import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.sources.pinned.PinnedShortcuts;
import dev.andre.homecontrol.sources.sports.SportsSettings;
import dev.andre.homecontrol.sources.sports.SportsSettingsService;
import dev.andre.homecontrol.sources.youtube.QuotaLedger;
import dev.andre.homecontrol.storage.SecretStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** One reset brings the shared application back to a fresh install, whatever a test class left behind. */
class FullAppResetTest extends FullAppTest {

    private static final String PASSWORD = "reset-test-password";

    @Autowired
    ApplicationContext context;

    @Autowired
    DeviceManager devices;

    @Autowired
    LoginService login;

    @Autowired
    SecretStore secrets;

    @Autowired
    LoginRateLimiter limiter;

    @Autowired
    PinnedShortcuts pins;

    @Autowired
    SportsSettingsService sports;

    @Autowired
    QuotaLedger quota;

    @Test
    void resetBringsTheApplicationBackToAFreshInstall() throws Exception {
        devices.adopt(new Device("reset-probe", "Probe", DeviceKind.ANDROID_TV, "127.0.0.1",
                Map.of("androidtv", Map.of()), Instant.now()));
        login.storeSecrets(Map.of("jellyfin.token", "0123456789abcdef"), PASSWORD, PASSWORD, new MockHttpServletRequest());
        for (int i = 0; i < 5; i++) {
            limiter.failed("127.0.0.1");
        }
        pins.add("https://www.netflix.com/title/1", "Stranger Things");
        sports.update(current -> current.withTimeZone("Europe/Berlin"));
        quota.charge(QuotaLedger.Call.VIDEOS_LIST);
        try (HttpClient http = HttpClient.newHttpClient()) {
            http.send(HttpRequest.newBuilder(SharedFakes.tmdb().url().resolve("/3/probe")).build(),
                    HttpResponse.BodyHandlers.discarding());
        }
        assertThat(login.loginRequired()).isTrue();
        assertThat(limiter.blockedFor("127.0.0.1")).isPresent();
        assertThat(dataDir().resolve("pinned.json")).exists();
        assertThat(SharedFakes.tmdb().requests()).isNotEmpty();

        FullAppReset.reset(context);

        assertThat(devices.devices()).isEmpty();
        assertThat(login.loginRequired()).isFalse();
        assertThat(secrets.names()).isEmpty();
        assertThat(limiter.blockedFor("127.0.0.1")).isEmpty();
        assertThat(pins.all()).isEmpty();
        assertThat(sports.current()).isEqualTo(SportsSettings.empty());
        assertThat(quota.usage().units()).isZero();
        for (String file : new String[]{"secrets.json", "secret.key", "sources.json", "sports.json", "pinned.json",
                "youtube-quota.json"}) {
            assertThat(dataDir().resolve(file)).doesNotExist();
        }
        assertThat(SharedFakes.tmdb().requests()).isEmpty();
    }
}
```

The calls match the code on `286f323`:
- `storeSecrets` is how `LoginGatingTest.storeAFirstSecret` stores a first secret;
- the pin URL is `PinnedContentSourceTest`'s;
- `url()` and `requests()` are `FakeTmdbServer`'s.

If one fails to compile, use the real API and record it. The assertions stay as written.

- [ ] **Step 2: Run it to verify it fails**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.testsupport.FullAppResetTest'`

Expected: FAIL at `compileTestJava`: `cannot find symbol: class FullAppTest`.

- [ ] **Step 3: The base**

`src/test/java/dev/andre/homecontrol/testsupport/FullAppTest.java`:

```java
package dev.andre.homecontrol.testsupport;

import dev.andre.homecontrol.sources.workflows.WorkflowHttpClient;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The one full-application context the end-to-end tests share: every module on, a real port, MockMvc, a data
 * directory made for this context, and the web APIs pointed at {@link SharedFakes}. {@link FullAppReset} brings it
 * back to a fresh install after every test class. A test class extends it and adds nothing to the context
 * ({@code SharedContextRulesTest}); a test that needs different beans or properties keeps a context of its own and is
 * listed there.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ExtendWith(FullAppReset.class)
public abstract class FullAppTest {

    private static Path dataDir;

    /** A spy calls the real client; {@code WorkflowSetupAuthenticationTest} checks it was never used. */
    @MockitoSpyBean
    protected WorkflowHttpClient workflowHttp;

    /** Inherited by every subclass, so they share one cache key: the union of what the shared tests need. */
    @DynamicPropertySource
    static void sharedApplication(DynamicPropertyRegistry registry) throws IOException {
        dataDir = Files.createTempDirectory("full-app");
        registry.add("shield.data-dir", dataDir::toString);
        registry.add("home-control.security.allowed-hosts", () -> "tv.example.org, *.home.example.net");
        registry.add("home-control.cast.command-timeout-seconds", () -> "3");
        registry.add("home-control.cast.load-timeout-seconds", () -> "5");
        registry.add("home-control.upnp.poll-interval-seconds", () -> "1");
        registry.add("home-control.upnp.idle-poll-interval-seconds", () -> "1");
        registry.add("home-control.sports.calendar.allow-loopback", () -> "true");
        registry.add("home-control.sports.thesportsdb.api-base-url", () -> SharedFakes.theSportsDb().apiBase().toString());
        registry.add("home-control.tmdb.api-base-url", () -> SharedFakes.tmdb().apiBase().toString());
        registry.add("home-control.youtube.oauth-base-url", () -> SharedFakes.google().base() + "/oauth");
        registry.add("home-control.youtube.api-base-url", () -> SharedFakes.google().base() + "/youtube/v3");
        registry.add("home-control.youtube.lounge-base-url", () -> SharedFakes.google().base() + "/lounge");
        registry.add("home-control.youtube.thumbnail-base-url", () -> SharedFakes.google().base() + "/thumbs");
    }

    /** This context's data directory. */
    protected static Path dataDir() {
        return dataDir;
    }
}
```

- [ ] **Step 4: The reset**

`src/test/java/dev/andre/homecontrol/testsupport/FullAppReset.java`:

```java
package dev.andre.homecontrol.testsupport;

import dev.andre.homecontrol.adapters.androidtv.AndroidTvProperties;
import dev.andre.homecontrol.content.RailCache;
import dev.andre.homecontrol.core.content.ContentSources;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.security.LoginRateLimiter;
import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.sources.pinned.PinnedShortcuts;
import dev.andre.homecontrol.sources.sports.SportsSettings;
import dev.andre.homecontrol.sources.sports.SportsSettingsService;
import dev.andre.homecontrol.sources.workflows.WorkflowStore;
import dev.andre.homecontrol.sources.youtube.QuotaLedger;
import dev.andre.homecontrol.sources.youtube.YouTubeSetupService;
import dev.andre.homecontrol.storage.SecretStore;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Brings the shared full application back to a fresh install after each test class, with the application's own
 * operations and the three reset methods that exist for this. Every module is on in {@link FullAppTest}, so a missing
 * bean fails the reset instead of being skipped. The order matters: the secrets go before YouTube
 * disconnects (so no revoke request reaches the shared Google fake) and before the workflows reload, and the fakes
 * are reset last (so a fetch the reset itself triggers cannot leave a recorded request behind).
 */
public final class FullAppReset implements AfterAllCallback {

    @Override
    public void afterAll(ExtensionContext context) {
        reset(SpringExtension.getApplicationContext(context));
    }

    public static void reset(ApplicationContext app) {
        DeviceManager devices = app.getBean(DeviceManager.class);
        devices.devices().forEach(device -> devices.forget(device.id()));

        app.getBean(LoginService.class).removeSecrets(app.getBean(SecretStore.class).names());
        app.getBean(YouTubeSetupService.class).disconnect();
        app.getBean(WorkflowStore.class).reload();
        app.getBean(SportsSettingsService.class).update(current -> SportsSettings.empty());
        PinnedShortcuts pins = app.getBean(PinnedShortcuts.class);
        pins.all().forEach(pin -> pins.remove(pin.id()));
        app.getBean(QuotaLedger.class).reset();
        app.getBean(LoginRateLimiter.class).reset();

        Path dataDir = app.getBean(AndroidTvProperties.class).dataDir();
        for (String file : new String[]{"secrets.json", "secret.key", "sources.json", "sports.json", "pinned.json"}) {
            delete(dataDir.resolve(file));
        }
        RailCache rails = app.getBean(RailCache.class);
        app.getBean(ContentSources.class).all().forEach(source -> rails.invalidateSource(source.id()));

        SharedFakes.resetAll();
    }

    private static void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete " + file, e);
        }
    }
}
```

- [ ] **Step 5: Run it**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.testsupport.FullAppResetTest' --rerun`

Expected: PASS. If an operation's name or signature differs from the code, use the real one and record it in your report.

- [ ] **Step 6: Commit**

```bash
git add src/test/java/dev/andre/homecontrol/testsupport/FullAppTest.java src/test/java/dev/andre/homecontrol/testsupport/FullAppReset.java src/test/java/dev/andre/homecontrol/testsupport/FullAppResetTest.java
git commit -m "test: add one shared full-application context that resets to a fresh install after each class"
```

End the message with your `Co-Authored-By:` trailer.

### Task 4: The classes that only need the base

**Files:**
- Modify:
  - `src/test/java/dev/andre/homecontrol/HomeControlApplicationTest.java`;
  - `src/test/java/dev/andre/homecontrol/sources/tmdb/TmdbModuleEnabledTest.java`;
  - `src/test/java/dev/andre/homecontrol/web/StaticAssetsTest.java`, `DeviceStateStreamEndToEndTest.java`, `CrossOriginEndToEndTest.java` and `LoginGateEndToEndTest.java`;
  - `src/test/java/dev/andre/homecontrol/sources/workflows/WorkflowSetupControllerTest.java` (the class `WorkflowSetupAuthenticationTest`);
  - `src/test/java/dev/andre/homecontrol/sources/sports/SportsModuleSwitchTest.java`.
- Create: `src/test/java/dev/andre/homecontrol/sources/sports/SportsUnconfiguredTest.java`

**Interfaces:**
- Consumes: `FullAppTest` (Task 3), `dataDir()` and `workflowHttp`.
- Produces: nothing.

**The recipe for each class** (the same in Tasks 5–8):
1. Before the change, note the class's test count from a `--rerun` of it.
2. Remove `@SpringBootTest(...)`, `@AutoConfigureMockMvc` and `@DirtiesContext`, and make the class `extends FullAppTest`.
3. Remove its `@DynamicPropertySource` method and the data-directory field it set: file checks use `dataDir()`. Every property it registered is already in the base. If one is not, stop and report: the base must hold the union.
4. Its own `@Autowired` fields stay (`MockMvc`, `ApplicationContext`, `DeviceManager`, …), as does `@LocalServerPort`. The base declares no field under those names.
5. Remove every `@MockitoBean` field. The one used here, `WorkflowHttpClient`, is the base's `workflowHttp` spy.
6. Remove the imports that are no longer used, and add `import dev.andre.homecontrol.testsupport.FullAppTest;`.
7. Run the class with `--rerun`: its count equals step 1's.

- [ ] **Step 1: Move the six plain classes**

| Class | Also |
| --- | --- |
| `HomeControlApplicationTest` | Had no `@DynamicPropertySource`. It keeps its three tests, including `noSwitchableModuleNeedsAnotherModulesBean`. |
| `TmdbModuleEnabledTest` | – |
| `StaticAssetsTest` | – |
| `DeviceStateStreamEndToEndTest` | Keep its per-test `FakeRemoteServer`s. |
| `CrossOriginEndToEndTest` | Its `allowed-hosts` is in the base. |
| `LoginGateEndToEndTest` | It removes the secrets after each test already. Keep that. |

- [ ] **Step 2: `WorkflowSetupAuthenticationTest`**

In `WorkflowSetupControllerTest.java`, the class `WorkflowSetupAuthenticationTest`:
- loses its fully qualified `SpringBootTest` (with its redundant scheduler property) and `AutoConfigureMockMvc` annotations, its `@DynamicPropertySource data(...)` method, and its `@MockitoBean WorkflowHttpClient http` field;
- becomes `class WorkflowSetupAuthenticationTest extends FullAppTest`;
- `verifyNoInteractions(http)` becomes `verifyNoInteractions(workflowHttp)`.

It asserts `store.all()).isEmpty()` and `login.loginRequired()).isFalse()`, which hold after a reset.

- [ ] **Step 3: `SportsModuleSwitchTest.OnButUnconfigured` becomes `SportsUnconfiguredTest`**

Create `src/test/java/dev/andre/homecontrol/sources/sports/SportsUnconfiguredTest.java` from the nested class:
- keep its Javadoc and its test `theModuleIsPresentButUnavailable`;
- make it `class SportsUnconfiguredTest extends FullAppTest`, keeping `@Autowired ContentSources sources`;
- `dataDir.resolve("sports.json")` becomes `dataDir().resolve("sports.json")`.

Delete the nested class from `SportsModuleSwitchTest`, with the imports only it used. `TheSportsDbOff` stays.

- [ ] **Step 4: Run the classes**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.HomeControlApplicationTest' --tests 'dev.andre.homecontrol.sources.tmdb.TmdbModuleEnabledTest' --tests 'dev.andre.homecontrol.web.StaticAssetsTest' --tests 'dev.andre.homecontrol.web.DeviceStateStreamEndToEndTest' --tests 'dev.andre.homecontrol.web.CrossOriginEndToEndTest' --tests 'dev.andre.homecontrol.web.LoginGateEndToEndTest' --tests 'dev.andre.homecontrol.sources.workflows.WorkflowSetupAuthenticationTest' --tests 'dev.andre.homecontrol.sources.sports.SportsUnconfiguredTest' --tests 'dev.andre.homecontrol.sources.sports.SportsModuleSwitchTest' --tests 'dev.andre.homecontrol.testsupport.FullAppResetTest' --rerun`

Expected: PASS.
- Every count is unchanged, except `SportsModuleSwitchTest` (one fewer, now only `$TheSportsDbOff`) and the new `SportsUnconfiguredTest` (one).
- Look at the `Started` lines: at most one per PID comes from a `FullAppTest` class.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/dev/andre/homecontrol/HomeControlApplicationTest.java src/test/java/dev/andre/homecontrol/sources/tmdb/TmdbModuleEnabledTest.java src/test/java/dev/andre/homecontrol/web/StaticAssetsTest.java src/test/java/dev/andre/homecontrol/web/DeviceStateStreamEndToEndTest.java src/test/java/dev/andre/homecontrol/web/CrossOriginEndToEndTest.java src/test/java/dev/andre/homecontrol/web/LoginGateEndToEndTest.java src/test/java/dev/andre/homecontrol/sources/workflows/WorkflowSetupControllerTest.java src/test/java/dev/andre/homecontrol/sources/sports/SportsModuleSwitchTest.java src/test/java/dev/andre/homecontrol/sources/sports/SportsUnconfiguredTest.java
git commit -m "test: move the plain full-application tests onto the shared context

SportsModuleSwitchTest.OnButUnconfigured moves to SportsUnconfiguredTest, unchanged."
```

End the message with your `Co-Authored-By:` trailer.

### Task 5: `LoginGatingTest` without a new context per test

**Files:**
- Modify: `src/test/java/dev/andre/homecontrol/web/LoginGatingTest.java`

**Interfaces:**
- Consumes: `FullAppTest`, `FullAppReset.reset(ApplicationContext)` (Task 3).
- Produces: nothing.

- [ ] **Step 1: Replace the per-test context with a per-test reset**

Apply Task 4's recipe. In addition:
- remove `@DirtiesContext(classMode = AFTER_EACH_TEST_METHOD)`;
- its `@AfterEach deleteSecrets()` becomes:

```java
    /** The context is shared, so every test starts from a fresh install: no login, no secrets, no rate limit. */
    @AfterEach
    void freshInstall() {
        FullAppReset.reset(context);
    }
```

  `context` is an `@Autowired ApplicationContext`. Add the field if the class has none;
- its static `dataDir` field goes, and file checks use `dataDir()`.

- [ ] **Step 2: Run it**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.web.LoginGatingTest' --tests 'dev.andre.homecontrol.web.LoginGateEndToEndTest' --rerun`

Expected: PASS, with 14 tests in `LoginGatingTest`. The result file shows one `Started` line for the two classes in a JVM, not 14.

- [ ] **Step 3: Show the per-test reset is needed**

Temporarily comment out the body of `freshInstall()` and run the Step 2 command again.

Expected: FAIL in a later test of `LoginGatingTest`, for example a 429 from the rate limiter, or a login that already exists. Restore the body, and check that `git diff` shows only Step 1's changes.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/dev/andre/homecontrol/web/LoginGatingTest.java
git commit -m "test: reset the shared application after each login-gating test instead of restarting it"
```

End the message with your `Co-Authored-By:` trailer.

### Task 6: The Jellyfin end-to-end tests

**Files:**
- Modify: `src/test/java/dev/andre/homecontrol/web/JellyfinEndToEndTest.java`, `src/test/java/dev/andre/homecontrol/web/SpeakerJellyfinEndToEndTest.java`

**Interfaces:**
- Consumes: `FullAppTest` (Task 3).
- Produces: nothing.

- [ ] **Step 1: Move both classes**

Apply Task 4's recipe.

| Class | Also |
| --- | --- |
| `JellyfinEndToEndTest` | Its Cast timeouts are in the base. Its static `dataDir` goes: the `sources.json` and `secrets.json` checks read `dataDir()`. Its `FakeJellyfinServer`, `FakeRemoteServer` and `FakeCastReceiver` stay per test. |
| `SpeakerJellyfinEndToEndTest` | Its UPnP poll intervals are in the base. Its `ssdp.enabled=false` is already the test configuration's value. Its fakes stay per test. |

- [ ] **Step 2: Run them**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.web.JellyfinEndToEndTest' --tests 'dev.andre.homecontrol.web.SpeakerJellyfinEndToEndTest' --rerun`

Expected: PASS, with counts unchanged.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/dev/andre/homecontrol/web/JellyfinEndToEndTest.java src/test/java/dev/andre/homecontrol/web/SpeakerJellyfinEndToEndTest.java
git commit -m "test: move the Jellyfin end-to-end tests onto the shared context"
```

End the message with your `Co-Authored-By:` trailer.

### Task 7: The streaming-launcher and sports end-to-end tests, on the shared TMDB and TheSportsDB fakes

**Files:**
- Modify: `src/test/java/dev/andre/homecontrol/web/StreamingLaunchersEndToEndTest.java`, `src/test/java/dev/andre/homecontrol/web/SportsEndToEndTest.java`

**Interfaces:**
- Consumes: `FullAppTest`, `SharedFakes.tmdb()` and `SharedFakes.theSportsDb()` (Tasks 2–3).
- Produces: nothing.

- [ ] **Step 1: Move `StreamingLaunchersEndToEndTest`**

Apply Task 4's recipe. In addition:
- its static `TMDB` field (a `FakeTmdbServer` with `withStandardResponses()` in a static initializer or `@BeforeAll`) becomes the shared fake: add `@BeforeAll static void routes() { SharedFakes.tmdb().withStandardResponses(); }`;
- replace every use of `TMDB` with `SharedFakes.tmdb()`, or with a `private static final FakeTmdbServer TMDB = SharedFakes.tmdb();` field if that reads better;
- remove its `@AfterAll` that closes the fake;
- its static `dataDir` goes: the `pinned.json` and `secrets.json` checks read `dataDir()`;
- its per-test `FakeRemoteServer` stays.

- [ ] **Step 2: Move `SportsEndToEndTest`**

Apply Task 4's recipe. In addition:
- its `SPORTSDB` field becomes `SharedFakes.theSportsDb()`, and the routes it set in its static block move to `@BeforeAll`;
- its `FakeCalendarServer` stays per class. It is started in `@BeforeAll` and closed in `@AfterAll`, because its URL reaches the application through the setup form, not a property;
- `stopServers()` is no longer called at the end of the `@Order(2)` test. It becomes the `@AfterAll` that closes only the calendar fake;
- its static `dataDir` goes: the `sports.json` and `secrets.json` checks read `dataDir()`.

- [ ] **Step 3: Run them, alone and together**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.web.StreamingLaunchersEndToEndTest' --tests 'dev.andre.homecontrol.web.SportsEndToEndTest' --rerun`

Expected: PASS, with counts unchanged. Then run the two in one JVM (`--max-workers=1` with the same `--tests`): PASS. That is the order in which the pin that sports adds would break the streaming launchers' "exactly 2 pins", if the reset missed it.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/dev/andre/homecontrol/web/StreamingLaunchersEndToEndTest.java src/test/java/dev/andre/homecontrol/web/SportsEndToEndTest.java
git commit -m "test: move the streaming-launcher and sports end-to-end tests onto the shared context and fakes"
```

End the message with your `Co-Authored-By:` trailer.

### Task 8: The YouTube tests, on the shared Google fake

**Files:**
- Modify: `src/test/java/dev/andre/homecontrol/web/YouTubeEndToEndTest.java`, `src/test/java/dev/andre/homecontrol/web/YouTubeBrowserOAuthTest.java`

**Interfaces:**
- Consumes: `FullAppTest`, `SharedFakes.google()` (Tasks 2–3).
- Produces: nothing.

- [ ] **Step 1: Move both classes**

Apply Task 4's recipe. In each:
- its static `GOOGLE` field becomes `SharedFakes.google()`, and the routes it set in a static initializer move to `@BeforeAll`. In `YouTubeEndToEndTest` these are `oauthApproves().youtubeLibrary().thumbnails()`, the search route and the fast device code. In `YouTubeBrowserOAuthTest` they are `youtubeLibrary()`, the token route and the revoke route;
- its `@AfterAll` no longer closes the fake or deletes the data directory; remove that `@AfterAll` if nothing else is left in it;
- its static data-directory field goes: the `secrets.json` checks read `dataDir()`;
- `YouTubeEndToEndTest`'s per-test `FakeRemoteServer` and `FakeCastReceiver` stay. Its `@ExtendWith(OutputCaptureExtension.class)` stays too: it is JUnit's, not Spring's, and does not change the context.

- [ ] **Step 2: Run them, alone and in one JVM**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.web.YouTubeEndToEndTest' --tests 'dev.andre.homecontrol.web.YouTubeBrowserOAuthTest' --rerun`, then the same with `--max-workers=1`.

Expected: PASS both times, with counts unchanged. In one JVM, `YouTubeBrowserOAuthTest`'s "no device-code request" and "exactly one revoke" hold after `YouTubeEndToEndTest`, and `YouTubeEndToEndTest`'s quota figures hold after `YouTubeBrowserOAuthTest`, whichever runs first.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/dev/andre/homecontrol/web/YouTubeEndToEndTest.java src/test/java/dev/andre/homecontrol/web/YouTubeBrowserOAuthTest.java
git commit -m "test: move the YouTube tests onto the shared context and Google fake"
```

End the message with your `Co-Authored-By:` trailer.

### Task 9: The rules

**Files:**
- Modify: `src/test/java/dev/andre/homecontrol/testsupport/SharedContextRulesTest.java`

**Interfaces:**
- Consumes: `FullAppTest` (Task 3).
- Produces: nothing.

- [ ] **Step 1: `FullAppTest` joins the shared bases, and a new rule lists the other contexts**

In `SharedContextRulesTest`:
1. `isASharedContextTest` also accepts a proper subclass of `FullAppTest`. The predicates' descriptions and the class Javadoc name all three bases.
2. Add the rule and its predicate:

```java
    /** The only full-application tests with a context of their own, because their beans or properties differ. */
    private static final Set<String> OWN_CONTEXT = Set.of(
            "CastEndToEndTest",               // Cast heartbeat and stale timeout for its own receiver
            "WebOsEndToEndTest", "TizenEndToEndTest", "SpeakersEndToEndTest", // SSDP on, TV modules off
            "BluetoothSpeakerEndToEndTest", "BluetoothJellyfinEndToEndTest",  // a @Primary fake BlueZ and mpv
            "WorkflowEndToEndTest",           // a recording device adapter
            "TheSportsDbOff", "WorkflowOnlySetupTest");                       // mixed module combinations

    private static final DescribedPredicate<JavaClass> A_SPRING_BOOT_TEST = DescribedPredicate.describe(
            "a @SpringBootTest, directly, through a composed annotation or by inheritance",
            type -> Stream.concat(Stream.of(type), type.getAllRawSuperclasses().stream())
                    .anyMatch(each -> each.isAnnotatedWith(SpringBootTest.class)
                            || each.isMetaAnnotatedWith(SpringBootTest.class)));

    @Test
    void everySpringBootTestSharesAContextOrIsListed() {
        classes().that(A_SPRING_BOOT_TEST)
                .should().beAssignableTo(FullAppTest.class)
                .orShould().beAssignableTo(ModulesOffTest.class)
                .orShould(haveSimpleNameIn(OWN_CONTEXT))
                .check(TESTS);
    }
```

   Write `haveSimpleNameIn` as an `ArchCondition<JavaClass>`, or use whatever ArchUnit 1.5.1 offers for "simple name in a set". Keep the meaning: a listed simple name passes.
3. Add the imports (`Set`, `SpringBootTest`).

- [ ] **Step 2: Run it, then show the new rule failing**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.testsupport.SharedContextRulesTest' --rerun`

Expected: PASS, 6 of 6.

Then, temporarily, add a class `TemporaryOwnContext` to the test tree with `@org.springframework.boot.test.context.SpringBootTest` and no tests. Run again.

Expected: FAIL in `everySpringBootTestSharesAContextOrIsListed`, naming `TemporaryOwnContext`. Delete it.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/dev/andre/homecontrol/testsupport/SharedContextRulesTest.java
git commit -m "test: let only listed tests keep a Spring context of their own"
```

End the message with your `Co-Authored-By:` trailer.

### Task 10: Measure, check order independence, document

**Files:**
- Modify: `docs/dev/testing.md` (new section "Full-application tests"; the "End to end" row of "Test layers"), `docs/dev/architecture.md` ("Progress measures")

**Interfaces:**
- Consumes: everything above.
- Produces: nothing.

- [ ] **Step 1: Three runs with four JVMs, and one with a single JVM**

```bash
time scripts/gradle.sh test --rerun
time scripts/gradle.sh test --rerun
time scripts/gradle.sh test --rerun
time scripts/gradle.sh test --rerun --max-workers=1
```

After each run, read the count and failures from `build/test-results/test/*.xml`, and count the context starts per PID with Task 5 of plan 1.3d-2's commands, writing the lines to `scratchpad/p13d3-starts-<n>.txt`.

Expected:
- All four runs green, with 2,765 tests.
- At most one `Started` line per PID comes from a `FullAppTest` class.
- The single-JVM run has one full-application context for all 15 classes.
- The four-JVM runs total roughly 18–22 starts, fewer than 10 per JVM. That is the spec's target, "under 10 per fork".
- A failure caused by state a previous class left is a reset gap: fix it in `FullAppReset` and run again. A known flake that passes alone is recorded.

- [ ] **Step 2: The testing guide**

In `docs/dev/testing.md`, the "Test layers" row "End to end" becomes:

```markdown
| End to end | The whole application with fake devices or services, over MockMvc or real HTTP: one shared `FullAppTest` context, reset after every class; a few keep their own. | classes named `*EndToEndTest` |
```

After the "Module switches" section, add:

```markdown
## Full-application tests

A test of the whole application extends `testsupport.FullAppTest`: one context per test JVM with every module on, a
real port and MockMvc. After every class, `FullAppReset` returns the application to a fresh install: no devices, no
login or secrets, no sports settings, pins or workflows, an unused YouTube quota, no rate limit, fresh rails and
settings files, and the shared web-API fakes (`SharedFakes`: TMDB, Google, TheSportsDB) reset.
- A test that sets up state it cannot leave for the next class relies on that reset; a test class that needs a fresh
  install after every test calls `FullAppReset.reset(context)` in `@AfterEach`, as `LoginGatingTest` does.
- A shared fake's routes go in `@BeforeAll` or the test, never a static initializer, and a test never closes it.
- A test that needs other beans or properties keeps a `@SpringBootTest` of its own and is named in
  `SharedContextRulesTest.OWN_CONTEXT`, with the reason.
- New state that outlives a test class needs a line in `FullAppReset`, using the bean's own operations; only where
  none exists does the bean get a small reset method, documented as existing for the shared test context.
```

- [ ] **Step 3: The progress measures**

In `docs/dev/architecture.md`, "Progress measures":
- the context-starts row's "Now" cell gets the median four-JVM total and its spread, in the style `20 (four JVMs: 5, 6, 4, 5)`;
- the summed test-class time and `test` wall-time rows get the median four-JVM figures, in their existing style;
- the one-JVM summed class time comes from the single-JVM run, for example `300 s (one JVM), 600 s (four JVMs)`.

- [ ] **Step 4: Build and check**

```bash
scripts/gradle.sh build
scripts/gradle.sh compileE2eJava
git diff --stat origin/main -- src/main
git grep -n '\.reset()\|\.reload()' origin/main -- src/main | wc -l
git grep -n '\.reset()\|\.reload()' -- src/main | wc -l
```

Expected:
- The build is green, and the browser tests compile.
- The diff names only `LoginRateLimiter.java`, `QuotaLedger.java` and `WorkflowStore.java`.
- Both counts are the same (5 on `286f323`): nothing in `src/main` calls the new methods.

- [ ] **Step 5: Commit**

```bash
git add docs/dev/testing.md docs/dev/architecture.md
git commit -m "docs: describe the shared full-application context and record Phase 1.3d's measures"
```

End the message with your `Co-Authored-By:` trailer.
