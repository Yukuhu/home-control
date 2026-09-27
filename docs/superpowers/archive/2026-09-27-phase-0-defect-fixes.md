# Phase 0 Defect Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the five confirmed defects of the architecture roadmap's Phase 0: calendar DNS rebinding, a state listener that can half-connect a TV, security headers that depend on a password, HTTP bodies that can stall rail refreshes forever, and tests that never load the production configuration.

**Architecture:** Each defect is fixed where it lives, with the smallest shared piece that the roadmap's Phase 2 builds on: `sources.http.VettedHttpClients` (Apache HttpClient 5 connecting only to policy-vetted addresses, extracted from `WorkflowHttpClient`) and `sources.http.BoundedBody` (a JDK `HttpClient` body handler bounded in size and time). Everything else is a local change in the class that has the defect.

**Tech Stack:** Java 25, Spring Boot 4.1.1, Apache HttpClient 5.6 (already a dependency), `java.net.http`, JUnit 5, AssertJ, Awaitility, Mockito, `com.sun.net.httpserver` fakes.

**Spec:** `docs/superpowers/specs/2026-09-27-architecture-roadmap-design.md` (section "Phase 0: Defect fixes")

## Global Constraints

- Build and test only through `scripts/gradle.sh`: it runs Gradle in the `gradle:jdk25` container; there is no local JDK. Example: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.ApplicationYamlTest'`.
- No new dependencies. Apache HttpClient 5, Awaitility and the JDK HTTP server are already on the classpath.
- Behaviour stays the same except the two visible changes this phase makes: the security headers are sent on every response, and a request timeout now covers the whole response body.
- Error messages name only the host, never a URL path or query: calendar and API secrets live there.
- Each item is its own branch and pull request from `main`: 0.5 (Task 1), 0.3 (Task 2), 0.2 (Task 3), 0.1 (Tasks 4–5), 0.4 (Tasks 6–8). 0.4 builds on 0.1, so branch it from the 0.1 branch unless 0.1 is already merged.
- `scripts/gradle.sh build` is green at the end of every item.
- Commits use the repository's conventional-commit style and end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- A deliberate Sonar exception uses the narrowest `@SuppressWarnings("java:S…")` with a one-line reason, as the existing fakes do.

## Review Focus

- **IPv6 literal calendar links** (`http://[fd00::5]/cal.ics`): vetting must accept the host with or without brackets, because HttpClient 5 may hand the resolver either form. Pinned by `vetsBracketedAndBareIpv6LiteralsAlike` in Task 5.
- **A body of exactly the cap** must be returned whole, and one byte more must still read as "too large" (a `BAD_RESPONSE`), not as "unreachable". Pinned by `returnsABodyUpToTheCap` and `keepsOneByteMoreThanTheCapSoTheCallerCanTellItIsTooLarge` in Task 6.
- **An empty body** (Jellyfin answers some POSTs with 204) must complete at once with zero bytes, not wait for the deadline. Pinned by `anEmptyBodyIsEmpty` in Task 6.
- **A listener that fails on every state**, not just once, must leave a session fully working. The Task 3 tests use listeners that always throw.
- **A page that sets a stricter Referrer-Policy** (the YouTube sign-in callback sends `no-referrer`) must keep it after the filter sets the default. Pinned by `aPageMaySetAStricterReferrerPolicyOfItsOwn` in Task 2, and by the existing `YouTubeBrowserOAuthTest`.

---

## Item 0.5: Tests bind the production configuration

Branch: `fix/test-config-overrides` from `main`.

### Task 1: Replace the test copy of `application.yaml` with overrides only

`src/test/resources/application.yaml` has the same name as the production file and hides it on the test classpath, so no Spring test has ever loaded `src/main/resources/application.yaml`. Spring Boot loads `classpath:/config/application.yaml` on top of `classpath:/application.yaml`, so moving the test file there, cut down to the values a test run must change, makes every test bind the production file. This works from an IDE too, with no profile to activate. (The spec said `application-test.yaml`; `config/` gives the same result without profile activation, and the spec is updated with this plan.)

**Files:**
- Create: `src/test/java/dev/andre/homecontrol/ApplicationYamlTest.java`
- Delete: `src/test/resources/application.yaml`
- Create: `src/test/resources/config/application.yaml`
- Modify: `src/test/java/dev/andre/homecontrol/adapters/bluetooth/BluetoothModuleSwitchTest.java` (delete `applicationYamlKeepsItOff` at lines 72-76, the `bluetoothEnabled` helper at lines 116-125, and the imports `org.yaml.snakeyaml.Yaml`, `java.io.InputStream`, `java.nio.file.Files`, `java.util.Map`)

**Interfaces:**
- Consumes: nothing.
- Produces: `src/test/resources/config/application.yaml`, the only test configuration file. Later tasks add nothing to it.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/dev/andre/homecontrol/ApplicationYamlTest.java`:

```java
package dev.andre.homecontrol;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests load the production application.yaml, with only src/test/resources/config/application.yaml on top. */
class ApplicationYamlTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    void theProductionFileIsBound() {
        runner.run(context -> {
            Environment environment = context.getEnvironment();
            // Set only in src/main/resources/application.yaml.
            assertThat(environment.getProperty("home-control.webos.port")).isEqualTo("3000");
            assertThat(environment.getProperty("home-control.deep-link-test.youtube-url"))
                    .isEqualTo("https://www.youtube.com/watch?v=aqz-KE-bpKQ");
        });
    }

    @Test
    void bluetoothStaysOffInTheProductionFile() {
        runner.run(context -> assertThat(context.getEnvironment().getProperty("home-control.bluetooth.enabled"))
                .isEqualTo("false"));
    }

    @Test
    void theTestOverridesWin() {
        runner.run(context -> {
            Environment environment = context.getEnvironment();
            assertThat(environment.getProperty("shield.data-dir")).isEqualTo("build/test-data");
            assertThat(environment.getProperty("shield.discovery-enabled")).isEqualTo("false");
            assertThat(environment.getProperty("home-control.ssdp.enabled")).isEqualTo("false");
            assertThat(environment.getProperty("home-control.content.rails.scheduler-enabled")).isEqualTo("false");
            assertThat(environment.getProperty("home-control.tmdb.api-base-url")).isEqualTo("http://127.0.0.1:9/3");
        });
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.ApplicationYamlTest'`
Expected: FAIL in `theProductionFileIsBound`, `expected: "3000" but was: null` (the test copy hides the production file). The other two pass.

- [ ] **Step 3: Replace the test configuration**

Run `git rm src/test/resources/application.yaml`, then create `src/test/resources/config/application.yaml`:

```yaml
# Test overrides only. Spring Boot loads src/main/resources/application.yaml as in production and this file
# (classpath:/config/ wins over classpath:/) on top of it, so tests bind the real configuration and change only
# what a test run must not do: discover the LAN, reach real upstream APIs, wait out production timeouts, or
# refresh rails on a schedule.
shield:
  data-dir: build/test-data
  discovery-enabled: false

home-control:
  ssdp:
    enabled: false
  tmdb:
    api-base-url: http://127.0.0.1:9/3
    connect-timeout-seconds: 1
    request-timeout-seconds: 2
  sports:
    calendar:
      connect-timeout-seconds: 1
      request-timeout-seconds: 2
    thesportsdb:
      api-base-url: http://127.0.0.1:9/api/v1/json
      connect-timeout-seconds: 1
      request-timeout-seconds: 2
  youtube:
    oauth-base-url: http://127.0.0.1:9
    api-base-url: http://127.0.0.1:9
    lounge-base-url: http://127.0.0.1:9
    thumbnail-base-url: http://127.0.0.1:9
  content:
    rails:
      scheduler-enabled: false
    search:
      timeout: 2s
```

- [ ] **Step 4: Remove the path-parsing workaround**

In `BluetoothModuleSwitchTest`, delete the test `applicationYamlKeepsItOff` and the private `bluetoothEnabled(String path)` helper; `ApplicationYamlTest.bluetoothStaysOffInTheProductionFile` covers it now. Delete the imports that become unused: `org.yaml.snakeyaml.Yaml`, `java.io.InputStream`, `java.nio.file.Files`, `java.util.Map`. Keep `java.nio.file.Path`, which other tests in the class use.

- [ ] **Step 5: Run the new test, then the whole suite**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.ApplicationYamlTest'`
Expected: PASS (3 tests).

Run: `scripts/gradle.sh build`
Expected: BUILD SUCCESSFUL. Spring tests now also see production values the old test copy lacked (the webOS, Tizen, UPnP, Sonos, Bluetooth, deep-link-test and Wake-on-LAN blocks, and `jellyfin.startup-timeout-seconds`). If a test fails because it relied on a value only the old copy set, add that one key to `config/application.yaml` with a comment saying why the test run needs it. Never change `src/main/resources/application.yaml` for a test.

- [ ] **Step 6: Commit**

```bash
git add src/test/java/dev/andre/homecontrol/ApplicationYamlTest.java src/test/resources/config/application.yaml \
        src/test/java/dev/andre/homecontrol/adapters/bluetooth/BluetoothModuleSwitchTest.java
git commit -m "test: bind the production application.yaml in tests, with only overrides on top

src/test/resources/application.yaml hid the production file on the test
classpath, so no test ever bound it. The overrides now live in
classpath:/config/application.yaml, which Spring Boot loads on top.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

(`git rm` in Step 3 already staged the deletion.)

Item 0.5 is done: follow superpowers:finishing-a-development-branch. The user decides on push and pull request.

---

## Item 0.3: Security headers on every response

Branch: `fix/security-headers-always` from `main`.

### Task 2: Send the security headers from `CrossOriginFilter`

`LoginGateFilter` returns before setting `X-Frame-Options`, `X-Content-Type-Options` and `Referrer-Policy` when no login exists (`LoginGateFilter.java:43-51`), so a deployment without a password can have its setup page framed. `CrossOriginFilter` runs first on every request (`SecurityConfiguration`, `Ordered.HIGHEST_PRECEDENCE`), so it sets them for every response, plus `Content-Security-Policy: frame-ancestors 'none'`. The full CSP waits for roadmap workstream 2E.

**Files:**
- Modify: `src/main/java/dev/andre/homecontrol/security/CrossOriginFilter.java`
- Modify: `src/main/java/dev/andre/homecontrol/security/LoginGateFilter.java:49-51`
- Test: `src/test/java/dev/andre/homecontrol/security/CrossOriginFilterTest.java`
- Modify: `src/test/java/dev/andre/homecontrol/web/LoginGatingTest.java:80`

**Interfaces:**
- Consumes: nothing.
- Produces: nothing other tasks use.

- [ ] **Step 1: Write the failing tests**

Add to `CrossOriginFilterTest` (new imports: `jakarta.servlet.http.HttpServlet`, `jakarta.servlet.http.HttpServletRequest`, `jakarta.servlet.http.HttpServletResponse`):

```java
    @Test
    void sendsTheSecurityHeadersOnEveryResponseWithOrWithoutALogin() throws Exception {
        MockHttpServletResponse allowed = new MockHttpServletResponse();
        run(filter, request("GET", "/setup"), allowed);
        MockHttpServletResponse refused = new MockHttpServletResponse();
        run(filter, request("POST", "/setup/forget", "Sec-Fetch-Site", "cross-site"), refused);
        MockHttpServletResponse misdirected = new MockHttpServletResponse();
        run(filter, withHost("evil.example:8080", "GET", "/"), misdirected);

        assertThat(refused.getStatus()).isEqualTo(403);
        assertThat(misdirected.getStatus()).isEqualTo(421);
        for (MockHttpServletResponse response : List.of(allowed, refused, misdirected)) {
            assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
            assertThat(response.getHeader("Content-Security-Policy")).isEqualTo("frame-ancestors 'none'");
            assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(response.getHeader("Referrer-Policy")).isEqualTo("same-origin");
        }
    }

    @Test
    void aPageMaySetAStricterReferrerPolicyOfItsOwn() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain(new HttpServlet() {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse servletResponse) {
                servletResponse.setHeader("Referrer-Policy", "no-referrer");
            }
        });

        filter.doFilter(request("GET", "/setup/youtube/callback"), response, chain);

        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
    }
```

In `LoginGatingTest.aDeviceOnlyDeploymentIsUnchanged`, replace

```java
                .andExpect(header().doesNotExist("X-Frame-Options"));
```

with

```java
                // No login, but the page still cannot be framed.
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Content-Security-Policy", "frame-ancestors 'none'"));
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.security.CrossOriginFilterTest' --tests 'dev.andre.homecontrol.web.LoginGatingTest'`
Expected: FAIL. `sendsTheSecurityHeadersOnEveryResponseWithOrWithoutALogin` fails with `expected: "DENY" but was: null`, and so does `aDeviceOnlyDeploymentIsUnchanged`. `aPageMaySetAStricterReferrerPolicyOfItsOwn` passes already; it guards the order of the fix.

- [ ] **Step 3: Set the headers in `CrossOriginFilter`**

In `CrossOriginFilter`, call `secure(response)` as the first statement of `doFilterInternal`, and add the method:

```java
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        secure(response);
        if (!hosts.allows(host(request))) {
            plain(response, MISDIRECTED_REQUEST,
                    "This host name is not allowed; add it to HOME_CONTROL_ALLOWED_HOSTS to use it");
            return;
        }
        if (!guard.allows(request)) {
            plain(response, HttpServletResponse.SC_FORBIDDEN, "Cross-origin request refused");
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * Every response, refused or not and whether or not a login exists: nothing may be framed (the setup page
     * works without a login too) or content-sniffed. Set before the chain runs, so a page can still choose a
     * stricter Referrer-Policy of its own, as the YouTube sign-in callback does.
     */
    private static void secure(HttpServletResponse response) {
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Content-Security-Policy", "frame-ancestors 'none'");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "same-origin"); // no-referrer would make Chrome send Origin: null
    }
```

Extend the class comment's last sentence so it reads: "…so a page on another site cannot press keys, change setup or try passwords through a visitor's LAN access. It also sends the security headers on every response."

In `LoginGateFilter.doFilterInternal`, delete the three `response.setHeader(...)` lines (49-51); `CrossOriginFilter` has already set them.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.security.*' --tests 'dev.andre.homecontrol.web.LoginGatingTest' --tests 'dev.andre.homecontrol.web.YouTubeBrowserOAuthTest' --tests 'dev.andre.homecontrol.sources.youtube.YouTubeThumbnailControllerTest' --tests 'dev.andre.homecontrol.sources.workflows.WorkflowSetupControllerTest'`
Expected: PASS. `YouTubeBrowserOAuthTest` still sees `Referrer-Policy: no-referrer` on the callback.

- [ ] **Step 5: Run the build and commit**

Run: `scripts/gradle.sh build`
Expected: BUILD SUCCESSFUL.

```bash
git add src/main/java/dev/andre/homecontrol/security/CrossOriginFilter.java \
        src/main/java/dev/andre/homecontrol/security/LoginGateFilter.java \
        src/test/java/dev/andre/homecontrol/security/CrossOriginFilterTest.java \
        src/test/java/dev/andre/homecontrol/web/LoginGatingTest.java
git commit -m "fix: send the security headers on every response, with or without a login

Without a password the login gate returned before setting X-Frame-Options,
so the setup page could be framed. The cross-origin filter, which runs first
on every request, now sets the headers and frame-ancestors 'none'.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

Item 0.3 is done: follow superpowers:finishing-a-development-branch. The user decides on push and pull request.

---

## Item 0.2: A failing state listener cannot break a session

Branch: `fix/state-listener-failures` from `main`.

### Task 3: Catch listener failures in the webOS and Bluetooth sessions

A session reports state through a `Consumer<DeviceState>` that publishes a Spring event synchronously, so any event subscriber's exception surfaces inside the session. `WebOsSession.update()` lets it escape: `connect()` has already set `connection` when it publishes CONNECTED, so the state subscription, inputs and MAC learning never run and no reconnect is scheduled. `BluetoothSpeakerSession.start()` publishes before scheduling its first poll, so a throw there means it never polls. Android TV and Cast already catch listener failures in `update()`. Tizen publishes last in a poll step that already catches, so it is left for roadmap workstream 2D.

**Files:**
- Modify: `src/main/java/dev/andre/homecontrol/adapters/webos/WebOsSession.java` (`update`, around lines 397-408)
- Modify: `src/main/java/dev/andre/homecontrol/adapters/bluetooth/BluetoothSpeakerSession.java` (`start` at lines 76-79, `publish` at lines 264-269)
- Test: `src/test/java/dev/andre/homecontrol/adapters/webos/WebOsSessionTest.java`
- Test: `src/test/java/dev/andre/homecontrol/adapters/bluetooth/BluetoothSpeakerSessionTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: nothing other tasks use.

- [ ] **Step 1: Write the failing webOS test**

In `WebOsSessionTest`, let the session helper take a listener (new import: `java.util.function.Consumer`):

```java
    private WebOsSession session(Map<String, String> settings) throws IOException {
        return session(settings, states::add);
    }

    private WebOsSession session(Map<String, String> settings, Consumer<DeviceState> listener) throws IOException {
        Device device = new Device("lg", "LG TV", DeviceKind.WEBOS, "127.0.0.1", Map.of("webos", settings), Instant.now());
        registry.save(device);
        WebOsProperties properties = new WebOsProperties(true, tv.port(), FakeWebSocketServer.closedPort(), 2, 2, 2, 1, 2, 0);
        session = new WebOsSession(device, properties, InsecureTls.httpClient(Duration.ofSeconds(2)), registry,
                learned(), new WakeOnLan(receiver.address()), listener, () -> { });
        return session;
    }
```

Add the test:

```java
    @Test
    void aFailingStateListenerDoesNotLeaveTheSessionHalfConnected() throws IOException {
        // The real listener publishes a Spring event synchronously, so any subscriber's failure lands here.
        session(Map.of("clientKey", FakeSsapServer.CLIENT_KEY), state -> {
            states.add(state);
            throw new IllegalStateException("a subscriber failed");
        }).start();
        connected();

        // Everything connect() does after publishing CONNECTED still happened.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(session.state().currentApp()).isEqualTo("com.webos.app.home");
            assertThat(session.inputs()).isNotEmpty();
            assertThat(storedSetting("macAddress")).isEqualTo("A8:23:FE:01:02:03");
        });
    }
```

- [ ] **Step 2: Write the failing Bluetooth test**

In `BluetoothSpeakerSessionTest`, let `start` take a listener (new import: `java.util.function.Consumer`):

```java
    private BluetoothSpeakerSession start(BluetoothProperties props) {
        return start(props, states::add);
    }

    private BluetoothSpeakerSession start(BluetoothProperties props,
                                          Consumer<dev.andre.homecontrol.core.DeviceState> listener) {
        // Mirrors BluetoothSpeakerAdapter.connect()'s wiring, so a test can widen a timeout via withTimings(...).
        MpvPlayer player = new MpvPlayer(launcher, MpvPlayer.socketFor(runtime, device.id()),
                Duration.ofSeconds(props.playerStartTimeoutSeconds()), Duration.ofSeconds(props.loadTimeoutSeconds()),
                Duration.ofSeconds(props.commandTimeoutSeconds()));
        AudioDeviceResolver resolver = new AudioDeviceResolver(launcher, props.audioDeviceTemplate(),
                Duration.ofSeconds(props.playerStartTimeoutSeconds()));
        session = new BluetoothSpeakerSession(device, props, bluez, player, resolver, listener);
        session.start();
        return session;
    }
```

Add the test:

```java
    @Test
    void aFailingStateListenerDoesNotStopPolling() {
        bluez.known("AA:BB:CC:DD:EE:FF", "JBL Flip 5").paired(true).connected(true).uuids(BluetoothDeviceInfo.A2DP_SINK);

        // The real listener publishes a Spring event synchronously, so any subscriber's failure lands here.
        start(properties, state -> {
            states.add(state);
            throw new IllegalStateException("a subscriber failed");
        });

        await().atMost(WAIT).untilAsserted(() -> assertThat(session.state().status()).isEqualTo(DeviceStatus.CONNECTED));
    }
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.adapters.webos.WebOsSessionTest' --tests 'dev.andre.homecontrol.adapters.bluetooth.BluetoothSpeakerSessionTest'`
Expected: FAIL. The webOS test times out in `connected()`, because the CONNECTING publish already throws out of `connect()`. The Bluetooth test fails with `IllegalStateException: a subscriber failed` thrown from `session.start()`. All other tests in both classes pass.

- [ ] **Step 4: Catch the failure in `WebOsSession.update`**

Replace the end of `update` so it reads:

```java
    /** Publishes only visible changes; entering CONNECTING is always published so every attempt shows. */
    private synchronized void update(UnaryOperator<DeviceState> change) {
        if (closed) {
            return;
        }
        DeviceState next = change.apply(state);
        if (next.sameIgnoringTime(state) && next.status() != DeviceStatus.CONNECTING) {
            return;
        }
        state = next;
        try {
            onChange.accept(next);
        } catch (RuntimeException e) {
            // The listener publishes a Spring event synchronously, to subscribers this class knows nothing about.
            // Their failure must not abort connect() after the connection is set but before its subscriptions exist.
            log.warn("A device state listener failed for {}", device.id(), e);
        }
    }
```

- [ ] **Step 5: Catch the failure in `BluetoothSpeakerSession`**

Route both publish sites through one method:

```java
    public void start() {
        report(state);
        loop.execute(this::poll);
    }
```

```java
    private void publish(DeviceState next) {
        if (!next.sameIgnoringTime(state)) {
            state = next;
            report(next);
        }
    }

    /**
     * The listener publishes a Spring event synchronously, to subscribers this class knows nothing about; their
     * failure must not stop this session from starting or polling.
     */
    private void report(DeviceState next) {
        try {
            onChange.accept(next);
        } catch (RuntimeException e) {
            log.warn("A device state listener failed for {}", device.id(), e);
        }
    }
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.adapters.webos.*' --tests 'dev.andre.homecontrol.adapters.bluetooth.*'`
Expected: PASS.

- [ ] **Step 7: Run the build and commit**

Run: `scripts/gradle.sh build`
Expected: BUILD SUCCESSFUL.

```bash
git add src/main/java/dev/andre/homecontrol/adapters/webos/WebOsSession.java \
        src/main/java/dev/andre/homecontrol/adapters/bluetooth/BluetoothSpeakerSession.java \
        src/test/java/dev/andre/homecontrol/adapters/webos/WebOsSessionTest.java \
        src/test/java/dev/andre/homecontrol/adapters/bluetooth/BluetoothSpeakerSessionTest.java
git commit -m "fix: keep a failing state listener from half-connecting webOS or stopping Bluetooth

Both sessions let a listener's exception escape: webOS was left CONNECTED
without its state subscription, inputs or MAC, and Bluetooth never polled.
They now log it, as the Android TV and Cast sessions already do.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

Item 0.2 is done: follow superpowers:finishing-a-development-branch. The user decides on push and pull request.

---

## Item 0.1: Calendars connect only to vetted addresses

Branch: `fix/calendar-dns-pinning` from `main`.

### Task 4: Extract `VettedHttpClients` from `WorkflowHttpClient`

`WorkflowHttpClient` closes the DNS rebinding gap with an HttpClient 5 `DnsResolver` that returns only the addresses its policy vetted, so the check and the connection use one lookup. Extract that client construction so calendars (Task 5) and roadmap workstream 2C can use it. A probe against HttpClient 5.6.4 confirmed the resolver is called for IP literals and host names alike, and that a `RuntimeException` it throws reaches the caller unwrapped.

**Files:**
- Create: `src/main/java/dev/andre/homecontrol/sources/http/VettedHttpClients.java`
- Test: `src/test/java/dev/andre/homecontrol/sources/http/VettedHttpClientsTest.java`
- Modify: `src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowHttpClient.java` (constructor, lines 55-81; imports)

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `dev.andre.homecontrol.sources.http.VettedHttpClients.create(AddressVetting vetting, int maxConnections, Duration connectTimeout)` returning `org.apache.hc.client5.http.impl.classic.CloseableHttpClient`.
  - `VettedHttpClients.AddressVetting`, a functional interface: `InetAddress[] addresses(String host) throws UnknownHostException`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/dev/andre/homecontrol/sources/http/VettedHttpClientsTest.java`:

```java
package dev.andre.homecontrol.sources.http;

import com.sun.net.httpserver.HttpServer;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VettedHttpClientsTest {

    private static final VettedHttpClients.AddressVetting LOOPBACK =
            host -> new InetAddress[] {InetAddress.ofLiteral("127.0.0.1")};

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> {
            requests.incrementAndGet();
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (exchange; OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/moved", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Location", "/ok");
                exchange.sendResponseHeaders(302, -1);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    /** A host name no DNS knows: a request can only reach the fake through the vetted address. */
    private URI unresolvable(String path) {
        return URI.create("http://calendar.test:" + server.getAddress().getPort() + path);
    }

    @Test
    void connectsToTheAddressesTheVettingReturned() throws IOException {
        List<String> vetted = new CopyOnWriteArrayList<>();
        try (CloseableHttpClient http = VettedHttpClients.create(host -> {
            vetted.add(host);
            return LOOPBACK.addresses(host);
        }, 2, Duration.ofSeconds(1))) {
            String body = http.execute(new HttpGet(unresolvable("/ok")),
                    response -> EntityUtils.toString(response.getEntity()));

            assertThat(body).isEqualTo("ok");
        }
        assertThat(vetted).containsExactly("calendar.test");
    }

    @Test
    void aVettingRefusalStopsTheRequestBeforeItConnects() throws IOException {
        try (CloseableHttpClient http = VettedHttpClients.create(host -> {
            throw new UnknownHostException(host + " is not allowed");
        }, 2, Duration.ofSeconds(1))) {
            HttpGet request = new HttpGet(unresolvable("/ok"));

            assertThatThrownBy(() -> http.execute(request, response -> response.getCode()))
                    .isInstanceOf(UnknownHostException.class)
                    .hasMessage("calendar.test is not allowed");
        }
        assertThat(requests).hasValue(0);
    }

    @Test
    void redirectsAreReturnedNotFollowed() throws IOException {
        try (CloseableHttpClient http = VettedHttpClients.create(LOOPBACK, 2, Duration.ofSeconds(1))) {
            int status = http.execute(new HttpGet(unresolvable("/moved")), response -> response.getCode());

            assertThat(status).isEqualTo(302);
        }
        assertThat(requests).hasValue(0);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.http.VettedHttpClientsTest'`
Expected: FAIL to compile, `cannot find symbol: class VettedHttpClients`.

- [ ] **Step 3: Write `VettedHttpClients`**

Create `src/main/java/dev/andre/homecontrol/sources/http/VettedHttpClients.java`:

```java
package dev.andre.homecontrol.sources.http;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;

/**
 * Apache HttpClient 5 clients that connect only to addresses an outbound policy has vetted. The policy's lookup is
 * the connection's lookup, so a host cannot pass the check with one address and be connected to at another (DNS
 * rebinding). Redirects, retries, cookies, authentication caching, compression and connection reuse are off:
 * callers follow redirects themselves and vet every hop.
 */
public final class VettedHttpClients {

    /** Looks a host up and returns only addresses the caller's policy allows, or throws. */
    @FunctionalInterface
    public interface AddressVetting {
        InetAddress[] addresses(String host) throws UnknownHostException;
    }

    private VettedHttpClients() {
    }

    public static CloseableHttpClient create(AddressVetting vetting, int maxConnections, Duration connectTimeout) {
        DnsResolver dns = new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                return vetting.addresses(host);
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return host;
            }
        };
        var manager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(dns)
                .setMaxConnTotal(maxConnections)
                .setMaxConnPerRoute(maxConnections)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofMilliseconds(Math.max(1, connectTimeout.toMillis()))).build())
                .build();
        return HttpClients.custom().setConnectionManager(manager)
                .disableAutomaticRetries().disableRedirectHandling().disableCookieManagement()
                .disableAuthCaching().disableContentCompression()
                .setConnectionReuseStrategy((request, response, context) -> false)
                .build();
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.http.VettedHttpClientsTest'`
Expected: PASS (3 tests).

- [ ] **Step 5: Build `WorkflowHttpClient`'s client with it**

In the `WorkflowHttpClient` constructor, replace everything from `DnsResolver dns = new DnsResolver() {` to the end of the `http = HttpClients.custom()…build();` statement with:

```java
        http = VettedHttpClients.create(host -> {
            Operation<?> operation = current.get();
            operation.check();
            InetAddress[] addresses = policy.addresses(host);
            // A platform resolver can ignore interruption. Never connect after it returns late.
            operation.check();
            return addresses;
        }, properties.maxConcurrentFetches(), properties.connectTimeout());
```

Add `import dev.andre.homecontrol.sources.http.VettedHttpClients;` and delete the imports that become unused: `org.apache.hc.client5.http.DnsResolver`, `org.apache.hc.client5.http.config.ConnectionConfig`, `org.apache.hc.client5.http.impl.classic.HttpClients`, `org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder`. Keep `RequestConfig`, `Timeout`, `InetAddress` and `UnknownHostException`, which the class still uses.

- [ ] **Step 6: Run the workflow tests to verify nothing changed**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.workflows.*' --tests 'dev.andre.homecontrol.sources.http.*'`
Expected: PASS, including `WorkflowHttpClientTest.totalDeadlineStopsAContinuouslyTricklingBody` and its DNS-pinning tests.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/sources/http/VettedHttpClients.java \
        src/test/java/dev/andre/homecontrol/sources/http/VettedHttpClientsTest.java \
        src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowHttpClient.java
git commit -m "refactor: extract the vetted-address HTTP client from the workflow fetcher

The client that connects only to addresses a policy vetted is what closes
DNS rebinding; calendars need it too.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 5: Fetch calendars through the vetted client

`CalendarFetcher.fetch` checks the address policy, then lets the JDK client resolve the host again when it connects (`CalendarFetcher.java:52-53`). A hostile calendar host or redirect target can answer the check with an allowed address and the connection with a loopback or LAN one. The fetcher moves to `VettedHttpClients` with `CalendarUrlPolicy.addresses` as the vetting, so the connection goes to exactly the checked addresses. It keeps the up-front `checkAddress` for a clear error before any connection. The injectable JDK `HttpClient` constructor goes, and with it the two Mockito tests built on it.

**Files:**
- Modify: `src/main/java/dev/andre/homecontrol/sources/sports/calendar/CalendarUrlPolicy.java` (`checkAddress`, lines 68-84)
- Modify: `src/main/java/dev/andre/homecontrol/sources/sports/calendar/CalendarFetcher.java` (whole class)
- Test: `src/test/java/dev/andre/homecontrol/sources/sports/calendar/CalendarUrlPolicyTest.java`
- Test: `src/test/java/dev/andre/homecontrol/sources/sports/calendar/CalendarFetcherTest.java`
- Test: `src/test/java/dev/andre/homecontrol/sources/sports/calendar/CalendarFetcherFailureTest.java`

**Interfaces:**
- Consumes: `VettedHttpClients.create(AddressVetting, int, Duration)` from Task 4.
- Produces:
  - `CalendarUrlPolicy.addresses(String host)` returning `InetAddress[]`. It throws `CalendarFetchException` with kind `UNREACHABLE` or `BLOCKED`, and accepts IPv6 literals with or without brackets.
  - `CalendarFetcher` now `implements AutoCloseable` (`close()` throws nothing). Its only constructor is `CalendarFetcher(SportsProperties.Calendar, CalendarUrlPolicy)`. Its private `request(URI target, int hop)` method is where Task 8 adds the deadline.

- [ ] **Step 1: Write the failing policy test**

Add to `CalendarUrlPolicyTest` (new import: `java.net.InetAddress`; add `assertThatThrownBy` to the static imports if missing):

```java
    @Test
    void vetsBracketedAndBareIpv6LiteralsAlike() {
        CalendarUrlPolicy lan = new CalendarUrlPolicy(false);

        assertThat(lan.addresses("[fd00::5]")).containsExactly(InetAddress.ofLiteral("fd00::5"));
        assertThat(lan.addresses("fd00::5")).containsExactly(InetAddress.ofLiteral("fd00::5"));
        assertThatThrownBy(() -> lan.addresses("[::1]"))
                .isInstanceOf(CalendarFetchException.class)
                .hasFieldOrPropertyWithValue("kind", CalendarFetchException.Kind.BLOCKED);
    }
```

- [ ] **Step 2: Write the failing fetcher tests**

In `CalendarFetcherTest`, add `fetcher.close();` as the first line of `stop()`. Add these tests (new imports: `java.net.URI`, `java.util.concurrent.atomic.AtomicInteger`):

```java
    /** A host name no DNS knows: the fetch can only reach the fake through the address the policy vetted. */
    private URI unresolvable(String path) {
        return URI.create("http://calendar.test:" + server.url("/").getPort() + path);
    }

    @Test
    void connectsToTheAddressThePolicyVetted() {
        server.respondFixture("/cal.ics", "bundesliga.ics");
        CalendarUrlPolicy.HostResolver loopback = host -> new InetAddress[] {InetAddress.ofLiteral("127.0.0.1")};

        try (CalendarFetcher pinned = new CalendarFetcher(properties, new CalendarUrlPolicy(true, loopback))) {
            assertThat(pinned.fetch(unresolvable("/cal.ics"))).startsWith("BEGIN:VCALENDAR");
        }
    }

    @Test
    void aHostThatRebindsToThisMachineAfterTheCheckIsBlocked() {
        server.respondFixture("/cal.ics", "bundesliga.ics");
        AtomicInteger lookups = new AtomicInteger();
        // The first answer passes the policy; every later one points at this machine, which the policy refuses.
        CalendarUrlPolicy.HostResolver rebinding = host -> new InetAddress[] {lookups.getAndIncrement() == 0
                ? InetAddress.ofLiteral("192.0.2.10") : InetAddress.ofLiteral("127.0.0.1")};

        try (CalendarFetcher rebound = new CalendarFetcher(properties, new CalendarUrlPolicy(false, rebinding))) {
            URI url = unresolvable("/cal.ics");
            assertThatThrownBy(() -> rebound.fetch(url))
                    .isInstanceOf(CalendarFetchException.class)
                    .hasFieldOrPropertyWithValue("kind", CalendarFetchException.Kind.BLOCKED);
        }
        assertThat(server.count("/cal.ics")).isZero();
    }
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.sports.calendar.*'`
Expected: FAIL to compile, `cannot find symbol: method addresses(String)` and `incompatible types: CalendarFetcher cannot be converted to AutoCloseable`.

- [ ] **Step 4: Add `CalendarUrlPolicy.addresses`**

Replace `checkAddress` with:

```java
    /**
     * Looks the host up and returns its addresses if every one of them may be fetched from; otherwise throws.
     * The fetcher connects to exactly these addresses, so the check and the connection cannot disagree.
     * Accepts an IPv6 literal with or without its URI brackets.
     */
    public InetAddress[] addresses(String host) {
        String lookup = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(lookup);
        } catch (UnknownHostException _) {
            throw new CalendarFetchException(CalendarFetchException.Kind.UNREACHABLE, "Could not find " + host);
        }
        for (InetAddress address : addresses) {
            if (address.isAnyLocalAddress() || address.isLinkLocalAddress() || address.isMulticastAddress()
                    || (address.isLoopbackAddress() && !allowLoopback)) {
                throw new CalendarFetchException(CalendarFetchException.Kind.BLOCKED, "Home Control does not load calendars from "
                        + host + ": that address belongs to this machine or its network link");
            }
        }
        return addresses;
    }

    public void checkAddress(URI uri) {
        addresses(uri.getHost());
    }
```

- [ ] **Step 5: Move `CalendarFetcher` onto the vetted client**

Replace the whole of `CalendarFetcher.java` with:

```java
package dev.andre.homecontrol.sources.sports.calendar;

import dev.andre.homecontrol.sources.http.VettedHttpClients;
import dev.andre.homecontrol.sources.sports.SportsProperties;
import dev.andre.homecontrol.sources.sports.calendar.CalendarFetchException.Kind;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches a calendar over HTTP(S), following only validated redirects. Never trusts the caller's URL:
 * {@link CalendarUrlPolicy} vets every hop, and the connection goes to exactly the addresses it vetted, so a
 * host cannot pass the check with one address and be connected to at another.
 */
public class CalendarFetcher implements AutoCloseable {

    /** The schedule fetches calendars one after another; a few more connections cover the setup page's checks. */
    private static final int MAX_CONNECTIONS = 4;
    private static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);
    private static final Pattern CHARSET = Pattern.compile("charset=\"?([^;\"]+)\"?", Pattern.CASE_INSENSITIVE);

    private final SportsProperties.Calendar properties;
    private final CalendarUrlPolicy policy;
    private final CloseableHttpClient http;

    public CalendarFetcher(SportsProperties.Calendar properties, CalendarUrlPolicy policy) {
        this.properties = properties;
        this.policy = policy;
        this.http = VettedHttpClients.create(policy::addresses, MAX_CONNECTIONS,
                Duration.ofSeconds(properties.connectTimeoutSeconds()));
    }

    public String fetch(URI url) {
        URI current = url;
        for (int hop = 0; ; hop++) {
            Hop answer = request(current, hop);
            if (answer.redirect() == null) {
                return answer.text();
            }
            current = answer.redirect();
        }
    }

    /** One request's outcome: the calendar text, or the validated link it redirected to. */
    private record Hop(String text, URI redirect) {
    }

    private Hop request(URI target, int hop) {
        String host = target.getHost();
        if (Thread.currentThread().isInterrupted()) {
            // Shutting down: keep the interrupt for the caller and open no new connection.
            throw new CalendarFetchException(Kind.UNREACHABLE, "Could not reach " + host);
        }
        // A clear answer before any connection; the connection itself is vetted again by the same policy.
        policy.checkAddress(target);
        Timeout timeout = Timeout.ofSeconds(properties.requestTimeoutSeconds());
        HttpGet request = new HttpGet(target);
        request.setConfig(RequestConfig.custom()
                .setAuthenticationEnabled(false)
                .setHardCancellationEnabled(true)
                .setConnectionRequestTimeout(timeout)
                .setResponseTimeout(timeout)
                .build());
        request.setHeader("Accept", "text/calendar, text/plain;q=0.9, */*;q=0.5");
        request.setHeader("User-Agent", "HomeControl");
        CloseableHttpResponse response = null;
        try {
            response = CloseableHttpResponse.adapt(http.executeOpen(null, request, null));
            return read(response, target, hop);
        } catch (IOException e) {
            throw unreachable(host, e);
        } finally {
            // Never drain a redirect or error body: drop the connection at once.
            request.cancel();
            if (response != null) {
                response.close(CloseMode.IMMEDIATE);
            }
        }
    }

    /** Reads the calendar from a 200, or where a redirect points; every other answer ends the fetch. */
    private Hop read(CloseableHttpResponse response, URI current, int hop) throws IOException {
        String host = current.getHost();
        int status = response.getCode();
        if (REDIRECTS.contains(status)) {
            return new Hop(null, redirectTarget(response, current, hop));
        }
        if (status == 401 || status == 403) {
            throw new CalendarFetchException(Kind.UNAUTHORIZED, host + " refused access to the calendar");
        }
        if (status == 404 || status == 410) {
            throw new CalendarFetchException(Kind.NOT_FOUND, host + " has no calendar at that link");
        }
        if (status != 200) {
            throw new CalendarFetchException(Kind.BAD_RESPONSE, host + " answered HTTP " + status);
        }
        HttpEntity entity = response.getEntity();
        byte[] bytes = entity == null ? new byte[0] : entity.getContent().readNBytes(properties.maxBytes() + 1);
        if (bytes.length > properties.maxBytes()) {
            throw new CalendarFetchException(Kind.TOO_LARGE,
                    "The calendar is larger than " + (properties.maxBytes() / 1_048_576) + " MB");
        }
        return new Hop(new String(bytes, charsetOf(response)), null);
    }

    private URI redirectTarget(CloseableHttpResponse response, URI current, int hop) {
        String host = current.getHost();
        Header location = response.getFirstHeader("Location");
        if (location == null) {
            throw new CalendarFetchException(Kind.BAD_RESPONSE, host + " answered HTTP " + response.getCode());
        }
        if (hop >= properties.maxRedirects()) {
            throw new CalendarFetchException(Kind.BAD_RESPONSE, "The calendar link redirected too many times");
        }
        try {
            return policy.parse(current.resolve(location.getValue()).toString());
        } catch (IllegalArgumentException _) {
            throw new CalendarFetchException(Kind.BAD_RESPONSE,
                    host + " redirected to a link Home Control does not follow");
        }
    }

    private static Charset charsetOf(CloseableHttpResponse response) {
        Header contentType = response.getFirstHeader("Content-Type");
        if (contentType != null) {
            Matcher m = CHARSET.matcher(contentType.getValue());
            if (m.find()) {
                try {
                    if (Charset.isSupported(m.group(1).strip())) {
                        return Charset.forName(m.group(1).strip());
                    }
                } catch (IllegalCharsetNameException | UnsupportedCharsetException _) {
                    // fall through to UTF-8
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static CalendarFetchException unreachable(String host, Exception cause) {
        String message = cause.getMessage();
        boolean leaksUrl = message != null && message.toLowerCase(Locale.ROOT).contains(host.toLowerCase(Locale.ROOT))
                && message.contains("/");
        return leaksUrl
                ? new CalendarFetchException(Kind.UNREACHABLE, "Could not reach " + host)
                : new CalendarFetchException(Kind.UNREACHABLE, "Could not reach " + host, cause);
    }

    @Override
    public void close() {
        http.close(CloseMode.IMMEDIATE);
    }
}
```

`SportsConfiguration.calendarFetcher` needs no change: Spring calls the public `close()` of a `@Bean` on shutdown.

- [ ] **Step 6: Replace the two Mockito tests in `CalendarFetcherFailureTest`**

The injectable JDK client is gone, so delete `anInterruptedFetchIsUnreachableAndKeepsTheInterrupt` and `aBrokenBodyIsUnreachable`, the `CALENDAR` constant, and the imports `java.io.InputStream`, `java.net.http.HttpClient`, `java.net.http.HttpResponse`, `org.mockito.ArgumentMatchers.any`, `org.mockito.BDDMockito.given` and `org.mockito.Mockito.mock`. Add these tests (new imports: `java.net.InetAddress`, `java.net.ServerSocket`, `java.net.Socket`, `java.nio.charset.StandardCharsets`):

```java
    @Test
    void anInterruptedFetchIsUnreachableKeepsTheInterruptAndConnectsNowhere() throws IOException {
        server = new FakeCalendarServer();
        server.respond("/cal.ics", 200, "text/calendar", "BEGIN:VCALENDAR\nEND:VCALENDAR\n");
        URI url = server.url("/cal.ics");
        try (CalendarFetcher fetcher = new CalendarFetcher(properties, policy)) {
            Thread.currentThread().interrupt();

            assertThatThrownBy(() -> fetcher.fetch(url))
                    .isInstanceOf(CalendarFetchException.class)
                    .hasFieldOrPropertyWithValue("kind", CalendarFetchException.Kind.UNREACHABLE)
                    .hasMessage("Could not reach 127.0.0.1");
            assertThat(Thread.interrupted()).as("the interrupt is kept (and cleared here)").isTrue();
        }
        assertThat(server.count("/cal.ics")).isZero();
    }

    @Test
    void aBodyCutShortIsUnreachable() throws IOException {
        try (ServerSocket truncating = new ServerSocket(0, 1, InetAddress.ofLiteral("127.0.0.1"))) {
            Thread.ofVirtual().start(() -> {
                try (Socket accepted = truncating.accept()) {
                    accepted.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/calendar\r\n"
                            + "Content-Length: 100\r\n\r\nBEGIN:VCAL").getBytes(StandardCharsets.US_ASCII));
                    accepted.getOutputStream().flush();
                } catch (IOException _) {
                    // The test is over.
                }
            });
            URI url = URI.create("http://127.0.0.1:" + truncating.getLocalPort() + "/private/token-abc123/cal.ics");
            try (CalendarFetcher fetcher = new CalendarFetcher(properties, policy)) {
                assertThatThrownBy(() -> fetcher.fetch(url))
                        .isInstanceOf(CalendarFetchException.class)
                        .hasFieldOrPropertyWithValue("kind", CalendarFetchException.Kind.UNREACHABLE)
                        .hasMessage("Could not reach 127.0.0.1")
                        .hasCauseInstanceOf(IOException.class);
            }
        }
    }
```

In `aRedirectWithoutLocationIsABadResponse`, wrap the fetcher in try-with-resources:

```java
        try (CalendarFetcher fetcher = new CalendarFetcher(properties, policy)) {
            URI url = server.url("/moved.ics");

            assertThatThrownBy(() -> fetcher.fetch(url))
                    .isInstanceOf(CalendarFetchException.class)
                    .hasFieldOrPropertyWithValue("kind", CalendarFetchException.Kind.BAD_RESPONSE)
                    .hasMessage("127.0.0.1 answered HTTP 302");
        }
```

- [ ] **Step 7: Run the calendar and sports tests to verify they pass**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.sports.*' --tests 'dev.andre.homecontrol.web.SportsEndToEndTest'`
Expected: PASS, including the existing `timesOut`, `capsTheBody`, `followsValidatedRedirects`, `refusesRedirectsToBlockedAddresses` and `neverLeaksThePath`.

- [ ] **Step 8: Run the build and commit**

Run: `scripts/gradle.sh build`
Expected: BUILD SUCCESSFUL.

```bash
git add src/main/java/dev/andre/homecontrol/sources/sports/calendar/CalendarUrlPolicy.java \
        src/main/java/dev/andre/homecontrol/sources/sports/calendar/CalendarFetcher.java \
        src/test/java/dev/andre/homecontrol/sources/sports/calendar/CalendarUrlPolicyTest.java \
        src/test/java/dev/andre/homecontrol/sources/sports/calendar/CalendarFetcherTest.java \
        src/test/java/dev/andre/homecontrol/sources/sports/calendar/CalendarFetcherFailureTest.java
git commit -m "fix: connect to the calendar addresses the policy vetted, closing the DNS rebinding gap

The fetcher checked a calendar host's addresses and then let the JDK client
look the name up again, so a hostile host could pass the check and still be
connected to at a loopback or LAN address. It now uses the vetted-address
client, whose connection lookup is the policy's.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

Item 0.1 is done: follow superpowers:finishing-a-development-branch. The user decides on push and pull request.

---

## Item 0.4: One deadline covers the whole response body

Branch: `fix/http-body-deadline` from `fix/calendar-dns-pinning`, or from `main` once 0.1 is merged.

A probe on JDK 25.0.4 confirmed the defect. With `HttpRequest.timeout(1 s)` and a server sending one byte every 100 ms, `send` returned only after the full 10 seconds, both with `ofInputStream` and with `ofByteArray`: the request timeout ends when the headers arrive. HttpClient 5's response timeout bounds each read, not the whole body, so a server that trickles its answer can hold a `RailCache` fetch permit (`RailCache.java:243`, four by default) indefinitely. The probes also showed what works. For the JDK client: a body handler that completes its body with `HttpTimeoutException` at the deadline and then cancels the subscription (the other order lets the client fail with its own chunked-encoding error). For HttpClient 5: `request.cancel()` with hard cancellation enabled. Both close the connection.

### Task 6: `BoundedBody` and a trickling test server

**Files:**
- Create: `src/main/java/dev/andre/homecontrol/sources/http/BoundedBody.java`
- Create: `src/test/java/dev/andre/homecontrol/sources/http/TricklingServer.java`
- Test: `src/test/java/dev/andre/homecontrol/sources/http/BoundedBodyTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `dev.andre.homecontrol.sources.http.BoundedBody.handler(int maxBytes, Duration timeout)` returning `java.net.http.HttpResponse.BodyHandler<byte[]>`. The body holds at most `maxBytes + 1` bytes; `HttpClient.send` throws `java.net.http.HttpTimeoutException` when the whole exchange has not finished within `timeout` of the `handler` call.
  - Test class `dev.andre.homecontrol.sources.http.TricklingServer implements AutoCloseable`, with `TricklingServer()` (throws `IOException`), `URI url(String path)`, `int bytesSent()`, `int openStreams()` and `close()`.

- [ ] **Step 1: Write the trickling server**

Create `src/test/java/dev/andre/homecontrol/sources/http/TricklingServer.java`:

```java
package dev.andre.homecontrol.sources.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Answers every request with 200 and a body that never ends: one space every 50 ms until the client gives up or
 * the server is closed. Stands in for an upstream that trickles its answer to hold the caller.
 */
public final class TricklingServer implements AutoCloseable {

    private final HttpServer server;
    private final AtomicInteger bytesSent = new AtomicInteger();
    private final AtomicInteger openStreams = new AtomicInteger();
    private volatile boolean closed;

    public TricklingServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::trickle);
        server.start();
    }

    public URI url(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    /** Bytes written so far across all requests; more than one proves a client read past the headers. */
    public int bytesSent() {
        return bytesSent.get();
    }

    /** Responses still being written; zero once every client has closed its connection. */
    public int openStreams() {
        return openStreams.get();
    }

    // A peer that is slow on purpose is exactly what the deadline tests exercise.
    @SuppressWarnings("java:S2925")
    private void trickle(HttpExchange exchange) {
        openStreams.incrementAndGet();
        try (exchange) {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            OutputStream body = exchange.getResponseBody();
            while (!closed) {
                body.write(' ');
                body.flush();
                bytesSent.incrementAndGet();
                Thread.sleep(50);
            }
        } catch (IOException _) {
            // The client closed the connection: what a deadline is meant to do.
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        } finally {
            openStreams.decrementAndGet();
        }
    }

    @Override
    public void close() {
        closed = true;
        server.stop(0);
    }
}
```

- [ ] **Step 2: Write the failing test**

Create `src/test/java/dev/andre/homecontrol/sources/http/BoundedBodyTest.java`:

```java
package dev.andre.homecontrol.sources.http;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class BoundedBodyTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final HttpClient http = HttpClient.newHttpClient();
    private HttpServer server;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void respond(String path, int status, byte[] body) {
        server.createContext(path, exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
                exchange.getResponseBody().write(body);
            }
        });
    }

    private HttpResponse<byte[]> get(String path, int maxBytes) throws IOException, InterruptedException {
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
        return http.send(HttpRequest.newBuilder(uri).timeout(TIMEOUT).build(), BoundedBody.handler(maxBytes, TIMEOUT));
    }

    @Test
    void returnsABodyUpToTheCap() throws Exception {
        respond("/exact", 200, new byte[100]);

        assertThat(get("/exact", 100).body()).hasSize(100);
    }

    @Test
    void keepsOneByteMoreThanTheCapSoTheCallerCanTellItIsTooLarge() throws Exception {
        respond("/large", 200, new byte[5000]);

        assertThat(get("/large", 100).body()).hasSize(101);
    }

    @Test
    void anEmptyBodyIsEmpty() throws Exception {
        respond("/empty", 204, new byte[0]);

        HttpResponse<byte[]> response = get("/empty", 100);

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.body()).isEmpty();
    }

    @Test
    void aBodyThatNeverFinishesFailsAtTheDeadlineAndClosesTheConnection() throws Exception {
        try (TricklingServer trickling = new TricklingServer()) {
            HttpRequest request = HttpRequest.newBuilder(trickling.url("/feed")).timeout(Duration.ofSeconds(1)).build();

            assertThatThrownBy(() -> http.send(request, BoundedBody.handler(1_000_000, Duration.ofSeconds(1))))
                    .isInstanceOf(HttpTimeoutException.class);
            assertThat(trickling.bytesSent()).isGreaterThan(1);
            await().atMost(Duration.ofSeconds(5)).until(() -> trickling.openStreams() == 0);
        }
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.http.BoundedBodyTest'`
Expected: FAIL to compile, `cannot find symbol: variable BoundedBody`.

- [ ] **Step 4: Write `BoundedBody`**

Create `src/main/java/dev/andre/homecontrol/sources/http/BoundedBody.java`:

```java
package dev.andre.homecontrol.sources.http;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/**
 * A response body for {@link java.net.http.HttpClient}, bounded in size and in time. The JDK's request timeout
 * ends once the response headers arrive, so without this a server that trickles its body could hold the caller,
 * and the rail fetch permit it holds, for as long as it likes.
 */
public final class BoundedBody {

    private BoundedBody() {
    }

    /**
     * Keeps at most {@code maxBytes + 1} bytes, so a caller can tell an oversized body apart without buffering all
     * of it, and makes {@code send} throw {@link HttpTimeoutException} when the whole exchange has not finished
     * within {@code timeout} of this call. Create it right before {@code send}.
     */
    public static HttpResponse.BodyHandler<byte[]> handler(int maxBytes, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        return info -> new Subscriber(maxBytes + 1L, deadline);
    }

    private static final class Subscriber implements HttpResponse.BodySubscriber<byte[]> {

        private final long limit;
        private final long deadline;
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        Subscriber(long limit, long deadline) {
            this.limit = limit;
            this.deadline = deadline;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public synchronized void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            long remaining = Math.max(0, deadline - System.nanoTime());
            CompletableFuture.delayedExecutor(remaining, TimeUnit.NANOSECONDS).execute(this::expire);
            subscription.request(1);
        }

        @Override
        public synchronized void onNext(List<ByteBuffer> buffers) {
            if (body.isDone()) {
                return;
            }
            for (ByteBuffer buffer : buffers) {
                int take = (int) Math.min(buffer.remaining(), limit - bytes.size());
                byte[] chunk = new byte[take];
                buffer.get(chunk);
                bytes.write(chunk, 0, take);
            }
            if (bytes.size() >= limit) {
                finish();
                subscription.cancel();
            } else {
                subscription.request(1);
            }
        }

        @Override
        public synchronized void onError(Throwable error) {
            bytes = null;
            body.completeExceptionally(error);
        }

        @Override
        public synchronized void onComplete() {
            if (!body.isDone()) {
                finish();
            }
        }

        /** Completes first, then cancels: cancelling first lets the client fail the exchange with its own error. */
        private synchronized void expire() {
            if (body.isDone()) {
                return;
            }
            bytes = null;
            body.completeExceptionally(new HttpTimeoutException("request timed out"));
            subscription.cancel();
        }

        /** Hands the bytes over and drops the buffer: the pending expiry keeps this subscriber until the deadline. */
        private void finish() {
            byte[] result = bytes.toByteArray();
            bytes = null;
            body.complete(result);
        }
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.http.*'`
Expected: PASS (4 tests in `BoundedBodyTest`, 3 in `VettedHttpClientsTest`).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/sources/http/BoundedBody.java \
        src/test/java/dev/andre/homecontrol/sources/http/TricklingServer.java \
        src/test/java/dev/andre/homecontrol/sources/http/BoundedBodyTest.java
git commit -m "fix: bound JDK HTTP response bodies in time as well as size

The JDK request timeout stops at the response headers. The new body handler
keeps at most one byte over the cap and fails the exchange at its deadline,
closing the connection.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 7: Use `BoundedBody` in the four JDK-based source clients

TMDB, TheSportsDB, Jellyfin and YouTube each read the body from `BodyHandlers.ofInputStream()` after `send` returns, outside every timeout. Each switches to `BoundedBody.handler(cap, requestTimeout)`. The size checks stay as they are: the body now holds at most one byte over the cap, and the Content-Length checks still see the headers. Transport failures keep their existing mapping, because `send` still throws the JDK's own exception types.

**Files:**
- Test: `src/test/java/dev/andre/homecontrol/sources/http/SlowBodyDeadlineTest.java`
- Modify: `src/main/java/dev/andre/homecontrol/sources/tmdb/TmdbClient.java` (`get`, lines 44-66; imports)
- Modify: `src/main/java/dev/andre/homecontrol/sources/sports/thesportsdb/TheSportsDbClient.java` (`get`, lines 56-78; imports)
- Modify: `src/main/java/dev/andre/homecontrol/sources/jellyfin/JellyfinClient.java` (`image` lines 186-219, `send` lines 238-255, `exchange` lines 257-273; imports)
- Modify: `src/main/java/dev/andre/homecontrol/sources/youtube/YouTubeHttp.java` (`send`, lines 93-114; imports)

**Interfaces:**
- Consumes: `BoundedBody.handler(int, Duration)` and `TricklingServer` from Task 6.
- Produces: `SlowBodyDeadlineTest`, which Task 8 extends with a calendar test.

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/dev/andre/homecontrol/sources/http/SlowBodyDeadlineTest.java`:

```java
package dev.andre.homecontrol.sources.http;

import dev.andre.homecontrol.sources.jellyfin.JellyfinClient;
import dev.andre.homecontrol.sources.jellyfin.JellyfinConnection;
import dev.andre.homecontrol.sources.jellyfin.JellyfinException;
import dev.andre.homecontrol.sources.jellyfin.JellyfinProperties;
import dev.andre.homecontrol.sources.sports.SportsProperties;
import dev.andre.homecontrol.sources.sports.thesportsdb.TheSportsDbClient;
import dev.andre.homecontrol.sources.sports.thesportsdb.TheSportsDbException;
import dev.andre.homecontrol.sources.tmdb.TmdbClient;
import dev.andre.homecontrol.sources.tmdb.TmdbCredential;
import dev.andre.homecontrol.sources.tmdb.TmdbException;
import dev.andre.homecontrol.sources.tmdb.TmdbProperties;
import dev.andre.homecontrol.sources.youtube.YouTubeException;
import dev.andre.homecontrol.sources.youtube.YouTubeHttp;
import dev.andre.homecontrol.sources.youtube.YouTubeProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Every source's HTTP client gives up at its request timeout on a body that never finishes. */
@Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class SlowBodyDeadlineTest {

    private TricklingServer server;

    @BeforeEach
    void start() throws IOException {
        server = new TricklingServer();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void tmdb() {
        TmdbClient client = new TmdbClient(new TmdbProperties(true, server.url("/3"), null, 1, 1, 20, 40,
                Duration.ofHours(24), Duration.ofHours(24), null));
        TmdbCredential key = TmdbCredential.parse("0123456789abcdef0123456789abcdef");

        assertThatThrownBy(() -> client.get(key, "/trending/movie/week", Map.of()))
                .isInstanceOf(TmdbException.class)
                .hasFieldOrPropertyWithValue("kind", TmdbException.Kind.UNREACHABLE);
        assertThat(server.bytesSent()).isGreaterThan(1);
    }

    @Test
    void theSportsDb() {
        TheSportsDbClient client = new TheSportsDbClient(new SportsProperties.TheSportsDb(true,
                server.url("/api/v1/json"), "123", Duration.ofHours(24), 1, 1, null));

        assertThatThrownBy(() -> client.get("123", "eventsnextleague.php", Map.of("id", "4331")))
                .isInstanceOf(TheSportsDbException.class)
                .hasFieldOrPropertyWithValue("kind", TheSportsDbException.Kind.UNREACHABLE)
                .hasMessage("Could not reach TheSportsDB");
        assertThat(server.bytesSent()).isGreaterThan(1);
    }

    @Test
    void jellyfin() {
        JellyfinClient client = new JellyfinClient(new JellyfinProperties(true, 1, 1, 20));
        JellyfinConnection connection = new JellyfinConnection(server.url(""), "tok", "dev", "user");
        URI serverUrl = server.url("");

        assertThatThrownBy(() -> client.get(connection, "/Items", Map.of()))
                .isInstanceOf(JellyfinException.class)
                .hasFieldOrPropertyWithValue("kind", JellyfinException.Kind.UNREACHABLE)
                .hasMessageContaining("(no answer in time)");
        assertThatThrownBy(() -> client.image(serverUrl, "abc", "Primary", null, 480))
                .isInstanceOf(JellyfinException.class)
                .hasFieldOrPropertyWithValue("kind", JellyfinException.Kind.UNREACHABLE);
        assertThat(server.bytesSent()).isGreaterThan(1);
    }

    @Test
    void youtube() {
        URI base = server.url("");
        YouTubeHttp http = new YouTubeHttp(new YouTubeProperties(true, base, base, base, base, 1, 1, 10000, 20, 30,
                30, 5, Duration.ofHours(24), 20, Duration.ofMinutes(60), Duration.ofMinutes(15), Duration.ofHours(6)));
        URI subscriptions = server.url("/youtube/v3/subscriptions");

        assertThatThrownBy(() -> http.get(subscriptions, Map.of()))
                .isInstanceOf(YouTubeException.class)
                .hasFieldOrPropertyWithValue("kind", YouTubeException.Kind.UNREACHABLE)
                .hasMessage("Could not reach 127.0.0.1");
        assertThat(server.bytesSent()).isGreaterThan(1);
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.http.SlowBodyDeadlineTest'`
Expected: FAIL, all four with `TimeoutException: … timed out after 10 seconds`. Each client waits on the trickling body forever.

- [ ] **Step 3: Bound the TMDB body**

In `TmdbClient.get`, replace the block from `HttpResponse<InputStream> response;` through the end of its `try`/`catch` with:

```java
        HttpResponse<byte[]> response;
        try {
            response = http.send(request.build(),
                    BoundedBody.handler(MAX_BODY_BYTES, Duration.ofSeconds(properties.requestTimeoutSeconds())));
        } catch (IOException e) {
            throw new TmdbException(TmdbException.Kind.UNREACHABLE, unreachable(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TmdbException(TmdbException.Kind.UNREACHABLE, unreachable(), e);
        }
        byte[] body = response.body();
```

Add `import dev.andre.homecontrol.sources.http.BoundedBody;` and delete `import java.io.InputStream;`.

- [ ] **Step 4: Bound the TheSportsDB body**

In `TheSportsDbClient.get`, replace the block from `HttpResponse<InputStream> response;` through the end of its `try`/`catch` with:

```java
        HttpResponse<byte[]> response;
        try {
            response = http.send(request,
                    BoundedBody.handler(MAX_BODY_BYTES, Duration.ofSeconds(properties.requestTimeoutSeconds())));
        } catch (IOException _) {
            // No cause attached: the request URI (which the JDK's IOException/timeout messages can
            // quote in full, e.g. via a wrapped ConnectException) embeds the API key in its path.
            throw new TheSportsDbException(TheSportsDbException.Kind.UNREACHABLE, "Could not reach TheSportsDB");
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            throw new TheSportsDbException(TheSportsDbException.Kind.UNREACHABLE, "Could not reach TheSportsDB");
        }
        byte[] body = response.body();
```

Add `import dev.andre.homecontrol.sources.http.BoundedBody;` and delete `import java.io.InputStream;`.

- [ ] **Step 5: Bound the Jellyfin bodies**

In `JellyfinClient`, replace `exchange` with a version that takes the cap:

```java
    /** Sends the request and reads a body of at most {@code maxBytes + 1} bytes; a transport failure becomes an UNREACHABLE naming what went wrong. */
    private HttpResponse<byte[]> exchange(URI serverUrl, HttpRequest request, int maxBytes) {
        try {
            return http.send(request,
                    BoundedBody.handler(maxBytes, Duration.ofSeconds(properties.requestTimeoutSeconds())));
        } catch (HttpConnectTimeoutException _) {
            throw unreachable(serverUrl, "connection timed out");
        } catch (HttpTimeoutException _) {
            throw unreachable(serverUrl, "no answer in time");
        } catch (ConnectException e) {
            throw unreachable(serverUrl, e.getCause() instanceof UnresolvedAddressException ? "unknown host" : "connection refused");
        } catch (IOException e) {
            throw unreachable(serverUrl, e.getClass().getSimpleName());
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            throw unreachable(serverUrl, "interrupted");
        }
    }
```

Replace `send` with:

```java
    private JsonNode send(URI serverUrl, HttpRequest.Builder builder) {
        HttpResponse<byte[]> response = exchange(serverUrl, builder.build(), MAX_JSON_BYTES);
        requireSuccess(serverUrl, response.statusCode());
        // A cap, not a limit we expect to hit: a well-behaved Jellyfin answer never comes close, and a
        // misbehaving or hostile server can't make us buffer an unbounded amount of it into heap.
        byte[] bytes = response.body();
        if (contentLengthExceeds(response, MAX_JSON_BYTES) || bytes.length > MAX_JSON_BYTES) {
            throw new JellyfinException(JellyfinException.Kind.BAD_RESPONSE, "Jellyfin at " + serverUrl + " sent an oversized response");
        }
        return bytes.length == 0 ? MissingNode.getInstance() : parse(serverUrl, bytes);
    }
```

In `image`, replace everything from `HttpResponse<InputStream> response = exchange(serverUrl, request);` to the end of the method with:

```java
        HttpResponse<byte[]> response = exchange(serverUrl, request, MAX_IMAGE_BYTES);
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        String contentType = response.headers().firstValue(CONTENT_TYPE).orElse("");
        String bareType = contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        if (response.statusCode() != 200 || !ALLOWED_IMAGE_TYPES.contains(bareType)
                || contentLengthExceeds(response, MAX_IMAGE_BYTES)) {
            throw new JellyfinException(JellyfinException.Kind.BAD_RESPONSE, "Jellyfin at " + serverUrl + " sent no image");
        }
        byte[] bytes = response.body();
        if (bytes.length > MAX_IMAGE_BYTES) {
            throw new JellyfinException(JellyfinException.Kind.BAD_RESPONSE, "Jellyfin at " + serverUrl + " sent an oversized image");
        }
        return Optional.of(new Image(contentType, bytes));
    }
```

Add `import dev.andre.homecontrol.sources.http.BoundedBody;` and delete `import java.io.InputStream;`. `IOException` stays imported, because `exchange` still catches it.

- [ ] **Step 6: Bound the YouTube body**

Replace `YouTubeHttp.send` with:

```java
    private Response send(URI uri, HttpRequest.Builder builder) {
        HttpResponse<byte[]> response;
        try {
            response = http.send(builder.build(), BoundedBody.handler(MAX_RESPONSE_BYTES, requestTimeout));
        } catch (IOException _) {
            throw new YouTubeException(YouTubeException.Kind.UNREACHABLE, "Could not reach " + uri.getHost());
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            throw new YouTubeException(YouTubeException.Kind.UNREACHABLE, "Interrupted while calling " + uri.getHost());
        }
        byte[] bytes = response.body();
        if (contentLengthExceeds(response, MAX_RESPONSE_BYTES) || bytes.length > MAX_RESPONSE_BYTES) {
            throw new YouTubeException(YouTubeException.Kind.BAD_RESPONSE, "Google sent an oversized response");
        }
        return new Response(response.statusCode(), response.headers().firstValue("Content-Type").orElse(""), bytes);
    }
```

Add `import dev.andre.homecontrol.sources.http.BoundedBody;` and delete `import java.io.InputStream;`.

- [ ] **Step 7: Run the tests to verify they pass**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.*' --tests 'dev.andre.homecontrol.web.*EndToEndTest'`
Expected: PASS: the four `SlowBodyDeadlineTest` tests, and every existing client test, including `JellyfinClientFailuresTest.aServerThatNeverAnswersIsNamedAsSuch` ("(no answer in time)") and `aServerThatHangsUpIsNamedByTheFailure`.

- [ ] **Step 8: Commit**

```bash
git add src/test/java/dev/andre/homecontrol/sources/http/SlowBodyDeadlineTest.java \
        src/main/java/dev/andre/homecontrol/sources/tmdb/TmdbClient.java \
        src/main/java/dev/andre/homecontrol/sources/sports/thesportsdb/TheSportsDbClient.java \
        src/main/java/dev/andre/homecontrol/sources/jellyfin/JellyfinClient.java \
        src/main/java/dev/andre/homecontrol/sources/youtube/YouTubeHttp.java
git commit -m "fix: stop TMDB, TheSportsDB, Jellyfin and YouTube waiting forever on a trickling body

Each client read its body after send returned, outside every timeout, so a
server sending one byte at a time held a rail fetch permit indefinitely.
The request timeout now covers the body.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 8: One deadline per calendar request

HttpClient 5's response timeout bounds each read, so a calendar server that sends a byte every few seconds never trips it. Cancelling the request at its deadline, with hard cancellation (already enabled in Task 5), closes the connection mid-read.

**Files:**
- Modify: `src/main/java/dev/andre/homecontrol/sources/sports/calendar/CalendarFetcher.java` (`request`; imports)
- Test: `src/test/java/dev/andre/homecontrol/sources/http/SlowBodyDeadlineTest.java`

**Interfaces:**
- Consumes: `TricklingServer` (Task 6), `SlowBodyDeadlineTest` (Task 7), `CalendarFetcher.request` and `CalendarFetcher implements AutoCloseable` (Task 5).
- Produces: nothing other tasks use.

- [ ] **Step 1: Write the failing test**

Add to `SlowBodyDeadlineTest` (new imports: `dev.andre.homecontrol.sources.sports.calendar.CalendarFetchException`, `dev.andre.homecontrol.sources.sports.calendar.CalendarFetcher`, `dev.andre.homecontrol.sources.sports.calendar.CalendarUrlPolicy`):

```java
    @Test
    void calendars() {
        URI calendar = server.url("/cal.ics");
        try (CalendarFetcher fetcher = new CalendarFetcher(
                new SportsProperties.Calendar(Duration.ofHours(6), 1, 1, 5 * 1024 * 1024, 3, true),
                new CalendarUrlPolicy(true))) {
            assertThatThrownBy(() -> fetcher.fetch(calendar))
                    .isInstanceOf(CalendarFetchException.class)
                    .hasFieldOrPropertyWithValue("kind", CalendarFetchException.Kind.UNREACHABLE)
                    .hasMessage("Could not reach 127.0.0.1");
        }
        assertThat(server.bytesSent()).isGreaterThan(1);
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.http.SlowBodyDeadlineTest.calendars'`
Expected: FAIL with `TimeoutException: … timed out after 10 seconds`. A byte every 50 ms never trips the 1-second read timeout.

- [ ] **Step 3: Cancel the request at its deadline**

In `CalendarFetcher.request`, after the two `request.setHeader(...)` lines and before `CloseableHttpResponse response = null;`, add:

```java
        // The response timeout bounds each read, not the whole body: a server that trickles its calendar would
        // hold the refresh. Cancelling at the deadline closes the connection mid-read.
        CompletableFuture.delayedExecutor(properties.requestTimeoutSeconds(), TimeUnit.SECONDS).execute(request::cancel);
```

Add the imports `java.util.concurrent.CompletableFuture` and `java.util.concurrent.TimeUnit`. Extend the class comment with one more sentence: "One deadline per request covers the headers and the whole body."

- [ ] **Step 4: Run the tests to verify they pass**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.http.*' --tests 'dev.andre.homecontrol.sources.sports.*'`
Expected: PASS: all five `SlowBodyDeadlineTest` tests and every calendar test, including `CalendarFetcherTest.timesOut`.

- [ ] **Step 5: Run the build and commit**

Run: `scripts/gradle.sh build`
Expected: BUILD SUCCESSFUL.

```bash
git add src/main/java/dev/andre/homecontrol/sources/sports/calendar/CalendarFetcher.java \
        src/test/java/dev/andre/homecontrol/sources/http/SlowBodyDeadlineTest.java
git commit -m "fix: give each calendar request one deadline that covers its body

HttpClient 5's response timeout bounds each read, so a calendar sent a byte
at a time never timed out. The request is now cancelled at its deadline.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

Item 0.4 is done: follow superpowers:finishing-a-development-branch. The user decides on push and pull request.
