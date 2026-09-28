# Phase 1.3c Test Isolation Under Parallel Forks Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The unit tests run safely and stably in four parallel JVMs. No two test JVMs share a file, and the webOS tests that failed under load no longer depend on a race in the JDK's WebSocket client.

**Architecture:**
- **Per-fork configuration.** The test configuration gives each test JVM its own data directory and Bluetooth runtime directory. It uses the system property `org.gradle.test.worker`, which Gradle sets in every test JVM; outside Gradle the value falls back to `0`.
- **No fixed paths.** The one test that still writes to a fixed path gets a temporary directory, like every other Spring test.
- **webOS connection drops.** The tests detect a dropped TV connection the way production does: with the session's liveness check. The one connection-level test drops the connection with a TCP reset, which the JDK always reports.
- **Measured.** Three full runs in a row with four JVMs, then the `test` task's wall time locally and CI's "Build and test" job time.

**Tech Stack:** Java 25, Spring Boot 4.1.1, JUnit 6 (Jupiter) with AssertJ and Awaitility, Gradle through `scripts/gradle.sh`.

**Spec:** `docs/superpowers/specs/2026-09-27-phase-1-guardrails-design.md`, section "1.3c Test isolation under parallel forks (S, now independent of 1.3a)", and "Delivery and sequencing" and "Progress measures".

## Global Constraints

- **Start condition:** #132 (Phase 1.3b) is merged. Branch `test/fork-isolation` from `origin/main` (`a0a09d0`).
- **Production code does not change.** `git diff origin/main -- src/main` stays empty. The spec allows production edits in Phase 1 only for 1.3b's timing seams and 1.3d's reset methods. A temporary edit to production code that makes a test fail on demand is reverted before the commit.
- **Test count:** equal or higher. This plan adds one test: 2,748 becomes 2,749.
- **Build and test only through `scripts/gradle.sh`.** There is no local JDK, and `scripts/gradle.sh build` is green at the end.
- **Test configuration:** `src/test/resources/config/application.yaml` holds only what a test run must change, and every key carries a comment saying why (`docs/dev/testing.md`, "Test configuration").
- **Waiting:** tests wait with Awaitility. A temporary `Thread.sleep` used to make a race fail on demand is never committed.
- **Commits:**
  - Conventional Commits, ending with a `Co-Authored-By:` trailer naming the model that wrote them.
  - Stage only the files you changed: `git add <paths>`, never `git add -A`.

## What the three runs found

Before this plan was written, the whole suite ran three times in a row with four JVMs on `a0a09d0`, and every test resource that two JVMs could share was inventoried:

- **Runs:** 2,748 tests each time. Runs 1 and 2 were green. Run 3 failed one test, `WebOsSessionTest.listsAndSwitchesInputs`, which waited 5 s for DISCONNECTED after the fake TV dropped the connection. No run failed on a shared file, port or UDP bind.
- **Summed class time:** 647, 660 and 642 s with four JVMs. CI's "Build and test" job on `main` at `a0a09d0` took 3 min 31 s.
- **The shared data directory `build/test-data`** is used only by `HomeControlApplicationTest`:
  - Every other `@SpringBootTest` class sets its own temporary `shield.data-dir`.
  - `SmartTvModulesOffTest` sets the fixed `build/tmp/tv-modules-off-test`.
  - The 28 `@WebMvcTest` classes and the bare `ApplicationContextRunner` tests do not bind the data directory.
  - `ApplicationYamlTest.theTestOverridesWin` asserts the value `build/test-data`.
- **Bluetooth's runtime directory** defaults to `<java.io.tmpdir>/home-control-bluetooth` and holds mpv's IPC socket (`MpvPlayer.socketFor(runtimeDir, deviceId)`). No test writes there today: the tests that play use `@TempDir`. It is shared in principle, though, so the spec makes it per fork.
- **`AndroidTvSessionTest`'s fixed `./build/test-data`**, named in the spec, is already gone. No test under `src/test` names a path under `build/` except `ApplicationYamlTest`'s assertion.
- **The webOS failure has a known cause, reproduced on demand.** See plan decision 3.

## Plan decisions

1. **The data directory per fork: `build/test-data/${org.gradle.test.worker:0}`,** as the spec writes it. The browser tests (`src/e2e`) have the test resources on their classpath and resolve the same value. They never name `build/test-data`, and `e2eTest` runs in one JVM.
2. **The Bluetooth runtime directory per fork stays under `java.io.tmpdir`:** `${java.io.tmpdir}/home-control-bluetooth-${org.gradle.test.worker:0}`. It holds a Unix socket, whose path is limited to about 108 bytes, and a path under the project's `build/` directory would come close to that limit in a deep checkout. `BluetoothModuleSwitchTest` asserts the production default through a bare `ApplicationContextRunner`, which does not read the test configuration, so it stays unchanged.
3. **The webOS drop failures come from the JDK, not from our code.**
   - **What goes wrong.** When the peer closes a connection with a FIN while the JDK's `java.net.http` WebSocket listener is still inside `onText` for the previous frame, the JDK loses the close. Neither `onClose` nor `onError` runs, and the socket stays in CLOSE_WAIT. The JDK calls `acknowledgeReception()` with zero demand, which throws an `InternalError` that it swallows (jdk25u `TransportImpl` and `WebSocketImpl`).
   - **Why the test hits it.** `FakeWebSocketServer.refuseConnections(true)` and `dropAll()` close with a FIN right after the fake's last answer. The test thread is released from inside that answer's `onText` (`SsapConnection`'s waiter completes there). So under load the FIN lands in the window, and the session stays CONNECTED.
   - **How it was proven.**
     - The flake reproduced 6 and 12 times in 400 repetitions under CPU load.
     - The JDK's debug log showed "got read EOF … signal read error: java.lang.InternalError" while `onText` was still running.
     - A standalone harness lost a FIN during `onText` 20 of 20 times, and reported a FIN after `onText`, or a reset during it, 20 of 20 times.
     - A 300 ms sleep at the end of `SsapConnection.dispatch` makes `listsAndSwitchesInputs` fail 10 of 10 times.
   - **The same cause is behind:**
     - `WebOsSessionTest.reconnectsAfterTheTvDropsTheConnection` and `powerWhileOffWakesTheTvAndReconnects`;
     - `WebOsEndToEndTest.discoversPairsControlsWakesAndTestsALgTv`, whose step 8 drops the connection. This is very probably the failure the spec recorded on 2026-09-27;
     - `SsapConnectionTest.theTvDroppingTheConnectionIsReportedOnce`.
   - **The fix is in the tests only.**
     - The session tests that drop the connection run with a 200 ms liveness check, so the session notices a lost close the way it would with a real TV.
     - The end-to-end test gets a 1 s liveness interval through its own `@DynamicPropertySource`.
     - `SsapConnectionTest`, which tests the connection without a session, drops with a TCP reset (RST), which the JDK reports even during `onText`.
   - **A new test pins the path that the liveness check could hide:** a reset connection must be noticed within 5 s with the liveness check at 30 s (Task 3). Without it, a session that ignored the connection's close report would pass every drop test.
4. **`WebOsSessionTest.powerWhileOnTurnsTheTvOff` has a second, unrelated race.**
   - The first power-state answer ("Active") can arrive after the test's `turnOff` and switch power back on.
   - MAC learning comes after that answer, so the test waits for the stored MAC address before pressing POWER.
   - This was proven 5 of 5 failing with the answer delayed, and 5 of 5 passing with the wait (Task 4).
5. **Probe-then-release ports stay.** `FakeWebSocketServer.closedPort()`, `AndroidTvSessionTest.closedPort()` and the inline probes in `CastSessionTest` and `CastConnectionTest` bind port 0, close the socket and use the port as "nothing listens here". Another JVM could take that port in the meantime. None of them failed in the three runs, and the spec makes per fork only what fails. They are listed under Review Focus.
6. **Out of scope:**
   - the 26 `Files.createTempDirectory` calls that never delete their directory, which accumulate files but do not collide;
   - the ArchUnit freeze store, which only `ArchitectureTest` writes, in one JVM.
7. **The production side of decision 3 is not part of this plan.** A real webOS TV that sends a frame and then closes can go unnoticed until the liveness check: up to about 40 s with the defaults (30 s interval and a 10 s request timeout). A command in that time waits for its timeout and fails as a gateway error. Tizen's remote connection uses the same client and has no liveness check. This needs its own decision, for example a JDK bug report, shorter liveness defaults, or a different WebSocket client with an ADR, and is handed to the user as a follow-up.

## Review Focus

- **The placeholder must resolve.** If Spring left `${org.gradle.test.worker:0}` unresolved, the data directory would be a folder literally named `${...}` and shared again. Test: `ApplicationYamlTest.theTestOverridesWin` asserts the resolved value, `build/test-data/<worker>` (Task 1).
- **The Bluetooth socket directory must stay short and per fork.** Test: `ApplicationYamlTest.theTestOverridesWin` asserts `<java.io.tmpdir>/home-control-bluetooth-<worker>` (Task 1).
- **A dropped webOS connection must still be noticed without the liveness check when the JDK reports it.** The drop tests now run with a 200 ms liveness check, which would also hide a session that ignores the connection's close report. Test: `WebOsSessionTest.aResetConnectionIsNoticedWithoutTheLivenessCheck`, with liveness at 30 s, shown failing when `WebOsSession.lost` returns early (Task 3).
- **Production must not change.** Every fix is in the tests, and the end-to-end test's liveness setting comes from its own `@DynamicPropertySource`. Test: `git diff origin/main -- src/main` is empty (Task 5).
- **Probe-then-release ports (decision 5) remain a small race.** Test: the three runs in Task 5 are green; a port failure in them gets its own fix before the pull request.

---

### Task 1: Per-fork test configuration

**Files:**
- Modify: `src/test/resources/config/application.yaml`
- Test: `src/test/java/dev/andre/homecontrol/ApplicationYamlTest.java` (`theTestOverridesWin`)

**Interfaces:**
- Consumes: nothing.
- Produces: `shield.data-dir` = `build/test-data/<worker>` and `home-control.bluetooth.runtime-dir` = `<java.io.tmpdir>/home-control-bluetooth-<worker>` in every Spring test that reads the test configuration.

- [ ] **Step 1: Write the failing assertions**

In `ApplicationYamlTest.theTestOverridesWin`, replace

```java
            assertThat(environment.getProperty("shield.data-dir")).isEqualTo("build/test-data");
```

with

```java
            // Gradle sets org.gradle.test.worker in every test JVM; outside Gradle the configuration falls back to 0.
            String fork = System.getProperty("org.gradle.test.worker", "0");
            assertThat(environment.getProperty("shield.data-dir")).isEqualTo("build/test-data/" + fork);
            assertThat(environment.getProperty("home-control.bluetooth.runtime-dir"))
                    .isEqualTo(System.getProperty("java.io.tmpdir") + "/home-control-bluetooth-" + fork);
```

- [ ] **Step 2: Run it to verify it fails**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.ApplicationYamlTest' --rerun`

Expected: FAIL in `theTestOverridesWin`: `expected: "build/test-data/<n>" but was: "build/test-data"`.

- [ ] **Step 3: The per-fork values**

In `src/test/resources/config/application.yaml`:

1. The header comment's list of what a test run must not do gains one item. Replace

```yaml
# what a test run must not do: discover the LAN, reach real upstream APIs, wait out production timeouts, or
# refresh rails on a schedule.
```

with

```yaml
# what a test run must not do: discover the LAN, reach real upstream APIs, wait out production timeouts, refresh
# rails on a schedule, or share files with another test JVM.
```

2. Replace

```yaml
shield:
  data-dir: build/test-data
```

with

```yaml
shield:
  # One directory per test JVM: Gradle sets org.gradle.test.worker in each, and outside Gradle it is 0.
  data-dir: build/test-data/${org.gradle.test.worker:0}
```

3. Directly under `home-control:`, before `ssdp:`, add

```yaml
  bluetooth:
    # mpv's socket directory, one per test JVM. It stays under java.io.tmpdir: a Unix socket path must be short.
    runtime-dir: ${java.io.tmpdir}/home-control-bluetooth-${org.gradle.test.worker:0}
```

- [ ] **Step 4: Run the tests that read the configuration**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.ApplicationYamlTest' --tests 'dev.andre.homecontrol.HomeControlApplicationTest' --tests 'dev.andre.homecontrol.adapters.bluetooth.*' --tests 'dev.andre.homecontrol.web.Bluetooth*' --rerun`

Expected: PASS. Then check that the data directory resolved: `ls build/test-data/` (if it exists; older runs may have left files there) shows no directory named `${org.gradle.test.worker:0}`.

- [ ] **Step 5: Commit**

```bash
git add src/test/resources/config/application.yaml src/test/java/dev/andre/homecontrol/ApplicationYamlTest.java
git commit -m "test: give each test JVM its own data and Bluetooth runtime directory"
```

End the message with your `Co-Authored-By:` trailer.

### Task 2: `SmartTvModulesOffTest` uses a temporary data directory

**Files:**
- Modify: `src/test/java/dev/andre/homecontrol/web/SmartTvModulesOffTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: nothing.

This is a change to test setup, not to behaviour, so there is no failing test to write first. The existing test must stay green, and the fixed path must disappear.

- [ ] **Step 1: Replace the fixed path**

In `SmartTvModulesOffTest.java`:

1. Remove `"shield.data-dir=build/tmp/tv-modules-off-test"` from `@SpringBootTest(properties = …)`, so it reads

```java
@SpringBootTest(properties = {"home-control.webos.enabled=false", "home-control.tizen.enabled=false"})
```

2. Add, before the `@Autowired ApplicationContext context;` field, the pattern `CastDisabledSmokeTest` uses:

```java
    @DynamicPropertySource
    static void isolatedDataDirectory(DynamicPropertyRegistry registry) throws IOException {
        String dataDir = Files.createTempDirectory("tv-modules-off-test").toString();
        registry.add("shield.data-dir", () -> dataDir);
    }
```

3. Add the imports `org.springframework.test.context.DynamicPropertyRegistry`, `org.springframework.test.context.DynamicPropertySource`, `java.io.IOException` and `java.nio.file.Files`, in the file's import order: the `org.springframework` imports with the others, then a blank line and the `java.` imports.

- [ ] **Step 2: Run it**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.web.SmartTvModulesOffTest' --rerun`

Expected: PASS, 1 test.

- [ ] **Step 3: Check that no test names a fixed path under `build/`**

Run: `git grep -n 'build/tmp\|"build/\|\./build' -- src/test/java`

Expected: only `ApplicationYamlTest`'s `"build/test-data/" + fork`.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/dev/andre/homecontrol/web/SmartTvModulesOffTest.java
git commit -m "test: give SmartTvModulesOffTest a temporary data directory"
```

End the message with your `Co-Authored-By:` trailer.

### Task 3: webOS drop tests no longer depend on the JDK noticing a FIN

**Files:**
- Modify: `src/test/java/dev/andre/homecontrol/adapters/net/FakeWebSocketServer.java` (`Connection.reset()`, `resetAll()`)
- Modify: `src/test/java/dev/andre/homecontrol/adapters/webos/FakeSsapServer.java` (`resetConnections()`)
- Modify: `src/test/java/dev/andre/homecontrol/adapters/webos/WebOsSessionTest.java`
- Modify: `src/test/java/dev/andre/homecontrol/adapters/webos/SsapConnectionTest.java` (`theTvDroppingTheConnectionIsReportedOnce`)
- Modify: `src/test/java/dev/andre/homecontrol/web/WebOsEndToEndTest.java` (`@DynamicPropertySource`)
- Temporarily, never committed: `src/main/java/dev/andre/homecontrol/adapters/webos/SsapConnection.java` and `WebOsSession.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `FakeWebSocketServer.resetAll()`, `FakeWebSocketServer.Connection.reset()` and `FakeSsapServer.resetConnections()`, which close with a TCP reset (RST) instead of a FIN.

Read plan decision 3 first.

- [ ] **Step 1: Write the test for the close-report path**

In `WebOsSessionTest`, after `reconnectsAfterTheTvDropsTheConnection`, add:

```java
    @Test
    void aResetConnectionIsNoticedWithoutTheLivenessCheck() throws Exception {
        started();
        connected();
        // MAC learning is the last setup request: after it, the TV resets an established, idle session.
        await().atMost(Duration.ofSeconds(5)).until(() -> storedSetting("macAddress") != null);
        int initialConnections = tv.connections();

        tv.resetConnections();

        // TIMINGS checks liveness every 30 s, so noticing the reset in time takes the connection's own close report.
        states.awaitStatus(DeviceStatus.DISCONNECTED, Duration.ofSeconds(5));
        await().atMost(Duration.ofSeconds(5)).until(() -> tv.connections() > initialConnections);
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.adapters.webos.WebOsSessionTest' --rerun`

Expected: FAIL at `compileTestJava`: `cannot find symbol: method resetConnections()`.

- [ ] **Step 3: The resetting fakes**

In `FakeWebSocketServer.Connection`, after `close()`, add:

```java
        /** Closes the socket with a reset (RST) instead of an orderly FIN. */
        public void reset() {
            try {
                socket.setSoLinger(true, 0);
            } catch (IOException _) {
                // Already gone; closing anyway.
            }
            close();
        }
```

In `FakeWebSocketServer`, after `dropAll()`, add:

```java
    /**
     * As {@link #dropAll()}, but with a reset (RST) instead of a FIN. The JDK WebSocket reports a reset even while its
     * listener still handles a frame; an end of stream that arrives then, it loses.
     */
    public void resetAll() {
        open.forEach(Connection::reset);
        open.clear();
    }
```

In `FakeSsapServer`, after `dropConnections()`, add:

```java
    /** As {@link #dropConnections()}, but with a reset (RST): the client notices it even while handling a frame. */
    public void resetConnections() {
        server.resetAll();
    }
```

- [ ] **Step 4: Run the new test, then show that it catches an ignored close report**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.adapters.webos.WebOsSessionTest.aResetConnectionIsNoticedWithoutTheLivenessCheck' --rerun`

Expected: PASS.

Then, temporarily, add `if (reason != null) { return; }` as the first line of `WebOsSession.lost(SsapConnection which, String reason)` (a bare `return;` would not compile), and run the same command.

Expected: FAIL: `ConditionTimeoutException` after 5 s. Restore `WebOsSession.java`, and check that `git diff src/main` is empty.

- [ ] **Step 5: Make the lost FIN happen on demand**

Temporarily, at the end of `SsapConnection.dispatch` (after the subscriber call), add:

```java
        try {
            Thread.sleep(300); // TEMPORARY: hold the frame in onText, as a busy host does
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
```

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.adapters.webos.WebOsSessionTest.listsAndSwitchesInputs' --tests 'dev.andre.homecontrol.adapters.webos.WebOsSessionTest.reconnectsAfterTheTvDropsTheConnection' --tests 'dev.andre.homecontrol.adapters.webos.WebOsSessionTest.powerWhileOffWakesTheTvAndReconnects' --tests 'dev.andre.homecontrol.adapters.webos.SsapConnectionTest.theTvDroppingTheConnectionIsReportedOnce' --tests 'dev.andre.homecontrol.web.WebOsEndToEndTest' --rerun`

Expected: FAIL. At least `listsAndSwitchesInputs` fails, with `ConditionTimeoutException` at its `awaitStatus(DeviceStatus.DISCONNECTED)`. Record in your report which of the five fail. Keep the sleep in place for Step 7.

- [ ] **Step 6: The tests detect drops as production does**

In `WebOsSessionTest`:

1. The `LIVENESS` Javadoc becomes `/** As TIMINGS, but a liveness check every 200 ms, for the liveness tests and startedWithLivenessCheck(). */`.
2. The two-argument `session(...)` delegates to a new three-argument one that takes the timings. Replace

```java
    private WebOsSession session(Map<String, String> settings, Consumer<DeviceState> listener) throws IOException {
        Device device = new Device("lg", "LG TV", DeviceKind.WEBOS, "127.0.0.1", Map.of("webos", settings), Instant.now());
```

with

```java
    private WebOsSession session(Map<String, String> settings, Consumer<DeviceState> listener) throws IOException {
        return session(settings, listener, TIMINGS);
    }

    private WebOsSession session(Map<String, String> settings, Consumer<DeviceState> listener, WebOsTimings timings)
            throws IOException {
        Device device = new Device("lg", "LG TV", DeviceKind.WEBOS, "127.0.0.1", Map.of("webos", settings), Instant.now());
```

   In that method's `new WebOsSession(device, properties, TIMINGS, …)`, `TIMINGS` becomes `timings`.
3. After `started()`, add:

```java
    /**
     * For tests in which the TV drops the connection: the fake drops it right after a frame, and when that end of
     * stream arrives while the JDK WebSocket still hands the frame to the listener, the JDK loses it (neither onClose
     * nor onError runs). Then only the liveness check notices the dead connection, as it would with a real TV.
     */
    private WebOsSession startedWithLivenessCheck() throws IOException {
        WebOsSession started = session(Map.of("clientKey", FakeSsapServer.CLIENT_KEY), states, LIVENESS);
        started.start();
        return started;
    }
```

4. In `listsAndSwitchesInputs`, `reconnectsAfterTheTvDropsTheConnection` and `powerWhileOffWakesTheTvAndReconnects`, the first line `started();` becomes `startedWithLivenessCheck();`.

In `SsapConnectionTest.theTvDroppingTheConnectionIsReportedOnce`, replace `server.dropConnections();` with:

```java
        // A reset, not a FIN: register() returns while the JDK WebSocket may still hand the registration to the
        // listener, and the JDK loses an end of stream that arrives then (see
        // WebOsSessionTest.startedWithLivenessCheck).
        server.resetConnections();
```

In `WebOsEndToEndTest`'s `@DynamicPropertySource`, after the `home-control.webos.wake-grace-seconds` line, add:

```java
        // Step 8 drops the connection right after step 7's frames. When the JDK WebSocket loses that end of stream
        // (see WebOsSessionTest.startedWithLivenessCheck), the liveness check notices within three seconds.
        registry.add("home-control.webos.liveness-interval-seconds", () -> "1");
        registry.add("home-control.webos.request-timeout-seconds", () -> "2");
```

- [ ] **Step 7: Run the five tests with the sleep still in place**

Run the Step 5 command again.

Expected: PASS: the tests now pass even with the lost FIN forced.

- [ ] **Step 8: Remove the sleep and run the packages**

Remove the temporary sleep from `SsapConnection.dispatch`, and check that `git diff src/main` is empty.

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.adapters.webos.*' --tests 'dev.andre.homecontrol.adapters.net.*' --tests 'dev.andre.homecontrol.web.WebOsEndToEndTest' --rerun`

Expected: PASS. The test count of these classes is one higher than before the task.

- [ ] **Step 9: Commit**

```bash
git add src/test/java/dev/andre/homecontrol/adapters/net/FakeWebSocketServer.java src/test/java/dev/andre/homecontrol/adapters/webos/FakeSsapServer.java src/test/java/dev/andre/homecontrol/adapters/webos/WebOsSessionTest.java src/test/java/dev/andre/homecontrol/adapters/webos/SsapConnectionTest.java src/test/java/dev/andre/homecontrol/web/WebOsEndToEndTest.java
git commit -m "test: detect dropped webOS connections as production does

The JDK WebSocket client loses an orderly close (FIN) that arrives while
its listener still handles a frame, and the fake TV drops connections
right after answering. The drop tests now let the session's liveness
check notice a lost close, the connection test drops with a reset, and a
new test pins that the session acts on a close the JDK does report."
```

End the message with your `Co-Authored-By:` trailer.

### Task 4: `powerWhileOnTurnsTheTvOff` waits for setup to finish

**Files:**
- Modify: `src/test/java/dev/andre/homecontrol/adapters/webos/WebOsSessionTest.java` (`powerWhileOnTurnsTheTvOff`)
- Temporarily, never committed: `src/test/java/dev/andre/homecontrol/adapters/webos/FakeSsapServer.java`

**Interfaces:**
- Consumes: nothing.
- Produces: nothing.

Read plan decision 4 first.

- [ ] **Step 1: Make the race fail on demand**

In `FakeSsapServer`, the power-state subscription is answered by

```java
            case SsapUris.POWER_STATE -> connection.send(
                    response(id, "{\"returnValue\":true,\"state\":\"Active\",\"subscribed\":true}"));
```

Temporarily replace it with a late answer, sent from its own thread (sleeping on the fake's reader thread would also hold back the `turnOff` request):

```java
            case SsapUris.POWER_STATE -> Thread.ofVirtual().start(() -> { // TEMPORARY: a late first answer
                try {
                    Thread.sleep(300);
                    connection.send(response(id, "{\"returnValue\":true,\"state\":\"Active\",\"subscribed\":true}"));
                } catch (InterruptedException | IOException _) {
                    // Only for the demonstration.
                }
            });
```

(If `connection.send` declares no `IOException`, catch only `InterruptedException`.)

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.adapters.webos.WebOsSessionTest.powerWhileOnTurnsTheTvOff' --rerun`

Expected: FAIL at `assertThat(session.state().powerOn()).isFalse()`: the late "Active" answer switches power back on.

- [ ] **Step 2: Wait for the end of setup**

In `powerWhileOnTurnsTheTvOff`, replace

```java
        await().atMost(Duration.ofSeconds(5)).until(() -> session.state().powerOn());
```

with

```java
        // The first power-state answer says Active. MAC learning comes after it, so once the MAC is stored that
        // answer cannot land after the turnOff and switch the power back on.
        await().atMost(Duration.ofSeconds(5)).until(() -> storedSetting("macAddress") != null);
        assertThat(session.state().powerOn()).isTrue();
```

- [ ] **Step 3: Run it with the delay, then without**

Run the Step 1 command. Expected: PASS.

Remove the temporary delay from `FakeSsapServer` (`git diff` shows only the test change), and run `scripts/gradle.sh test --tests 'dev.andre.homecontrol.adapters.webos.*' --rerun`. Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/dev/andre/homecontrol/adapters/webos/WebOsSessionTest.java
git commit -m "test: let the webOS power-off test wait until setup has finished"
```

End the message with your `Co-Authored-By:` trailer.

### Task 5: Three runs, measures and documentation

**Files:**
- Modify: `docs/dev/testing.md` (sections "Fakes and fixtures" and "Test configuration")
- Modify: `docs/dev/architecture.md` (section "Progress measures")

**Interfaces:**
- Consumes: everything above.
- Produces: nothing.

- [ ] **Step 1: Run the whole suite three times in a row**

Run each of these to the end, one after another, and after each read the test count and failures from `build/test-results/test/*.xml` before the next run clears them:

```bash
time scripts/gradle.sh test --rerun
time scripts/gradle.sh test --rerun
time scripts/gradle.sh test --rerun
```

Expected:
- All three green, with 2,749 tests each.
- If a test fails, run its class alone. A failure caused by a shared file, a port or a UDP bind is fixed before going on, in its own commit, following the pattern of Tasks 1–3. A timing failure is diagnosed like plan decision 3 and fixed so that it does not depend on CPU share. Each such failure and its fix go in your report.
- Record each run's wall time and summed class time. For the summed class time, use `grep -ho 'testsuite name="[^"]*" tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*" timestamp="[^"]*" hostname="[^"]*" time="[0-9.]*"' build/test-results/test/*.xml | sed -E 's/.*time="([0-9.]*)"/\1/' | awk '{s+=$1} END {printf "%.0f s\n", s}'`.

- [ ] **Step 2: The testing guide**

In `docs/dev/testing.md`, under `## Fakes and fixtures`, add at the end of the list:

```markdown
- A test never shares a file or a fixed port with another test JVM: files go in `@TempDir` or
  `Files.createTempDirectory`, and servers bind port 0.
- The JDK's WebSocket client loses an orderly close (FIN) that arrives while its listener still handles a frame. A
  test that drops a WebSocket connection either lets the session's liveness check notice it
  (`WebOsSessionTest.startedWithLivenessCheck`) or drops with a reset (`FakeWebSocketServer.resetAll()`).
```

Under `## Test configuration`, after the paragraph, add:

```markdown
The unit tests run in up to four JVMs at once. The test file gives each its own data directory,
`build/test-data/<worker>`, and Bluetooth runtime directory, `<java.io.tmpdir>/home-control-bluetooth-<worker>`, from
the system property `org.gradle.test.worker`, which Gradle sets in every test JVM (outside Gradle it is 0).
```

- [ ] **Step 3: Build and check the guarantees**

```bash
scripts/gradle.sh build
scripts/gradle.sh compileE2eJava
git diff --stat origin/main -- src/main
```

Expected:
- The build is green.
- The browser tests compile.
- The last command prints nothing: no production code changed.

- [ ] **Step 4: The progress measures**

In `docs/dev/architecture.md`'s "Progress measures" table, the `test` task wall time row's "Now" cell becomes the median wall time of Step 1's three runs, in the table's style, for example `3 min 10 s (four JVMs, 4 CPUs)`. The summed test-class time row's "Now" cell keeps its one-JVM figure and gets the median four-JVM figure of Step 1, for example `340 s (one JVM), 640 s (four JVMs)`.

- [ ] **Step 5: Commit**

```bash
git add docs/dev/testing.md docs/dev/architecture.md
git commit -m "docs: describe per-fork test isolation and record the Phase 1.3c measures"
```

End the message with your `Co-Authored-By:` trailer.

- [ ] **Step 6: CI's job time, after the pull request's CI run**

Once the pull request's CI run is green, read its "Build and test" job time (`gh run view <run id> --json jobs`, `completedAt − startedAt` of the job named "Build and test"). Put it in the "CI "Build and test" job time" row's "Now" cell, for example `3 min 31 s`, and commit `docs: record CI's Build and test job time after Phase 1.3c` with your trailer.
