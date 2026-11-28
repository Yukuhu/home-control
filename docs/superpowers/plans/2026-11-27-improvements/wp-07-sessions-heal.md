# WP-07: Device sessions heal themselves — Implementation Plan

> **For agentic workers:** work task by task, test first. Steps use checkbox (`- [ ]`) syntax: tick them in the commit
> that does them, and update this package's row in [the index](README.md) in the pull request that lands it.

**Goal:** A Shield that is slow to boot after a power cut is no longer latched UNPAIRED for good: the latch waits a
minute as well as five refusals, and a latched Shield is checked again every 10 minutes and whenever it announces
itself. An LG TV that forgot this server can be accepted on the TV itself. Five d-pad presses on an LG TV that hangs
answer in one request timeout, not five. Stop on a Bluetooth speaker answers at once, even while a stream is starting.

**Findings:**

- [DEV-7](../../reviews/2026-11-27-improvement-catalogue.md#dev-7): all of it (AC5). The latch needs five consecutive
  ambiguous verdicts and 60 s since the first, on the monotonic clock. A latched session stays UNPAIRED and probes
  every 10 minutes and at each mDNS announcement. A configure/active exchange clears it and restores the backoff. A
  fingerprint mismatch still latches at once and is never probed. The Javadoc is fixed.
- [DEV-V1](../../reviews/2026-11-27-improvement-catalogue.md#dev-v1): all of it. A stored key that gets PROMPT waits up
  to `home-control.webos.pairing-timeout` and stores the new key on `registered`. The hint goes into the command
  refusal and the log; a hint on the device chip is WP-12's (decision 4). The on-device check is decision 6.
- [DEV-8](../../reviews/2026-11-27-improvement-catalogue.md#dev-8): what aab4fe8 left open. `button()` opens the
  pointer socket single-flight, and `close()` swaps the pointer out atomically without the monitor.
- [DEV-13](../../reviews/2026-11-27-improvement-catalogue.md#dev-13): all of it. A play generation in `MpvPlayer`;
  Stop, the setup page's Disconnect and `close()` pre-empt a play in flight; volume and mute stop waiting for one.

**Approach:** Each fix stays in the class that owns the state. DEV-7 adds `Reconnector.probeEvery(Duration)`: until the
next connection, retries and losses wait that interval instead of the backoff. `AndroidTvSession` latches into that
mode instead of returning `STOP`, and only `handleReady()` clears it. The window runs on `System.nanoTime()`: a Pi
without a real-time clock steps its wall clock when NTP syncs after a power cut, which would end an `Instant` window
at once, in exactly the case it is for. DEV-V1 gives `SsapConnection.register` a `Runnable onPrompt`: with a stored
key it waits at PROMPT as it already does without one, and `WebOsSession` publishes UNPAIRED from the callback. DEV-8
holds the pointer as an `AtomicReference<CompletableFuture<TextWebSocket>>` that one press fills on its own thread;
`close()` swaps in a closed sentinel, so no `synchronized` remains. DEV-13 gives `MpvPlayer` an `AtomicLong`
generation: `stop()` and `close()` bump it, fail the play's `loaded` future and end a starting mpv without the
monitor, and the session leaves Stop, volume and mute outside its `commands` monitor.

**Depends on / touches:** depends on nothing. Touches WP-12 (WEB-6's "Needs pairing again" label must not promise that
the device never comes back, and a chip hint for a waiting LG prompt belongs there), WP-20 (OPS-4's `nextAttemptAt`
must read the probe interval from `Reconnector`), WP-16 (OPS-3 logs failed plays and reach failures; a pre-empted play
is one), WP-32 (DEV-14's recordings would settle decisions 1 and 6), WP-22 (DEV-3 changes the mismatch path kept here),
WP-23 (PROD-2 adds power actions to these sessions' `execute`), WP-24 and WP-25 (DEV-10, DEV-2 and SEC-3 edit
`SsapConnection` next to Tasks 3 and 4) and WP-31 (ARCH-13 can turn the guards stated here into `@GuardedBy`). No ADR
changes: the new LG key goes through `DeviceSecrets.putDeviceSecret` (ADR 0002), and `SsapConnection` stays Spring-free
in `protocols` (ADR 0006).

**Effort:** M overall. Four pull requests, each S.

## Before you start

- Re-check the findings against current main. At 6a253e4, whose code equals 3932f9b (`git diff --stat 3932f9b HEAD --
  src protocols core` is empty), all four hold:
  - DEV-7: `AndroidTvSession.UNPAIRED_CONFIRMATION_THRESHOLD = 5` with the "about half a minute" Javadoc, though the
    fifth verdict comes after 1+2+4+8 s. On it, `connect()` returns `STOP` and `Attempt.handleDisconnect` calls
    `reconnector.stop()`. `reconnectNow()` does nothing while `consecutiveUnpaired != 0`, which a latched session never
    leaves, and `Reconnector.reconnectNow()` does nothing once stopped.
  - DEV-V1: `SsapConnection.register` throws `KEY_REJECTED` at a PROMPT when `clientKey != null`.
    `WebOsSession.connect` passes `timings.registerTimeout()` (the request timeout, per `WebOsTimings.from`), and its
    `catch (SsapPairingException e)` publishes `DeviceState.unpaired()` and returns `STOP`. `FakeSsapServer.register`
    answers any key but `CLIENT_KEY` with PROMPT and then its `Prompt` (default `ACCEPT`).
  - DEV-8: `SsapConnection.button` is `synchronized` and requests `POINTER_INPUT_SOCKET` itself; `close()` closes the
    pointer in `synchronized (this)`.
  - DEV-13: `BluetoothSpeakerSession.execute` runs every action in `synchronized (commands)`, as does `stopPlayback()`.
    `MpvPlayer.play`, `stop` and `close` are `synchronized`, and `play` waits `loaded.get(loadTimeout)` in the monitor.

  Repeat with `git log 3932f9b..origin/main` over `src/main/java/dev/andre/homecontrol/adapters/{support,androidtv,
  webos,bluetooth}` and `protocols/src/main/java/dev/andre/homecontrol/adapters/webos`, and by reading those methods.
- Owner decisions. Ask them up front. None changes a product rule, a name that never changes, or an ADR:
  1. **Window and probe interval.** Recommended: 60 s and 10 minutes, constants in `AndroidTvSession`, not settings.
     The seventh verdict on the default ramp, about 63 s in, latches: twice the ~30 s boot window the Javadoc names.
  2. **A latched probe that gets no verdict** (unreachable, or another drop). Recommended: stay UNPAIRED at the probe
     cadence. Otherwise a de-paired Shield switched off at night re-runs the confirmation every morning.
  3. **Announcements.** Recommended: one probes a latched Shield at once, with no rate limit; they come in bursts only
     at boot, and a refused Android TV connection shows nothing on screen. While verdicts are counted they stay
     ignored (`anAnnouncementDoesNotHurryTheUnpairedLatch`): a booting Shield announces before it takes the certificate.
  4. **What an LG TV shows while its prompt waits.** Recommended: UNPAIRED, with the command refusal "LG TV is showing
     the Allow prompt: accept it with the TV remote" and one INFO line. A new status or `DeviceState` field would
     change `core`, the SSE payload and the JS, which WP-12 and WP-20 own.
  5. **A hang-up while a stored-key prompt waits.** Recommended: `KEY_REJECTED`, latch and stop. A retry would bring the
     prompt back at each backoff step if dismissing it closes the socket, Samsung's "keeps reappearing" problem.
  6. **Hardware check for DEV-V1.** Not a merge gate. After PR 3, the owner removes Home Control from an LG TV's
     connected apps and presses Allow at the prompt. If the TV drops the prompt anyway, the session only latches a
     pairing timeout later than today. Record the result in PR 3 or the index row.
  7. **A Play pre-empted by Stop.** Recommended: `ActionFailedException("JBL Flip 5: stopped before the stream
     started")`, or "is not connected" after `close()`: a success with nothing playing would mislead. Pause and resume
     keep the `commands` monitor, which DEV-13 leaves out.

## Global constraints

- **Commands are ephemeral.** A key pressed while the LG prompt waits fails at once with the hint; a pre-empted Play
  fails now and is not retried. The probe and the prompt wait are connection attempts, not held commands.
- **A LAN appliance.** The probe is one TLS connection to the paired device's own address every 10 minutes. No thread
  or executor is added: the pointer opens on the pressing thread, `probeEvery` runs on the session's `SessionLoop`.
- **Secrets never reach a log or the browser.** The new LG key goes only to `secrets.putDeviceSecret` under the
  existing `keyRef`; `SsapConnection` still logs no frame; new log lines name the device only.
- **`/data`.** No format changes, nothing migrates, no setting is added.
- **Tests** sit in the code's package and module; the fakes' new switches go into `FakeRemoteServer` and
  `FakeSsapServer` in `protocols/src/testFixtures/java/`. Waits come from `*Timings` in milliseconds. Per
  `docs/dev/testing.md`, a time bound appears only where the time is the behaviour ("Stop answers at once"), with a
  gap of five times or more, and `await().during(...)` covers at least five intervals.
- **SonarCloud.** New shared fields are primitives or `Atomic*` (no `java:S3077`); catch blocks keep the interrupt.
- `scripts/gradle.sh build` is green after every task; no template or script changes, so no `scripts/e2e.sh`.
  Conventional Commits with the scopes `support`, `androidtv`, `webos`, `bluetooth`; `git add <paths>` of the task's
  files only; each commit ends with the session's `Co-Authored-By` and `Claude-Session` lines.

## Review focus

1. **The latch needs both conditions; only a real refusal counts.** `theLatchAlsoWaitsForTheWindow`,
   `latchesUnpairedAfterFiveConsecutiveAmbiguousVerdicts` and
   `doesNotLatchWhenANetworkFailureSeparatesTheAmbiguousVerdicts` (now window zero). `firstUnpairedAt` is nanoTime.
2. **Latched, a Shield stays UNPAIRED while probed, heals on configure/active, then backs off normally; a mismatch is
   never probed.** `aLatchedDeviceThatAcceptsThePairingAgainHealsAtTheNextProbe`,
   `anAnnouncementProbesALatchedDeviceAtOnceAndNormalRetriesResume`, `aProbeThatCannotReachTheDeviceKeepsItUnpaired`,
   `aFingerprintMismatchIsNeverProbed`, `ReconnectorTest.aPendingAttemptThatConnectsEndsProbing`.
3. **A forgotten LG key shows one prompt and Allow stores the new key; Setup's pairing is unchanged.**
   `aTvThatForgotTheKeyIsControlledAgainOnceItsPromptIsAccepted`, `aPromptNobodyAcceptsLatchesUnpairedOnce`,
   `aTvThatHangsUpWhileItsPromptWaitsRejectsTheForgottenKey`, `withoutAKeyAHangUpDuringThePromptIsStillAnIOException`.
4. **One pointer attempt, no cached failure, a close that neither waits nor leaks.**
   `pressesWhileThePointerSocketIsRequestedShareThatRequest`, `aFailedPointerSocketRequestIsTriedAgainByTheNextPress`,
   `closeDoesNotWaitForAPointerSocketThatIsStillOpening`. Read the `complete`/`completeExceptionally` pair in
   `openPointer` and `close`. Waiters rethrow the opener's `DeviceTimeoutException`, so
   `WebOsSessionTest.aButtonIsNamedForPeopleWhenTheTvDoesNotAnswerOrLacksIt` still says "did not answer in time".
5. **Stop, Disconnect and close pre-empt a play wherever it is; plays still take turns.** `MpvPlayerTest`'s three stop
   tests, `stopAnswersWhileAPlayStillConnectsTheSpeaker`, `closeEndsAPlayThatIsStillLoading`, the unchanged
   `concurrentPlaysLeaveOnePlayer`. `stop()` bumps the generation before reading `loading`; `play` sets `loading`
   before reading the generation again.

## File structure

- **Create:** `src/main/java/dev/andre/homecontrol/adapters/bluetooth/player/PlaybackStoppedException.java`
- **Modify:**
  - `src/main/java/dev/andre/homecontrol/adapters/support/Reconnector.java`
  - `src/main/java/dev/andre/homecontrol/adapters/androidtv/AndroidTvSession.java`
  - `src/main/java/dev/andre/homecontrol/adapters/androidtv/AndroidTvTimings.java`
  - `protocols/src/main/java/dev/andre/homecontrol/adapters/webos/protocol/SsapConnection.java`
  - `src/main/java/dev/andre/homecontrol/adapters/webos/WebOsSession.java`
  - `src/main/java/dev/andre/homecontrol/adapters/webos/WebOsTimings.java`
  - `src/main/java/dev/andre/homecontrol/adapters/bluetooth/player/MpvPlayer.java`
  - `src/main/java/dev/andre/homecontrol/adapters/bluetooth/BluetoothSpeakerSession.java`
  - `protocols/src/testFixtures/java/dev/andre/homecontrol/adapters/androidtv/protocol/FakeRemoteServer.java`
  - `protocols/src/testFixtures/java/dev/andre/homecontrol/adapters/webos/protocol/FakeSsapServer.java`
  - `src/main/resources/application.yaml`, `docs/user/devices.md`, `docs/user/configuration.md`,
    `docs/user/bluetooth-speakers.md`
- **Test:**
  - `src/test/java/dev/andre/homecontrol/adapters/support/ReconnectorTest.java`
  - `src/test/java/dev/andre/homecontrol/adapters/androidtv/AndroidTvSessionTest.java`
  - `src/test/java/dev/andre/homecontrol/adapters/androidtv/AndroidTvTimingsTest.java`
  - `protocols/src/test/java/dev/andre/homecontrol/adapters/webos/protocol/SsapConnectionTest.java`
  - `src/test/java/dev/andre/homecontrol/adapters/webos/WebOsSessionTest.java`
  - `src/test/java/dev/andre/homecontrol/adapters/webos/WebOsTimingsTest.java`
  - `src/test/java/dev/andre/homecontrol/adapters/bluetooth/player/MpvPlayerTest.java`
  - `src/test/java/dev/andre/homecontrol/adapters/bluetooth/BluetoothSpeakerSessionTest.java`
  - Unchanged and green: `CastSessionTest` (the other `Reconnector` user), `AndroidTvAdapterTest`, `WebOsAdapterTest`,
    `WebOsPairingTest`, `WebOsEndToEndTest`, `BluetoothPairingServiceTest`, `ArchitectureTest`, `ApplicationYamlTest`.

## Pull requests

| PR | Title | Tasks | Effort |
| --- | --- | --- | --- |
| 1 | `fix(androidtv): let a Shield latched as unpaired heal itself` | 1, 2 | S |
| 2 | `fix(webos): open the pointer input socket once for concurrent presses` | 3 | S |
| 3 | `fix(webos): wait for the Allow prompt a TV shows when it forgot the key` | 4 | S |
| 4 | `fix(bluetooth): let Stop pre-empt a play in flight` | 5, 6 | S |

The pull requests are independent and merge in any order. PRs 2 and 3 edit different methods of `SsapConnection`, so
the later one rebases without a real conflict. Task 1's API has no caller until Task 2, so every commit leaves main
releasable.

## Tasks

File names below are the ones under File structure.

### Task 1: `Reconnector.probeEvery` waits a fixed interval until the next connection

**Files:** Modify `Reconnector.java`. Test `ReconnectorTest.java`.

**Interfaces:** new `public void probeEvery(Duration interval)`; private `volatile long probeNanos` (0 when off) and
`Duration nextDelay()`.

- [ ] **Step 1: Write the failing test.** Each connect step calls `probeEvery` on the loop, as a session does, through
  an `AtomicReference<Reconnector> self`; the backoff is a flat 50 ms.
  - `whileProbingARetryWaitsTheIntervalNotTheBackoff`: attempt 1 calls `probeEvery(500 ms)` and returns `RETRY`, then
    `RETRY`, then `CONNECTED`; `gapMillis(0)` and `gapMillis(1)` are at least 450.
  - `whileProbingALossWaitsTheInterval`: attempt 1 probes and returns `PENDING`; after `lost()` on the loop, attempt 2
    comes at least 450 ms later.
  - `aPendingAttemptThatConnectsEndsProbing` and `anAttemptThatConnectsEndsProbing`: after `connected()`, or after a
    `CONNECTED` outcome, a `lost()` brings the next attempt within `await().atMost(Duration.ofMillis(400))`, as in
    `connectedResetsTheBackoffForTheNextLoss`.
  - `reconnectNowStillTriesAtOnceWhileProbing`: with a 1-minute probe, `reconnectNow()` brings attempt 2 within 2 s.
- [ ] **Step 2: Run it to see it fail.** `scripts/gradle.sh test --tests
  'dev.andre.homecontrol.adapters.support.ReconnectorTest'`: `cannot find symbol: probeEvery`.
- [ ] **Step 3: Implement.**

  ```java
  /**
   * Until the next connection, a retry and a loss wait {@code interval} instead of the backoff: the session thinks
   * connecting cannot work now but wants it checked now and then. {@link #reconnectNow()} still tries at once.
   */
  public void probeEvery(Duration interval) {
      probeNanos = interval.toNanos();
  }

  private Duration nextDelay() {
      long probe = probeNanos;
      return probe > 0 ? Duration.ofNanos(probe) : backoff.next();
  }
  ```

  `lost()` and `attempt()`'s `RETRY` branch (when no `retryIn` wait stands) schedule `nextDelay()`. The `CONNECTED`
  outcome and `connected()` set `probeNanos = 0` beside `backoff.reset()`. The class Javadoc names `probeEvery`.
- [ ] **Step 4: Run the tests.** The Step 2 command, `scripts/gradle.sh test --tests 'dev.andre.homecontrol.adapters.*'`
  (Cast and webOS share the class) and `scripts/gradle.sh build`: green.
- [ ] **Step 5: Update docs.** None; `docs/dev/architecture.md` names `Reconnector` only.
- [ ] **Step 6: Commit.** `refactor(support): let a Reconnector probe at a fixed interval until it connects`.

### Task 2: A time-based Android TV latch that probes and heals

**Files:** Modify `AndroidTvSession.java`, `AndroidTvTimings.java`, `FakeRemoteServer.java`, `docs/user/devices.md`.
Test `AndroidTvSessionTest.java`, `AndroidTvTimingsTest.java`.

**Interfaces:** `AndroidTvTimings` gains `Duration unpairedWindow, Duration unpairedProbeInterval`; its 3- and
5-argument constructors fill them with the new package-private `AndroidTvSession.UNPAIRED_WINDOW` (60 s) and
`UNPAIRED_PROBE_INTERVAL` (10 min), and the new `withUnpairedLatch(Duration window, Duration probeInterval)` serves
tests. New `FakeRemoteServer.closeEveryConnection(boolean on)`: once the script is used up, it closes every connection
before the TLS handshake, as `closeNextConnections` does (`if (gate == null && closingEvery) continue;` in `serve()`).
`AndroidTvSession` keeps `timings` in a field and gains loop-only `long firstUnpairedAt` and `boolean latched`.

- [ ] **Step 1: Write the failing test.** In `AndroidTvSessionTest`:
  - `FAST_RETRY` and `SHORT_TIMEOUT` gain `.withUnpairedLatch(Duration.ZERO, Duration.ofMinutes(1))`: the count alone
    latches, and no probe comes during a test. Otherwise the window would make
    `doesNotLatchWhenANetworkFailureSeparatesTheAmbiguousVerdicts` pass for the wrong reason. New: `WINDOWED` (50 ms
    flat, window 1 s, probe 1 min) and `PROBING` (50 ms flat, window zero, probe 300 ms).
  - `theLatchAlsoWaitsForTheWindow`: `closeEveryConnection(true)` and `WINDOWED`. From `start()` to UNPAIRED takes at
    least 1000 ms (`System.nanoTime()`), with `connections()` at least 5. Today five verdicts latch in well under 1 s.
  - `aLatchedDeviceThatAcceptsThePairingAgainHealsAtTheNextProbe`: `PROBING` with a `RecordingStateListener`; after
    UNPAIRED, `closeEveryConnection(false)`; CONNECTED within 5 s, and the statuses never left UNPAIRED until then:

    ```java
    assertThat(states.all().stream().map(DeviceState::status)
            .dropWhile(status -> status != DeviceStatus.UNPAIRED).toList())
            .containsOnly(DeviceStatus.UNPAIRED, DeviceStatus.CONNECTED).endsWith(DeviceStatus.CONNECTED);
    ```

  - `aLatchedDeviceIsProbedAtTheProbeIntervalNotTheBackoff`: `FAST_RETRY`; after UNPAIRED, `await().during(500 ms)`
    sees no new connection. It passes today too (`STOP`); it guards the slow cadence.
  - `anAnnouncementProbesALatchedDeviceAtOnceAndNormalRetriesResume`: `FAST_RETRY`; after UNPAIRED,
    `closeEveryConnection(false)` and `reconnectNow()` give CONNECTED within 5 s. After `fakeDevice.hangUp()`, one more
    connection and CONNECTED come within 5 s, which only the 50 ms backoff, not the 1-minute probe, can give.
  - `aProbeThatCannotReachTheDeviceKeepsItUnpaired`: `SHORT_TIMEOUT` (1 s stale timeout); after UNPAIRED,
    `stallNextConnection()`, `reconnectNow()` and `awaitEntered()`; the status stays UNPAIRED `during(1500 ms)`, and no
    status after the first UNPAIRED differs from it (decision 2). Then `release()`.
  - `aFingerprintMismatchIsNeverProbed`: the impostor of `refusesADeviceWhoseCertificateDoesNotMatchThePin` with
    `FAST_RETRY`; after UNPAIRED and `reconnectNow()`, `connections()` stays 1 `during(500 ms)`.
  - `latchesUnpairedAfterFiveConsecutiveAmbiguousVerdicts`, `latchesWhenRemoteRejectsAfterTlsBeforeConfiguration` and
    `anAnnouncementDoesNotHurryTheUnpairedLatch` stay; the last one's comment drops "and five latch a pairing that is
    fine": the guard keeps a booting device on the backoff ramp.
  - `AndroidTvTimingsTest.theProductionLatchWaitsAMinuteAndProbesEveryTenMinutes` reads both from `from(...)`.
- [ ] **Step 2: Run it to see it fail.** `scripts/gradle.sh test --tests
  'dev.andre.homecontrol.adapters.androidtv.AndroidTvSessionTest' --tests
  'dev.andre.homecontrol.adapters.androidtv.AndroidTvTimingsTest'`. Compilation fails on `withUnpairedLatch`,
  `closeEveryConnection` and `unpairedWindow`; with stubs, the window test fails on the elapsed time and the heal and
  announcement tests time out.
- [ ] **Step 3: Implement.** In `AndroidTvSession`, `ambiguousUnpaired()` returns `void`:

  ```java
  private void ambiguousUnpaired() {
      if (latched) {
          return; // a probe refused again: stays UNPAIRED, one DEBUG line
      }
      long now = System.nanoTime();
      if (consecutiveUnpaired++ == 0) {
          firstUnpairedAt = now;
      }
      Duration spanned = Duration.ofNanos(now - firstUnpairedAt);
      if (consecutiveUnpaired < UNPAIRED_CONFIRMATION_THRESHOLD || spanned.compareTo(timings.unpairedWindow()) < 0) {
          // today's INFO line, with the time spanned
          publisher.update(state -> state.withStatus(DeviceStatus.DISCONNECTED));
          return;
      }
      latched = true;
      // today's WARN line, plus "checking again every 10 min and whenever it announces itself"
      publisher.update(state -> state.withStatus(DeviceStatus.UNPAIRED));
      reconnector.probeEvery(timings.unpairedProbeInterval());
  }
  ```

  - `connect()` publishes CONNECTING only `if (!latched)`. Its `UnpairedException` branch calls `ambiguousUnpaired()`
    and returns `RETRY`; its `IOException` branch returns `RETRY` right after the DEBUG line when latched. The mismatch
    branch is unchanged: UNPAIRED and `STOP`.
  - `handleReady()` goes on when the status is CONNECTING or `latched`; when it was latched, it logs at INFO "Device
    {} accepted its pairing again". `forgetAmbiguousVerdicts()` also clears `firstUnpairedAt` and `latched`.
  - `Attempt.handleDisconnect`: `DisconnectCause.UNPAIRED` runs `ambiguousUnpaired()` then `reconnector.lost()`, with
    no `stop()` left. For any other cause, a latched session calls `reconnector.lost()` and returns.
  - `reconnectNow()` tries `if (consecutiveUnpaired == 0 || latched)`; its Javadoc says why.
  - The Javadoc of `UNPAIRED_CONFIRMATION_THRESHOLD` states the real timing (AC5): the fifth verdict comes about 15 s
    after the first, inside a booting Shield's window, so the latch also waits `UNPAIRED_WINDOW` on
    `System.nanoTime()`, which a clock step does not move. A latched session probes with the same credential until a
    configure/active exchange; a mismatch latches at once and is never probed. The class Javadoc follows.
- [ ] **Step 4: Run the tests.** The Step 2 command, `scripts/gradle.sh test --tests
  'dev.andre.homecontrol.adapters.androidtv.*'` and `scripts/gradle.sh build`: green.
- [ ] **Step 5: Update docs.** `docs/user/devices.md`, after the paragraph on reconnecting at an announcement: "A
  Shield that refuses this server's certificate (it was reset, or the pairing was removed on it) shows **UNPAIRED**:
  pair it again from **Setup**. A Shield that is still starting can refuse it too, so Home Control says so only after
  five refusals in a row over at least a minute, and even then checks again every 10 minutes and whenever the Shield
  announces itself. A Shield that was only slow to start after a power cut comes back by itself."
- [ ] **Step 6: Commit.** `fix(androidtv): wait a minute before latching UNPAIRED, then keep checking`.

### Task 3: One pointer socket attempt for concurrent presses, and a close without the monitor

**Files:** Modify `SsapConnection.java`, `FakeSsapServer.java`. Test `SsapConnectionTest.java`.

**Interfaces:** `button(String)` loses `synchronized`. New private `pointer()`, `openPointer(CompletableFuture<
TextWebSocket>)`, `awaitPointer(...)`, `giveUp(...)`, the field `AtomicReference<CompletableFuture<TextWebSocket>>
pointer`, the sentinel `CLOSED_POINTER = CompletableFuture.failedFuture(new IOException("closed"))` and
`POINTER_LISTENER` (today's anonymous listener). New `FakeSsapServer.requestCount(String uri)`, counted in `onMain`.

- [ ] **Step 1: Write the failing test.** Each press runs on `Thread.ofVirtual().start(...)` (a pool could run them in
  turn) and completes a `CompletableFuture<Throwable>` with its `IOException`, or null.
  - `pressesWhileThePointerSocketIsRequestedShareThatRequest`: `ignoreRequests(SsapUris.POINTER_INPUT_SOCKET)`, then
    "UP", "DOWN" and "LEFT" at once. Each fails with `DeviceTimeoutException`, and `requestCount(...)` is 1. Today they
    queue on the monitor and make three requests of 2 s each.
  - `aFailedPointerSocketRequestIsTriedAgainByTheNextPress`: with the request ignored, `button("UP")` throws
    `DeviceTimeoutException`; after `answerRequests(...)`, "DOWN" arrives and `requestCount` is 2. It passes today; it
    pins that a failure is not cached.
  - `closeDoesNotWaitForAPointerSocketThatIsStillOpening`: `offerPointerSocket` names a loopback `ServerSocket` whose
    virtual-thread `accept()` counts down a latch and never answers. The connection uses `new SsapOptions(
    server.port(), FakeWebSocketServer.closedPort(), Duration.ofSeconds(10), Duration.ofSeconds(2))`, so a pointer
    connect can take 20 s. Press "UP" (the opener), await the latch, press "DOWN" (a waiter); then
    `CompletableFuture.runAsync(opened::close).get(2, SECONDS)` returns, and the waiter fails within 2 s with `The
    connection to the TV was closed`. `finally` closes both sockets. Today `close()` waits for the opener's monitor.
- [ ] **Step 2: Run it to see it fail.** `scripts/gradle.sh :protocols:test --tests
  'dev.andre.homecontrol.adapters.webos.protocol.SsapConnectionTest'`: with `requestCount` added, `expected: 1 but
  was: 3`, and a `TimeoutException` in the close test.
- [ ] **Step 3: Implement.** `button(name)` is `pointer().send(SsapMessages.button(name))`.

  ```java
  private TextWebSocket pointer() throws IOException {
      while (true) {
          CompletableFuture<TextWebSocket> current = pointer.get();
          if (current == CLOSED_POINTER) {
              throw new IOException(closedReason);
          }
          if (current == null) {
              CompletableFuture<TextWebSocket> mine = new CompletableFuture<>();
              if (pointer.compareAndSet(null, mine)) {
                  return openPointer(mine);
              }
          } else {
              TextWebSocket open = awaitPointer(current);
              if (open.isOpen()) {
                  return open;
              }
              pointer.compareAndSet(current, null); // the TV closed it: the next pass opens another
          }
      }
  }
  ```

  `openPointer(mine)` is today's request and `TextWebSocket.connect`, then `if (!mine.complete(opened)) {
  opened.close(); throw new IOException(closedReason); }`, for a `close()` that failed the attempt meanwhile. Its
  `catch (IOException e)` calls `giveUp(mine, e)` and rethrows; a `finally` gives up with a generic `IOException` for
  an unchecked failure. `giveUp` is `pointer.compareAndSet(mine, null)` then `mine.completeExceptionally(e)`, no-ops
  once the attempt is done. `awaitPointer` calls `get()` and unwraps the `IOException` cause; an interrupt restores
  the flag. It needs no timeout of its own: the opener's request and connect are bounded. `close()` keeps its first
  two steps and replaces its `synchronized` block:

  ```java
  CompletableFuture<TextWebSocket> previous = pointer.getAndSet(CLOSED_POINTER);
  if (previous != null) {
      previous.completeExceptionally(new IOException(closedReason));
      previous.thenAccept(TextWebSocket::close); // closes one that had already opened
  }
  ```

- [ ] **Step 4: Run the tests.** The Step 2 command, `scripts/gradle.sh :protocols:test`, `scripts/gradle.sh test
  --tests 'dev.andre.homecontrol.adapters.webos.*'` and `scripts/gradle.sh build`: green.
- [ ] **Step 5: Update docs.** None; only the wait changes. The `close()` Javadoc says it takes no lock.
- [ ] **Step 6: Commit.** `fix(webos): share one pointer socket attempt between concurrent presses`.

### Task 4: A TV that forgot the key can be accepted on the TV

**Files:** Modify `SsapConnection.java`, `FakeSsapServer.java`, `WebOsSession.java`, `WebOsTimings.java`,
`application.yaml`, `docs/user/devices.md`, `docs/user/configuration.md`. Test `SsapConnectionTest.java`,
`WebOsSessionTest.java`, `WebOsTimingsTest.java`.

**Interfaces:** new `public String register(String clientKey, Duration promptTimeout, Runnable onPrompt)`; the
2-argument one passes a no-op, so `WebOsPairing` does not change. `WebOsTimings.registerTimeout` becomes
`promptTimeout`, from `properties.pairingTimeout()`. New `FakeSsapServer.answerPrompt(Prompt answer)`: it answers a
prompt that `Prompt.IGNORE` left waiting on every main connection (`registered` with `CLIENT_KEY`, or `403 User denied
access`). `WebOsSession` gains `volatile boolean awaitingPrompt` and `promptShown()`.

- [ ] **Step 1: Write the failing test.**
  - `SsapConnectionTest` replaces `aStoredKeyTheTvForgotIsRejected` with:
    `aStoredKeyTheTvForgotWaitsForThePromptAndTakesTheNewKey` (`ACCEPT`: `register("forgotten", 2 s, () ->
    prompted.set(true))` returns `CLIENT_KEY`, and `prompted` is true); `aForgottenKeyWhosePromptIsDeclinedIsRejected`
    (`KEY_REJECTED`, "403"); `aForgottenKeyWhosePromptNobodyAnswersTimesOut` (`TIMED_OUT`, `Nobody answered the prompt
    on the TV within 1 seconds`); and `aTvThatHangsUpWhileItsPromptWaitsRejectsTheForgottenKey`. In that one,
    `openScripted` answers with the PROMPT response and keeps the `Connection`; after `onPrompt` counts down a latch
    on the register's virtual thread, `connection.reset()` gives `KEY_REJECTED` and `The TV closed the connection
    before its prompt was answered`. `withoutAKeyAHangUpDuringThePromptIsStillAnIOException`: the same script with
    `register(null, …)` gives an `IOException` that is no `SsapException`, `The TV closed the connection during
    registration`.
  - `WebOsSessionTest`: `aForgottenPairingIsUnpairedAndStopsReconnecting` and
    `anUnpairedTvRefusesCommandsUntilItIsPairedAgain` first `setPrompt(Prompt.DECLINE)`, since the default `ACCEPT` now
    pairs them; the `TIMINGS` comment calls its 2 s the prompt wait. New constant `PROMPT_WAIT` (10 s) and:
    - `aTvThatForgotTheKeyIsControlledAgainOnceItsPromptIsAccepted`: `IGNORE`, the key "forgotten", `PROMPT_WAIT`.
      After UNPAIRED, `execute(new Action.PressKey(RemoteKey.HOME))` fails with `DeviceOfflineException` `LG TV is
      showing the Allow prompt: accept it with the TV remote`. After `tv.answerPrompt(ACCEPT)` the session connects,
      `secrets.deviceSecret(WebOsSettings.secretName(KEY_REF))` contains `CLIENT_KEY`, and `registrations()` is 1.
    - `aPromptNobodyAcceptsLatchesUnpairedOnce`: `IGNORE` with `TIMINGS`; within 5 s a key press says `LG TV must be
      paired again before it can be controlled`, then `registrations()` stays 1 `during(1 s)`.
  - `WebOsTimingsTest.fromTakesTheConfiguredSeconds` expects the pairing timeout (61 s) as the fifth component.
- [ ] **Step 2: Run it to see it fail.** `scripts/gradle.sh :protocols:test --tests
  'dev.andre.homecontrol.adapters.webos.protocol.SsapConnectionTest'` and `scripts/gradle.sh test --tests
  'dev.andre.homecontrol.adapters.webos.*'`: compilation fails on the 3-argument `register`, `answerPrompt` and
  `promptTimeout`.
- [ ] **Step 3: Implement.**
  - The PROMPT branch of `SsapConnection.register` no longer throws for a stored key:

    ```java
    onPrompt.run();
    answer = awaitRegistration(promptTimeout);
    if (answer == null) {
        throw new SsapPairingException(SsapPairingException.Reason.TIMED_OUT,
                "Nobody answered the prompt on the TV within " + promptTimeout.toSeconds() + " seconds");
    }
    if (clientKey != null && type(answer).equals("closed")) {
        throw new SsapPairingException(SsapPairingException.Reason.KEY_REJECTED,
                "The TV closed the connection before its prompt was answered");
    }
    ```

    The `registered` and `error` cases stay; the Javadoc says a TV that forgot the key shows the prompt and hands out
    a new key on Allow.
  - `WebOsSession.connect` calls `opened.register(clientKey, timings.promptTimeout(), this::promptShown)` and gains
    `finally { awaitingPrompt = false; }`. `promptShown()` sets the flag, publishes `DeviceState.unpaired()` and logs
    at INFO "{} forgot this server and shows the Allow prompt; accept it with the TV remote within {}". When the
    existing branch stores a changed key, an INFO line says "{} accepted this server again". `requireConnected()`
    throws the hint while `awaitingPrompt`. The class Javadoc drops "connecting again would re-prompt": the session
    waits once, and only a declined, unanswered or cut-off prompt leaves re-pairing.
  - The `WebOsTimings` Javadoc: pairing and a reconnect to a TV that forgot this server both use the prompt wait.
    `application.yaml`: "# How long the setup page, or a reconnect to a TV that forgot this server, waits for someone
    to accept the prompt on the TV."
- [ ] **Step 4: Run the tests.** The Step 2 commands, `scripts/gradle.sh test --tests
  'dev.andre.homecontrol.web.WebOsEndToEndTest' --tests 'dev.andre.homecontrol.ApplicationYamlTest'` and
  `scripts/gradle.sh build`: green.
- [ ] **Step 5: Update docs.** `docs/user/devices.md`, the LG sentence: "An LG TV that forgot this server (a factory
  reset, or Home Control removed from the TV's connected apps) shows its Allow prompt the next time Home Control
  connects, and the device shows **UNPAIRED** meanwhile. Accept it with the TV remote within
  `home-control.webos.pairing-timeout` (60 seconds by default), and the TV is controlled again without a trip to
  Setup. If it is declined or nobody answers, the TV is not asked again until Home Control restarts: pair it again
  from **Setup**." `docs/user/configuration.md`, the `pairing-timeout` row: "How long pairing, and a reconnect to a TV
  that forgot this server, waits for someone to accept the prompt on the TV".
- [ ] **Step 6: Commit.** `fix(webos): accept a TV's Allow prompt when it forgot the stored key`.

### Task 5: `MpvPlayer.stop()` and `close()` pre-empt a play in flight

**Files:** Create `PlaybackStoppedException.java`. Modify `MpvPlayer.java`. Test `MpvPlayerTest.java`.

**Interfaces:** new `public final class PlaybackStoppedException extends IOException` ("playback was stopped before
the stream started"), so `play`'s `throws` clause stays. New `public long generation()` and `play(URI, String, int,
boolean, long generation)`; the 4-argument `play` passes `generation()`. `stop()` and `close()` stop being
`synchronized` methods, and `closed` becomes `volatile`. New private `stopRunning()` (today's `stop()` body),
`AtomicLong generation`, `AtomicReference<CompletableFuture<Void>> loading`, `AtomicReference<MpvProcess> starting`.

- [ ] **Step 1: Write the failing test.** Each play runs on a virtual thread with its failure in a future, as in
  `interruptingAnUnfinishedLoadStopsThePlayerAndPreservesTheInterrupt`.
  - `aPlayTakenBeforeAStopStartsNoMpv`: `long ticket = player.generation(); player.stop();`, then `play(…, ticket)`
    throws `PlaybackStoppedException`, and `launcher.starts` is empty.
  - `stopEndsAPlayThatIsStillLoading`: `FakeMpv::holdFileLoaded` and a 30 s load timeout. Once the `loadfile` command
    arrived, `CompletableFuture.runAsync(player::stop).get(2, SECONDS)` returns, the play fails with
    `PlaybackStoppedException` within 2 s, and `launcher.alive()` reaches zero.
  - `stopEndsAPlayWhoseMpvIsStillStarting`: `launcher.startDelay` and the start timeout 30 s; once `launcher.starts`
    has one entry, stop as above. The play fails with `PlaybackStoppedException`, not "did not open its control socket".
  - `closeEndsAPlayThatIsStillLoadingAndRefusesTheNext`: the loading test with `close()`; a later `play` throws `the
    player is closed`.
- [ ] **Step 2: Run it to see it fail.** `scripts/gradle.sh test --tests
  'dev.andre.homecontrol.adapters.bluetooth.player.MpvPlayerTest'`: compilation fails on `generation()`, the
  5-argument `play` and `PlaybackStoppedException`.
- [ ] **Step 3: Implement.**

  ```java
  /** Ends a play in flight at once, without waiting for it, then stops what plays. */
  public void stop() {
      generation.incrementAndGet();
      CompletableFuture<Void> load = loading.get();
      if (load != null) {
          load.completeExceptionally(new PlaybackStoppedException());
      }
      MpvProcess spawned = starting.get();
      if (spawned != null) {
          spawned.terminate(Duration.ofSeconds(1));
      }
      synchronized (this) { // a pre-empted play lets go once it has torn its own mpv down
          stopRunning();
      }
  }
  ```

  `close()` sets `closed = true` and calls `stop()`. The synchronized `play(…, expected)` checks `closed`, then throws
  `PlaybackStoppedException` if `generation.get() != expected`, and calls `stopRunning()` where it called `stop()`.
  Right after `launcher.start` it sets `starting`, then `loading`, then reads the generation again, terminating and
  throwing if it moved: `stop()` bumps before it reads, so one side always sees the other. An `IOException` from
  `MpvIpc.connect` becomes `PlaybackStoppedException` when the generation moved, else `withErrors(e, process)` as now.
  `starting` clears once `running` is set. An `ExecutionException` whose cause is a `PlaybackStoppedException` runs
  `stopRunning()` and rethrows the cause, never through `withErrors`. A `finally` from `launcher.start` on clears both
  references with `compareAndSet`. `stopIfCurrent` calls `stopRunning()`, so cleaning up a dead player pre-empts
  nothing.
- [ ] **Step 4: Run the tests.** The Step 2 command, `scripts/gradle.sh test --tests
  'dev.andre.homecontrol.adapters.bluetooth.*'` and `scripts/gradle.sh build`: green.
- [ ] **Step 5: Update docs.** None; Task 6 makes it visible.
- [ ] **Step 6: Commit.** `fix(bluetooth): let stop and close end a play that is still starting`.

### Task 6: Stop, volume and mute answer while a play is in flight

**Files:** Modify `BluetoothSpeakerSession.java`, `docs/user/bluetooth-speakers.md`. Test
`BluetoothSpeakerSessionTest.java`.

**Interfaces:** no public change. New private `Object levels`, `RuntimeException stopped()` and `catchUpLevels(int,
boolean)`. `stopPlayback()` stays package-private for `BluetoothPairingService.disconnect`.

- [ ] **Step 1: Write the failing test.** `playInBackground()` runs `session.execute(PLAY)` on a virtual thread and
  completes a `CompletableFuture<Throwable>` with what it threw, or null.
  - `stopAnswersWhileAPlayStillConnectsTheSpeaker`: a paired, unconnected speaker, `withAutoConnect(false)`,
    `bluez.whenCalled("connect", connecting::countDown)` and `bluez.delay("connect", Duration.ofSeconds(3))`. After
    `connecting`, `runAsync(() -> session.execute(new Action.Stop())).get(1, SECONDS)` returns; the play fails with
    `ActionFailedException` `JBL Flip 5: stopped before the stream started`, and `launcher.starts` is empty.
  - `stopEndsAPlayThatIsStillLoading`: a connected speaker, `FakeMpv::holdFileLoaded`, a 30 s load timeout through
    `properties.withTimings(...)`. After `loadfile`, Stop returns within 2 s, the play fails with the same message, and
    `launcher.alive()` reaches zero.
  - `volumeAndMuteAnswerWhileAPlayStillConnectsTheSpeaker`: the first test's setup; while connecting, `SetVolume(30)`
    and `Mute(true)` return within 1 s, then the play succeeds with `--volume=30` and `launcher.latest().muted()`.
  - `aVolumeSetWhileMpvStartsReachesTheStream`: `launcher.startDelay = Duration.ofSeconds(1)`; once `launcher.starts`
    has one entry, `SetVolume(30)`; `launcher.latest().volume()` reaches `30.0`. It passes today (the volume waits for
    the play); it pins the catch-up that replaces the wait.
  - `closeEndsAPlayThatIsStillLoading`: as above with `session::close`; `DeviceOfflineException` `JBL Flip 5 is not
    connected`, and no mpv is left.
  - Unchanged and green: `concurrentPlaysLeaveOnePlayer`, `aPlayThatOutlivesItsSessionStartsNoPlayer`,
    `aCommandThatRacesCloseStillAnswers`, `pauseResumeVolumeMuteAndStop`.
- [ ] **Step 2: Run it to see it fail.** `scripts/gradle.sh test --tests
  'dev.andre.homecontrol.adapters.bluetooth.BluetoothSpeakerSessionTest'`: the Stop and volume tests fail with a
  `TimeoutException`, the loading tests with "could not play the stream".
- [ ] **Step 3: Implement.**
  - In `execute`, `PlayMedia`, `Pause` and `Resume` run in `synchronized (commands)`; `Stop` calls `stopPlayback()`;
    `SetVolume` and `Mute` store and apply in `synchronized (levels)` through `whilePlaying`. The `closed` check and
    the trailing `pollNow()` stay. A comment on `commands` says plays, pauses and resumes take turns, and that Stop,
    volume and mute never wait for them.
  - `stopPlayback()` drops its monitor: `player.stop(); title = null;`. Its Javadoc says a play in flight gives up.
  - `play` reads `player.generation()` before `ensureConnected()` and throws `stopped()` if it moved by then (a Stop
    while BlueZ connected the speaker). It reads `volume` and `muted` once, passes them and the generation to
    `player.play`, sets `title`, and calls `catchUpLevels`. A new first `catch (PlaybackStoppedException _)` throws
    `stopped()`.
  - `stopped()` is `DeviceOfflineException(name + " is not connected")` when `closed`, else
    `ActionFailedException(name + ": stopped before the stream started")` (decision 7).
  - `catchUpLevels`, in `synchronized (levels)`, applies a volume or mute set after the play read them, while mpv
    started and `whilePlaying` saw no player. Its failures go to DEBUG and the next poll; they never fail the Play.
- [ ] **Step 4: Run the tests.** The Step 2 command, `scripts/gradle.sh test --tests
  'dev.andre.homecontrol.adapters.bluetooth.*'` and `scripts/gradle.sh build`: green.
- [ ] **Step 5: Update docs.** A row in the troubleshooting table of `docs/user/bluetooth-speakers.md`, after "could
  not be loaded": `Play: "JBL Flip 5: stopped before the stream started"` | Stop was pressed, or the speaker was
  disconnected on the setup page, while the stream was still starting | Intended: Stop answers at once and the
  starting stream does not play. Play again.
- [ ] **Step 6: Commit.** `fix(bluetooth): answer Stop, volume and mute without waiting for a play in flight`.

## Done when

- DEV-7, DEV-V1, DEV-8 and DEV-13 are resolved, except two parts left out with their reasons below: the chip hint for
  a waiting LG prompt (decision 4) and the hardware checks (decision 6).
- An Android TV latches UNPAIRED only after five consecutive ambiguous verdicts over at least 60 s of monotonic time.
  Latched, it stays UNPAIRED, is probed every 10 minutes and at each announcement, and returns to CONNECTED with the
  normal backoff on configure/active. A mismatch latches at once with no probe. The threshold's Javadoc is right.
- An LG TV that forgot the stored key keeps the socket up to the pairing timeout: Allow stores the new key as a device
  secret and connects; Deny, silence or a hang-up latch UNPAIRED after one prompt.
- Concurrent presses make one pointer-socket request, a failed attempt is not cached, and `SsapConnection` holds no
  monitor.
- Stop, Disconnect, volume, mute and `close()` on a Bluetooth speaker never wait for a play in flight; a pre-empted
  play leaves no mpv running and fails with a reason.
- `docs/user/devices.md`, `docs/user/configuration.md`, `docs/user/bluetooth-speakers.md` and `application.yaml` say
  what changed. `scripts/gradle.sh build` is green after each commit, and `scripts/check-commits.sh` accepts them.
- This package's row in [the index](README.md) is updated, with decision 6's result or "unverified on hardware".

## Out of scope

- **A hint on the device chip or Setup while an LG prompt waits, and wording for a probing Shield.** `DeviceState` has
  no field for it: WP-12 (WEB-6's labels) and WP-20 (OPS-4's last failure and next attempt).
- **Settings for the window and the probe interval** (decision 1), and recording real boot windows: WP-32 (DEV-14).
- **The mismatch path and AC2's fingerprint re-pair:** WP-22 (DEV-3).
- **Samsung's prompt.** A Tizen TV that forgot this server stays DISCONNECTED and is retried, as documented; no finding
  asks to change it.
- **A newer Play pre-empting an older one, Pause or Resume during a load, cancelling a BlueZ connect.** Not in DEV-13.
  A BlueZ page timeout still runs out on the play's thread; Stop answers at once and the play gives up afterwards.
- **A shared single-flight helper in `adapters.net`.** The pointer socket is its only user.
- **INFO logs for a pre-empted play or a failed probe:** WP-16 (OPS-3). **`@GuardedBy`:** WP-31 (ARCH-13).
