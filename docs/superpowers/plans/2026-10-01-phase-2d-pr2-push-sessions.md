# Phase 2D, PR 2: The Push Sessions on the Toolkit — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cast, Android TV, webOS and Tizen compose the `adapters.support` toolkit that PR 1 built, the TV adapters
share their helpers and one session registry, and the play/pause race is gone.

**Architecture:** Each session keeps its protocol and its policies and takes its plumbing from `adapters.support`: a
`SessionLoop` (one virtual thread), a `StatePublisher`, a `ConnectionSlot` for the live connection and, for the three
push-style sessions, a `Reconnector` that schedules `connect()`. Tizen stays poll-driven. `WakeOnLanPower`,
`LearnedMac`, `PlayPauseToggle` and `SessionRegistry` replace near-twin code in webOS, Tizen, Sonos and UPnP. A strict
ArchUnit rule keeps executors out of sessions.

**Tech Stack:** Java 25 (virtual threads), Spring Boot 4.1.1, Jackson 3 (`tools.jackson.*`), JUnit 5, AssertJ,
Awaitility, ArchUnit 1.5.1. Build with `scripts/gradle.sh` (Gradle in the `gradle:jdk25` image; `-q`, silent on
success).

**Spec:** `docs/superpowers/specs/2026-10-01-phase-2d-adapter-lifecycle-design.md`, sections 1, 4 and 5 and the PR 2
lines of Testing and Delivery. PR 1 (#162, merged as `f2a92aa`) built the toolkit; this branch,
`refactor/adapter-sessions`, starts from `f2a92aa`.

## Global Constraints

- Nothing a user sees changes except (spec, Visible changes): webOS's and Cast's "offline" messages read
  "{name} could not be reached to {what}" instead of "dropped the connection"; Android TV's messages name the device;
  a Tizen state-listener failure no longer reaches whoever pressed a button; concurrent play/pause presses alternate.
- Protocol packages (`..protocol..`) depend only on protocol packages and `adapters.net`. Anything that builds core
  exceptions or core state lives in `adapters.support` or the session's own package.
- A state listener that throws a `RuntimeException` is logged and the session goes on; an `Error` propagates
  (`AndroidTvSessionTest.doesNotContinueConnectingAfterAStateListenerThrowsAnError` pins it).
- `DeviceState.updatedAt` is never shown and drives no decision; suppressing states that differ only in it is
  invisible.
- Frozen ArchUnit violations: never refreeze. The store stays at 25 lines.
- Names that never change: `shield.*` configuration, `SHIELD_KEYSTORE_PASSWORD`, the CasaOS app id
  `dev.andre.shield-remote`, the Android TV client package name `dev.andre.shield`.
- `scripts/gradle.sh build` green at the end of every task; `scripts/e2e.sh -Pe2eBrowsers=chromium` at the end of the
  PR.
- Commits follow Conventional Commits, stage only the files the task changed (`git add <paths>`, never `git add -A`),
  and end with:

  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01Q6dsbK16wqsU1DrkTAV32K
  ```
- A deliberate exception to a SonarCloud rule uses the narrowest `@SuppressWarnings("java:S…")` with a one-line
  reason.

## Review Focus

1. **A reconnect and another one-shot wait never cancel each other** (Android TV's playback expiry beside its
   reconnect; Tizen's wake poll): the playback expiry uses its own `SessionLoop.Timer`. Pinned by
   `SessionLoopTest.eachTimerHoldsItsOwnPendingTask` and `aTimerCancelsOnlyItsOwnTask` (Task 1).
2. **An announcement while connected, waiting or unpaired starts no second connection** (webOS's SSDP
   `reconnectNow`, a wake's `retryIn`). Pinned by `ReconnectorTest.whileConnectedReconnectNowAndRetryInAttemptNothing`,
   `whileAnAttemptWaitsReconnectNowAndRetryInAttemptNothing` (Task 1) and
   `WebOsSessionTest.anAnnouncementWhileConnectedOpensNoSecondConnection` (Task 6).
3. **After `close()`, a command fails as "not connected" and closing again is quiet.** Pinned by
   `CastSessionTest.aCommandAfterCloseIsOfflineAndClosingTwiceIsQuiet` (Task 4).
4. **A state listener that throws never stops a push session's reconnects.** Pinned by
   `CastSessionTest.aStateListenerThatThrowsDoesNotStopReconnecting` (Task 4); Android TV and webOS have theirs
   already.
5. **A Tizen TV that was just woken is polled after the wake grace, not at the next interval.** Pinned by
   `TizenSessionTest.aWokenTvIsPolledAfterTheWakeGraceNotTheNextInterval` (Task 7).

## Plan rulings (where this plan departs from the spec's text)

1. **`SessionRegistry.matching(Predicate<S>)` returns a `List<S>`** instead of the spec's `find` returning an
   `Optional<S>`. The adapters act on every matching session today (`forEach`); a first-match lookup would drop a
   second device registered at the same address.
2. **`SessionLoop.timer()`.** Android TV waits for two things at once: a reconnect and the expiry of what it inferred
   is playing. With one pending-task slot, scheduling either would cancel the other. A `Timer` is a slot of its own;
   `schedule` and `cancelPending` keep using the loop's own timer.
3. **The `Reconnector` knows its phase and gains `stop()`** (PR 1's deferred minor 2): `reconnectNow()` and
   `retryIn()` do nothing while connected, while an attempt waits for the session, or once stopped. Android TV latches
   UNPAIRED from a disconnect as well as from `connect()`, so it needs `stop()`. `STOP` stays final: re-pairing replaces
   the session (`Enrollment` reconnects the device). `ReconnectorTest.retryInWaitsTheGivenTimeInsteadOfTheBackoff`
   starts from `RETRY` instead of `PENDING`, since `retryIn` while an attempt waits now does nothing.
4. **PR 1's other deferred minors that PR 2 builds on are settled in Task 1:** `ConnectionSlot.set` with the current
   connection keeps it open; a `SessionLoop` task that throws is logged. The rest stay deferred.
5. **The play/pause race is fixed in a `PlayPauseToggle` in `adapters.support`, tested under contention.** The spec's
   test ("two concurrent presses send play once and pause once") cannot fail: two racing presses still send one PLAY
   and one PAUSE, but they leave the toggle unflipped, so the third press repeats the second. A stress test of the
   toggle shows it.
6. **No `fix:` commit and no session test for the Tizen double close.** `TextWebSocket.close()` is idempotent, so the
   second close has no effect today. Tizen's channel moves into a `ConnectionSlot` with the rest of the session
   (Task 7), and `ConnectionSlotTest` pins "closed exactly once". Tizen's commit is a `fix:` for its listener failure
   instead.
7. **Two Android TV tests stop using the session as a `RemoteListener`**, which the spec removes:
   `staysConnectingAfterTlsUntilTheRemoteHandshakeCompletes` sends its callback through the attempt's listener,
   captured by the test's opener; `doesNotContinueConnectingAfterAStateListenerThrowsAnError` checks a quiet period
   instead of queueing a sentinel callback.
8. **Refusals read as `DeviceCalls` words them for webOS and Tizen too** ("{name} refused to {what}: {reason}"): the
   spec routes all four sessions through `DeviceCalls`. webOS's "could not {what}: …" and "refused the {button}
   button: …" and Tizen's "{name}: {DIAL reason}" change with it; the tests that pin them assert only the reason.
9. **The CONNECTING counts in `AndroidTvSessionTest` stay.** Every attempt's CONNECTING follows a DISCONNECTED, so
   deduplication still publishes one per attempt; the spec's switch to counting at the fake is not needed.
10. **Tizen's first poll runs through `loop.execute`, the later ones through `loop.every`**, whose first run comes one
    interval after it is set up.

## File structure

| File | Change |
| --- | --- |
| `adapters/support/SessionLoop.java` | `timer()` and `Timer`; tasks that throw are logged |
| `adapters/support/Reconnector.java` | phases, `stop()`, no-ops while connected, waiting or stopped |
| `adapters/support/ConnectionSlot.java` | setting the current connection again keeps it |
| `adapters/support/WakeOnLanPower.java` | new: the "power on" half of webOS and Tizen |
| `adapters/support/LearnedMac.java` | new: stores a reported MAC unless typed or known |
| `adapters/support/SessionRegistry.java` | new: an adapter's open sessions |
| `adapters/support/PlayPauseToggle.java` | new: atomic play/pause alternation |
| `adapters/{webos,tizen,sonos,upnp}/*Adapter.java` | `SessionRegistry` |
| `adapters/cast/CastSession.java` | on the toolkit |
| `adapters/androidtv/AndroidTvSession.java` | on the toolkit; no longer a `RemoteListener` |
| `adapters/webos/WebOsSession.java` | on the toolkit |
| `adapters/tizen/TizenSession.java` | on the toolkit |
| `adapters/net/Backoff.java`, its test | deleted |
| `ArchitectureTest.java` | strict rule: sessions create no executors |
| `docs/dev/testing.md`, `docs/dev/architecture.md` | the toolkit in tests; rule row; measures |

Paths below are under `src/main/java/dev/andre/homecontrol/` and `src/test/java/dev/andre/homecontrol/` unless they
start with `src/` or `docs/`.

## Commits

1. `refactor:` the session toolkit settles what the push sessions need (Task 1)
2. `refactor:` Wake-on-LAN power, MAC learning and one session registry for the TV adapters (Task 2)
3. `fix:` concurrent play/pause presses alternate on webOS and Tizen (Task 3)
4. `refactor:` Cast on the session toolkit (Task 4)
5. `refactor:` Android TV on the session toolkit (Task 5)
6. `refactor:` webOS on the session toolkit (Task 6)
7. `fix:` a Tizen state listener's failure no longer reaches a button press (Task 7)
8. `test:` device sessions create no executors (Task 8)
9. `docs:` testing with the session toolkit (Task 9)

---

### Task 1: The toolkit settles what the push sessions need

**Files:**
- Modify: `src/main/java/dev/andre/homecontrol/adapters/support/SessionLoop.java` (whole file below)
- Modify: `src/main/java/dev/andre/homecontrol/adapters/support/Reconnector.java` (whole file below)
- Modify: `src/main/java/dev/andre/homecontrol/adapters/support/ConnectionSlot.java` (`set`)
- Test: `src/test/java/dev/andre/homecontrol/adapters/support/SessionLoopTest.java`,
  `ReconnectorTest.java`, `ConnectionSlotTest.java`

**Interfaces:**
- Consumes: PR 1's toolkit as merged.
- Produces:
  - `SessionLoop.Timer SessionLoop.timer()`; `boolean SessionLoop.Timer.schedule(Runnable task, Duration delay)`;
    `void SessionLoop.Timer.cancel()`. `SessionLoop.execute`, `schedule`, `cancelPending`, `every`, `close` keep
    their signatures.
  - `void Reconnector.stop()`. `start`, `lost`, `connected`, `reconnectNow`, `retryIn`, `stopped` keep their
    signatures. Phases: TRYING (no connection; an attempt runs or is scheduled), WAITING (`PENDING`), CONNECTED,
    STOPPED. `reconnectNow`/`retryIn` act only in TRYING; `connected()` only in WAITING; `lost()` anywhere but
    STOPPED.
  - `ConnectionSlot.set(c)` with `c` already current: returns true, closes nothing.

- [ ] **Step 1: Write the failing tests**

In `SessionLoopTest`, add the imports `org.junit.jupiter.api.extension.ExtendWith`,
`org.springframework.boot.test.system.CapturedOutput` and `org.springframework.boot.test.system.OutputCaptureExtension`,
and these tests:

```java
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aTaskThatThrowsIsLoggedAndTheLoopGoesOn(CapturedOutput output) {
        AtomicInteger after = new AtomicInteger();

        loop.execute(() -> {
            throw new IllegalStateException("a task bug");
        });
        loop.execute(after::incrementAndGet);

        await().atMost(Duration.ofSeconds(2)).until(() -> after.get() == 1 && output.getOut().contains("a task bug"));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void anErrorInATaskIsLoggedAndTheLoopGoesOn(CapturedOutput output) {
        AtomicInteger after = new AtomicInteger();

        loop.schedule(() -> {
            throw new LinkageError("a class that cannot load");
        }, Duration.ZERO);
        await().atMost(Duration.ofSeconds(2)).until(() -> output.getOut().contains("a class that cannot load"));
        loop.execute(after::incrementAndGet);

        await().atMost(Duration.ofSeconds(2)).until(() -> after.get() == 1);
    }

    @Test
    void eachTimerHoldsItsOwnPendingTask() {
        AtomicInteger loopTask = new AtomicInteger();
        AtomicInteger timerTask = new AtomicInteger();
        SessionLoop.Timer timer = loop.timer();

        timer.schedule(timerTask::incrementAndGet, Duration.ofMillis(100));
        loop.schedule(loopTask::incrementAndGet, Duration.ofMillis(50));

        await().atMost(Duration.ofSeconds(2)).until(() -> loopTask.get() == 1 && timerTask.get() == 1);
    }

    @Test
    void aTimerCancelsOnlyItsOwnTask() {
        AtomicInteger loopTask = new AtomicInteger();
        AtomicInteger cancelled = new AtomicInteger();
        SessionLoop.Timer timer = loop.timer();
        loop.schedule(loopTask::incrementAndGet, Duration.ofMillis(100));
        timer.schedule(cancelled::incrementAndGet, Duration.ofMillis(100));

        timer.cancel();

        await().atMost(Duration.ofSeconds(2)).until(() -> loopTask.get() == 1);
        await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2)).until(() -> cancelled.get() == 0);
    }

    @Test
    void aTimerReplacesItsPendingTask() {
        AtomicInteger replaced = new AtomicInteger();
        AtomicInteger replacement = new AtomicInteger();
        SessionLoop.Timer timer = loop.timer();

        timer.schedule(replaced::incrementAndGet, Duration.ofMillis(200));
        timer.schedule(replacement::incrementAndGet, Duration.ofMillis(50));

        await().atMost(Duration.ofSeconds(2)).until(() -> replacement.get() == 1);
        await().during(Duration.ofMillis(400)).atMost(Duration.ofSeconds(2)).until(() -> replaced.get() == 0);
    }

    @Test
    void aClosedLoopsTimerSchedulesNothing() {
        SessionLoop.Timer timer = loop.timer();

        loop.close();

        assertThat(timer.schedule(() -> { }, Duration.ZERO)).isFalse();
    }
```

In `ReconnectorTest`, change `retryInWaitsTheGivenTimeInsteadOfTheBackoff` to start from a scheduled retry (its first
line becomes):

```java
        Reconnector reconnector = reconnector(Duration.ofSeconds(10), Duration.ofSeconds(10), RETRY);
```

and add:

```java
    @Test
    void whileConnectedReconnectNowAndRetryInAttemptNothing() {
        Reconnector reconnector = reconnector(Duration.ofMillis(50), Duration.ofMillis(50));
        reconnector.start();
        await().atMost(Duration.ofSeconds(1)).until(() -> attempts.size() == 1);

        reconnector.reconnectNow();
        reconnector.retryIn(Duration.ofMillis(10));

        await().during(Duration.ofMillis(400)).atMost(Duration.ofSeconds(2)).until(() -> attempts.size() == 1);
    }

    @Test
    void whileAnAttemptWaitsReconnectNowAndRetryInAttemptNothing() {
        Reconnector reconnector = reconnector(Duration.ofMillis(50), Duration.ofMillis(50), PENDING);
        reconnector.start();
        await().atMost(Duration.ofSeconds(1)).until(() -> attempts.size() == 1);

        reconnector.reconnectNow();
        reconnector.retryIn(Duration.ofMillis(10));

        await().during(Duration.ofMillis(400)).atMost(Duration.ofSeconds(2)).until(() -> attempts.size() == 1);
    }

    @Test
    void aPendingAttemptThatConnectsResetsTheBackoffForTheNextLoss() {
        // Three retries grow the backoff to 800 ms; connected() resets it, so the attempt after the loss waits 100 ms.
        Reconnector reconnector = reconnector(Duration.ofMillis(100), Duration.ofSeconds(10), RETRY, RETRY, RETRY,
                PENDING);
        reconnector.start();
        await().atMost(Duration.ofSeconds(3)).until(() -> attempts.size() == 4);

        reconnector.connected();
        reconnector.reconnectNow();
        await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2)).until(() -> attempts.size() == 4);

        reconnector.lost();
        await().atMost(Duration.ofMillis(600)).until(() -> attempts.size() == 5);
    }

    @Test
    void stopEndsAnAttemptAlreadyScheduled() {
        Reconnector reconnector = reconnector(Duration.ofMillis(200), Duration.ofMillis(200), RETRY, RETRY);
        reconnector.start();
        await().atMost(Duration.ofSeconds(1)).until(() -> attempts.size() == 1);

        reconnector.stop();

        await().during(Duration.ofMillis(600)).atMost(Duration.ofSeconds(2)).until(() -> attempts.size() == 1);
        assertThat(reconnector.stopped()).isTrue();
    }
```

In `ConnectionSlotTest`, add:

```java
    @Test
    void settingTheCurrentConnectionAgainKeepsItOpen() {
        Connection connection = new Connection();
        slot.set(connection);

        assertThat(slot.set(connection)).isTrue();

        assertThat(connection.closes).hasValue(0);
        assertThat(slot.current()).containsSame(connection);
    }
```

- [ ] **Step 2: Run them to see them fail**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.adapters.support.*'`
Expected: compilation fails (`timer()`, `SessionLoop.Timer` and `stop()` do not exist). Comment out the four timer
tests and `stopEndsAnAttemptAlreadyScheduled` for a moment and run again: `aTaskThatThrowsIsLoggedAndTheLoopGoesOn`,
`anErrorInATaskIsLoggedAndTheLoopGoesOn`, `whileConnectedReconnectNowAndRetryInAttemptNothing`,
`whileAnAttemptWaitsReconnectNowAndRetryInAttemptNothing`, `aPendingAttemptThatConnectsResetsTheBackoffForTheNextLoss`
and `settingTheCurrentConnectionAgainKeepsItOpen` FAIL. Restore the commented tests.

- [ ] **Step 3: Write the implementation**

```java
// File: src/main/java/dev/andre/homecontrol/adapters/support/SessionLoop.java
package dev.andre.homecontrol.adapters.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * A session's one thread, a virtual one. A one-shot task waits in a {@link Timer}, which holds at most one: the loop's
 * own ({@link #schedule}), or one a session takes for a second wait ({@link #timer()}). Once {@link #close()} has run,
 * every call is a no-op that returns false: a caller racing close() never sees a {@link RejectedExecutionException}.
 * A task that throws is logged with the loop's name: a {@link RuntimeException} as a warning, and the loop goes on; an
 * {@link Error} as an error, and it still ends that task (a periodic one for good).
 */
public final class SessionLoop implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SessionLoop.class);

    private final String name;
    private final ScheduledExecutorService executor;
    private final Timer pending = new Timer();
    private volatile boolean closed;

    public SessionLoop(String name) {
        this.name = name;
        this.executor = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name(name).factory());
    }

    public boolean execute(Runnable task) {
        if (closed) {
            return false;
        }
        try {
            executor.execute(logged(task));
            return true;
        } catch (RejectedExecutionException _) {
            return false;
        }
    }

    /** Runs {@code task} after {@code delay} as the loop's pending task, replacing the one scheduled before. */
    public boolean schedule(Runnable task, Duration delay) {
        return pending.schedule(task, delay);
    }

    /** Drops the loop's pending task if it has not started. */
    public void cancelPending() {
        pending.cancel();
    }

    /** A pending-task slot of its own, for a session that waits for two things at once. */
    public Timer timer() {
        return new Timer();
    }

    /** Runs {@code task} every {@code interval} after the previous run ends; a failed run is logged, the next still comes. */
    public boolean every(Runnable task, Duration interval) {
        if (closed) {
            return false;
        }
        try {
            executor.scheduleWithFixedDelay(logged(task), interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (RejectedExecutionException _) {
            return false;
        }
    }

    /** Cancels everything and interrupts a task that is running. */
    @Override
    public void close() {
        closed = true;
        executor.shutdownNow();
    }

    @SuppressWarnings("java:S1181") // an Error is logged and rethrown: it still ends the task
    private Runnable logged(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                log.warn("{}: a task failed", name, e);
            } catch (Error e) {
                log.error("{}: a task failed", name, e);
                throw e;
            }
        };
    }

    /** At most one pending one-shot task on this loop: scheduling another replaces it. */
    public final class Timer {

        private final Object lock = new Object();
        private ScheduledFuture<?> task; // guarded by lock

        private Timer() {
        }

        public boolean schedule(Runnable next, Duration delay) {
            synchronized (lock) {
                cancelLocked();
                if (closed) {
                    return false;
                }
                try {
                    task = executor.schedule(logged(next), delay.toMillis(), TimeUnit.MILLISECONDS);
                    return true;
                } catch (RejectedExecutionException _) {
                    return false;
                }
            }
        }

        /** Drops the pending task if it has not started. */
        public void cancel() {
            synchronized (lock) {
                cancelLocked();
            }
        }

        private void cancelLocked() {
            if (task != null) {
                task.cancel(false);
                task = null;
            }
        }
    }
}
```

```java
// File: src/main/java/dev/andre/homecontrol/adapters/support/Reconnector.java
package dev.andre.homecontrol.adapters.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Drives a push-style session's {@code connect()} on its {@link SessionLoop}. The first attempt runs at once.
 * {@code RETRY} tries again after the backoff's next delay; {@code CONNECTED} resets the backoff; {@code PENDING} waits
 * for the session to call {@link #connected()} or {@link #lost()}; {@code STOP}, like {@link #stop()}, ends the
 * attempts for good: a session that must start over, after a new pairing, is replaced by its owner. While connected,
 * while an attempt waits, or once stopped, {@link #reconnectNow()} and {@link #retryIn} do nothing. The session's
 * policies, such as when to stop, stay in its {@code connect()}.
 */
public final class Reconnector {

    public enum Outcome { CONNECTED, PENDING, RETRY, STOP }

    /** TRYING: no connection; an attempt runs or is scheduled. WAITING: an attempt returned {@code PENDING}. */
    private enum Phase { TRYING, WAITING, CONNECTED, STOPPED }

    private static final Logger log = LoggerFactory.getLogger(Reconnector.class);

    private final SessionLoop loop;
    private final Backoff backoff;
    private final Supplier<Outcome> connect;
    private volatile Phase phase = Phase.TRYING;

    public Reconnector(SessionLoop loop, Backoff backoff, Supplier<Outcome> connect) {
        this.loop = loop;
        this.backoff = backoff;
        this.connect = connect;
    }

    public void start() {
        loop.execute(this::attempt);
    }

    /** The connection dropped, or a pending attempt failed: try again after the backoff's next delay. */
    public void lost() {
        if (phase == Phase.STOPPED) {
            return;
        }
        phase = Phase.TRYING;
        loop.schedule(this::attempt, backoff.next());
    }

    /** A {@code PENDING} attempt became usable. */
    public void connected() {
        if (phase == Phase.WAITING) {
            phase = Phase.CONNECTED;
            backoff.reset();
        }
    }

    /** Ends the attempts for good: the session found that connecting again cannot help. */
    public void stop() {
        phase = Phase.STOPPED;
        loop.cancelPending();
    }

    /** Tries at once with a fresh backoff, unless connected, waiting or stopped. */
    public void reconnectNow() {
        if (phase != Phase.TRYING) {
            return;
        }
        backoff.reset();
        loop.execute(() -> {
            if (phase == Phase.TRYING) {
                loop.cancelPending();
                attempt();
            }
        });
    }

    /** Tries after {@code wait} with a fresh backoff, unless connected, waiting or stopped: a TV that was just woken. */
    public void retryIn(Duration wait) {
        if (phase != Phase.TRYING) {
            return;
        }
        backoff.reset();
        loop.schedule(this::attempt, wait);
    }

    public boolean stopped() {
        return phase == Phase.STOPPED;
    }

    private void attempt() {
        if (phase != Phase.TRYING) {
            return;
        }
        Outcome outcome;
        try {
            outcome = connect.get();
        } catch (RuntimeException e) {
            log.warn("A connection attempt failed unexpectedly", e);
            outcome = Outcome.RETRY;
        }
        if (phase == Phase.STOPPED) {
            return; // the session stopped while it was connecting
        }
        switch (outcome) {
            case CONNECTED -> {
                phase = Phase.CONNECTED;
                backoff.reset();
            }
            case PENDING -> phase = Phase.WAITING;
            case RETRY -> loop.schedule(this::attempt, backoff.next());
            case STOP -> phase = Phase.STOPPED;
        }
    }
}
```

In `ConnectionSlot`, `set` keeps the current connection:

```java
    /**
     * Makes {@code connection} the current one and closes the one before, unless it is the same; false, and it is
     * closed, once the slot is.
     */
    public boolean set(C connection) {
        C givenUp;
        boolean accepted;
        synchronized (this) {
            if (!closed && current == connection) {
                return true;
            }
            accepted = !closed;
            givenUp = accepted ? current : connection;
            if (accepted) {
                current = connection;
            }
        }
        closeQuietly(givenUp);
        return accepted;
    }
```

- [ ] **Step 4: Run the tests to see them pass**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.adapters.support.*'`
Expected: PASS.

- [ ] **Step 5: Run the build**

Run: `scripts/gradle.sh build`
Expected: green (Sonos, UPnP, Bluetooth and the `ReconnectingPoller` use `schedule`, `execute` and `every`
unchanged).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/adapters/support/SessionLoop.java \
  src/main/java/dev/andre/homecontrol/adapters/support/Reconnector.java \
  src/main/java/dev/andre/homecontrol/adapters/support/ConnectionSlot.java \
  src/test/java/dev/andre/homecontrol/adapters/support/SessionLoopTest.java \
  src/test/java/dev/andre/homecontrol/adapters/support/ReconnectorTest.java \
  src/test/java/dev/andre/homecontrol/adapters/support/ConnectionSlotTest.java
git commit -F <message file>
```

Message:

```
refactor: the session toolkit settles what the push sessions need

Before Cast, Android TV, webOS and Tizen move onto adapters.support:
- Reconnector knows its phase. reconnectNow() and retryIn() do nothing while connected, while an
  attempt waits for the session, or once stopped, and stop() ends the attempts from outside.
- SessionLoop.timer() gives a session a second pending-task slot, so Android TV's playback expiry
  and its reconnect never cancel each other.
- A SessionLoop task that throws is logged: a RuntimeException as a warning, an Error as an error.
- Setting the current connection into a ConnectionSlot again keeps it open.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Q6dsbK16wqsU1DrkTAV32K
```

---

### Task 2: Wake-on-LAN power, MAC learning and one session registry

**Files:**
- Create: `src/main/java/dev/andre/homecontrol/adapters/support/WakeOnLanPower.java`
- Create: `src/main/java/dev/andre/homecontrol/adapters/support/LearnedMac.java`
- Create: `src/main/java/dev/andre/homecontrol/adapters/support/SessionRegistry.java`
- Modify: `src/main/java/dev/andre/homecontrol/adapters/webos/WebOsAdapter.java`,
  `tizen/TizenAdapter.java`, `sonos/SonosAdapter.java`, `upnp/UpnpAdapter.java`
- Test: `src/test/java/dev/andre/homecontrol/adapters/support/WakeOnLanPowerTest.java`,
  `LearnedMacTest.java`, `SessionRegistryTest.java`

**Interfaces:**
- Consumes: `adapters.net.WakeOnLan.wake(String mac) throws IOException`; `core.WakeOnLanSettings.MAC_ADDRESS`,
  `MAC_ADDRESS_MANUAL`; `core.LearnedSettings.store(Map<String, String>)`.
- Produces (used by Tasks 6 and 7):
  - `new WakeOnLanPower(String deviceName, Supplier<Device> device, String adapterId, WakeOnLan wakeOnLan)`;
    `void wake()` throws `DeviceOfflineException` with today's two messages.
  - `new LearnedMac(Supplier<Device> device, String adapterId, LearnedSettings learned)`; `void offer(String mac)`.
  - `new SessionRegistry<S>()`; `S open(String deviceId, Function<Runnable, S> create)`;
    `List<S> matching(Predicate<S> matches)`.

- [ ] **Step 1: Write the failing tests**

```java
// File: src/test/java/dev/andre/homecontrol/adapters/support/WakeOnLanPowerTest.java
package dev.andre.homecontrol.adapters.support;

import dev.andre.homecontrol.adapters.net.FakeWakeOnLanReceiver;
import dev.andre.homecontrol.adapters.net.WakeOnLan;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.WakeOnLanSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WakeOnLanPowerTest {

    private static final String MAC = "A8:23:FE:01:02:03";

    private FakeWakeOnLanReceiver receiver;
    private final AtomicReference<Device> device = new AtomicReference<>(tv(Map.of()));

    @BeforeEach
    void startReceiver() throws IOException {
        receiver = new FakeWakeOnLanReceiver();
    }

    @AfterEach
    void stopReceiver() {
        receiver.close();
    }

    private static Device tv(Map<String, String> settings) {
        return new Device("lg", "LG TV", DeviceKind.WEBOS, "192.0.2.10", Map.of("webos", settings), Instant.now());
    }

    private WakeOnLanPower power() {
        return new WakeOnLanPower("LG TV", device::get, "webos", new WakeOnLan(receiver.address()));
    }

    @Test
    void sendsTheMagicPacketToTheMacInTheSettings() throws Exception {
        device.set(tv(Map.of(WakeOnLanSettings.MAC_ADDRESS, MAC)));

        power().wake();

        assertThat(receiver.nextPacket()).containsExactly(WakeOnLan.magicPacket(MAC));
    }

    @Test
    void readsTheSettingsWhenWakingNotWhenBuilt() throws Exception {
        WakeOnLanPower power = power();
        device.set(tv(Map.of(WakeOnLanSettings.MAC_ADDRESS, MAC)));

        power.wake();

        assertThat(receiver.nextPacket()).containsExactly(WakeOnLan.magicPacket(MAC));
    }

    @Test
    void withoutAMacItExplainsTheFixAndSendsNothing() {
        WakeOnLanPower power = power();

        assertThatThrownBy(power::wake)
                .isInstanceOf(DeviceOfflineException.class)
                .hasMessage("LG TV is off and no MAC address is known for Wake-on-LAN; switch it on once by hand or"
                        + " enter its MAC address on the setup page");
        assertThat(receiver.received()).isZero();
    }

    @Test
    void aPacketThatCannotBeBuiltIsAnOfflineDeviceWithTheReason() {
        device.set(tv(Map.of(WakeOnLanSettings.MAC_ADDRESS, "not a MAC")));
        WakeOnLanPower power = power();

        assertThatThrownBy(power::wake)
                .isInstanceOf(DeviceOfflineException.class)
                .hasMessageStartingWith("Could not send the Wake-on-LAN packet: ");
    }
}
```

```java
// File: src/test/java/dev/andre/homecontrol/adapters/support/LearnedMacTest.java
package dev.andre.homecontrol.adapters.support;

import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.WakeOnLanSettings;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class LearnedMacTest {

    private static final String REPORTED = "70:2A:D5:01:02:03";

    private final List<Map<String, String>> stored = new CopyOnWriteArrayList<>();

    private LearnedMac learnedMac(Map<String, String> settings) {
        Device tv = new Device("samsung", "Samsung TV", DeviceKind.TIZEN, "192.0.2.20", Map.of("tizen", settings),
                Instant.now());
        return new LearnedMac(() -> tv, "tizen", stored::add);
    }

    @Test
    void storesTheMacADeviceReports() {
        learnedMac(Map.of()).offer(REPORTED);

        assertThat(stored).containsExactly(Map.of(WakeOnLanSettings.MAC_ADDRESS, REPORTED));
    }

    @Test
    void replacesALearnedMacThatChanged() {
        learnedMac(Map.of(WakeOnLanSettings.MAC_ADDRESS, "70:2A:D5:09:09:09")).offer(REPORTED);

        assertThat(stored).containsExactly(Map.of(WakeOnLanSettings.MAC_ADDRESS, REPORTED));
    }

    @Test
    void storesNothingWhenTheMacIsAlreadyKnown() {
        learnedMac(Map.of(WakeOnLanSettings.MAC_ADDRESS, REPORTED)).offer(REPORTED);

        assertThat(stored).isEmpty();
    }

    @Test
    void neverReplacesAHandEnteredMac() {
        learnedMac(Map.of(WakeOnLanSettings.MAC_ADDRESS, "11:22:33:44:55:66",
                WakeOnLanSettings.MAC_ADDRESS_MANUAL, "true")).offer(REPORTED);

        assertThat(stored).isEmpty();
    }
}
```

```java
// File: src/test/java/dev/andre/homecontrol/adapters/support/SessionRegistryTest.java
package dev.andre.homecontrol.adapters.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SessionRegistryTest {

    /** Runs the registry's callback when it closes, as a real session does. */
    private record Session(String host, Runnable onClose) {
        void close() {
            onClose.run();
        }
    }

    private final SessionRegistry<Session> sessions = new SessionRegistry<>();

    @Test
    void anOpenSessionIsFoundByWhatItAnswersTo() {
        Session kitchen = sessions.open("tv-1", onClose -> new Session("192.0.2.10", onClose));
        sessions.open("tv-2", onClose -> new Session("192.0.2.20", onClose));

        assertThat(sessions.matching(session -> session.host().equals("192.0.2.10"))).containsExactly(kitchen);
    }

    @Test
    void aClosedSessionIsGone() {
        Session session = sessions.open("tv-1", onClose -> new Session("192.0.2.10", onClose));

        session.close();

        assertThat(sessions.matching(any -> true)).isEmpty();
    }

    @Test
    void aReplacedSessionThatClosesLaterLeavesItsReplacement() {
        Session replaced = sessions.open("tv-1", onClose -> new Session("192.0.2.10", onClose));
        Session replacement = sessions.open("tv-1", onClose -> new Session("192.0.2.10", onClose));

        replaced.close();

        assertThat(sessions.matching(any -> true)).containsExactly(replacement);
    }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.adapters.support.*'`
Expected: compilation fails: `WakeOnLanPower`, `LearnedMac` and `SessionRegistry` do not exist.

- [ ] **Step 3: Write the implementation**

```java
// File: src/main/java/dev/andre/homecontrol/adapters/support/WakeOnLanPower.java
package dev.andre.homecontrol.adapters.support;

import dev.andre.homecontrol.adapters.net.WakeOnLan;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.WakeOnLanSettings;

import java.io.IOException;
import java.util.function.Supplier;

/**
 * Switches a TV on with a Wake-on-LAN packet to the MAC address in its adapter settings, read when it wakes: the user
 * may have typed it in meanwhile. Switching off stays with each protocol.
 */
public final class WakeOnLanPower {

    private final String deviceName;
    private final Supplier<Device> device;
    private final String adapterId;
    private final WakeOnLan wakeOnLan;

    public WakeOnLanPower(String deviceName, Supplier<Device> device, String adapterId, WakeOnLan wakeOnLan) {
        this.deviceName = deviceName;
        this.device = device;
        this.adapterId = adapterId;
        this.wakeOnLan = wakeOnLan;
    }

    /** Sends the packet; with no MAC address known, or a packet that cannot be sent, the device counts as offline. */
    public void wake() {
        String mac = device.get().adapterSettings(adapterId).get(WakeOnLanSettings.MAC_ADDRESS);
        if (mac == null || mac.isBlank()) {
            throw new DeviceOfflineException(deviceName + " is off and no MAC address is known for Wake-on-LAN;"
                    + " switch it on once by hand or enter its MAC address on the setup page");
        }
        try {
            wakeOnLan.wake(mac);
        } catch (IOException | IllegalArgumentException e) {
            throw new DeviceOfflineException("Could not send the Wake-on-LAN packet: " + e.getMessage());
        }
    }
}
```

```java
// File: src/main/java/dev/andre/homecontrol/adapters/support/LearnedMac.java
package dev.andre.homecontrol.adapters.support;

import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.LearnedSettings;
import dev.andre.homecontrol.core.WakeOnLanSettings;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Keeps the MAC address a TV reports, for Wake-on-LAN: stored through {@link LearnedSettings} unless the user typed
 * one in or it is already known. The settings are read from the device itself, never from its pairing secrets.
 */
public final class LearnedMac {

    private final Supplier<Device> device;
    private final String adapterId;
    private final LearnedSettings learned;

    public LearnedMac(Supplier<Device> device, String adapterId, LearnedSettings learned) {
        this.device = device;
        this.adapterId = adapterId;
        this.learned = learned;
    }

    public void offer(String mac) {
        Map<String, String> settings = device.get().adapterSettings(adapterId);
        if ("true".equals(settings.get(WakeOnLanSettings.MAC_ADDRESS_MANUAL))
                || mac.equals(settings.get(WakeOnLanSettings.MAC_ADDRESS))) {
            return;
        }
        learned.store(Map.of(WakeOnLanSettings.MAC_ADDRESS, mac));
    }
}
```

```java
// File: src/main/java/dev/andre/homecontrol/adapters/support/SessionRegistry.java
package dev.andre.homecontrol.adapters.support;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;

/** An adapter's open sessions by device id, for finding the ones a discovery announcement is about. */
public final class SessionRegistry<S> {

    private final Map<String, S> sessions = new ConcurrentHashMap<>();

    /**
     * Creates the session for {@code deviceId} and registers it in place of any before it. {@code create} gets the
     * callback the session runs when it closes; it removes exactly this session, never one opened after it.
     */
    public S open(String deviceId, Function<Runnable, S> create) {
        AtomicReference<S> self = new AtomicReference<>();
        S session = create.apply(() -> sessions.remove(deviceId, self.get()));
        self.set(session);
        sessions.put(deviceId, session);
        return session;
    }

    public List<S> matching(Predicate<S> matches) {
        return sessions.values().stream().filter(matches).toList();
    }
}
```

The adapters use it. In `WebOsAdapter`:
- the field becomes `private final SessionRegistry<WebOsSession> sessions = new SessionRegistry<>();`
- the SSDP listener becomes:

```java
        ssdp.addListener(SEARCH_TARGET, service -> sessions
                .matching(session -> session.host().equalsIgnoreCase(service.address()))
                .forEach(WebOsSession::reconnectNow));
```

- `connect(Device, Consumer<DeviceState>, LearnedSettings)` becomes:

```java
    @Override
    public DeviceHandle connect(Device device, Consumer<DeviceState> onChange, LearnedSettings learned) {
        WebOsSession session = sessions.open(device.id(), onClose -> new WebOsSession(device, properties,
                WebOsTimings.from(properties), http, registry, learned, secrets, wakeOnLan, onChange, onClose));
        session.start();
        return session;
    }
```

- imports: add `dev.andre.homecontrol.adapters.support.SessionRegistry`; remove `java.util.Map` if unused,
  `java.util.concurrent.ConcurrentHashMap` and `java.util.concurrent.atomic.AtomicReference`.

In `TizenAdapter` the same, with `TizenSession`, `TizenSession::pollNow` and:

```java
    @Override
    public DeviceHandle connect(Device device, Consumer<DeviceState> onChange, LearnedSettings learned) {
        TizenSession session = sessions.open(device.id(), onClose -> new TizenSession(device, properties,
                TizenTimings.from(properties), http, registry, learned, secrets, wakeOnLan, onChange, onClose));
        session.start();
        return session;
    }
```

In `SonosAdapter`:

```java
    private final SessionRegistry<SonosSession> sessions = new SessionRegistry<>();
```
```java
        discovery.onAlive(uuid -> sessions.matching(session -> uuid != null && uuid.equals(session.uuid()))
                .forEach(SonosSession::reconnectNow));
```
```java
    @Override
    public DeviceHandle connect(Device device, Consumer<DeviceState> onChange) {
        SonosSession session = sessions.open(device.id(),
                onClose -> new SonosSession(device, properties, http, onChange, onClose));
        session.start();
        return session;
    }
```

In `UpnpAdapter`:

```java
    private final SessionRegistry<UpnpSession> sessions = new SessionRegistry<>();
```
```java
        discovery.onAlive(udn -> sessions.matching(session -> udn != null && udn.equalsIgnoreCase(session.udn()))
                .forEach(UpnpSession::reconnectNow));
```
```java
    @Override
    public DeviceHandle connect(Device device, Consumer<DeviceState> onChange) {
        // Only announcements from the registered address may point the session at a (new) description port.
        UpnpSession session = sessions.open(device.id(), onClose -> new UpnpSession(device, properties, http,
                udn -> discovery.location(udn, device.host()), onChange, onClose));
        session.start();
        return session;
    }
```

Remove the imports each adapter no longer uses (`ConcurrentHashMap`, `AtomicReference`, and `Map` where nothing
else uses it).

- [ ] **Step 4: Run the tests to see them pass**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.adapters.*'`
Expected: PASS, including `WebOsAdapterTest`, `TizenAdapterTest`, `SonosAdapterTest` and `UpnpAdapterTest`.

- [ ] **Step 5: Run the build**

Run: `scripts/gradle.sh build`
Expected: green.

- [ ] **Step 6: Commit**

Stage the three new classes, their three tests and the four adapters. Message:

```
refactor: Wake-on-LAN power, MAC learning and one session registry for the TV adapters

webOS and Tizen switched a TV on and learned its MAC address with the same code, and webOS, Tizen,
Sonos and UPnP kept the same map of open sessions. WakeOnLanPower, LearnedMac and SessionRegistry
in adapters.support replace the copies. The sessions move onto the first two with the rest of the
toolkit; the four adapters use the registry now. LearnedMac reads the MAC settings from the device
itself, so Tizen will no longer resolve its pairing key on every poll to check them.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Q6dsbK16wqsU1DrkTAV32K
```

---

### Task 3: Concurrent play/pause presses alternate

**Files:**
- Create: `src/main/java/dev/andre/homecontrol/adapters/support/PlayPauseToggle.java`
- Modify: `src/main/java/dev/andre/homecontrol/adapters/webos/WebOsSession.java`,
  `src/main/java/dev/andre/homecontrol/adapters/tizen/TizenSession.java`
- Test: `src/test/java/dev/andre/homecontrol/adapters/support/PlayPauseToggleTest.java`

**Interfaces:**
- Produces: `new PlayPauseToggle()`; `boolean playNext()`: false for the first press (pause), then alternating.
  Tasks 6 and 7 keep using it.

- [ ] **Step 1: Write the failing test, and move today's toggle into the new class unchanged**

```java
// File: src/test/java/dev/andre/homecontrol/adapters/support/PlayPauseToggleTest.java
package dev.andre.homecontrol.adapters.support;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PlayPauseToggleTest {

    @Test
    void pressesAlternateStartingWithPause() {
        PlayPauseToggle toggle = new PlayPauseToggle();

        assertThat(toggle.playNext()).isFalse();
        assertThat(toggle.playNext()).isTrue();
        assertThat(toggle.playNext()).isFalse();
    }

    @Test
    void concurrentPressesStillAlternate() throws Exception {
        // A press that reads the toggle while another flips it leaves it unflipped, and the next press repeats the
        // command before it. Under contention that happens many times over.
        PlayPauseToggle toggle = new PlayPauseToggle();
        int threads = 8;
        int pressesEach = 100_000;
        AtomicInteger plays = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pressers = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                pressers.execute(() -> {
                    try {
                        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException _) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int i = 0; i < pressesEach; i++) {
                        if (toggle.playNext()) {
                            plays.incrementAndGet();
                        }
                    }
                });
            }
            start.countDown();
        }

        assertThat(plays).hasValue(threads * pressesEach / 2);
        assertThat(toggle.playNext()).isFalse();
    }
}
```

The class, first with the expression webOS and Tizen use today:

```java
// File: src/main/java/dev/andre/homecontrol/adapters/support/PlayPauseToggle.java
package dev.andre.homecontrol.adapters.support;

import java.util.concurrent.atomic.AtomicBoolean;

/** The play/pause key of a device that takes separate play and pause commands: presses alternate, pause first. */
public final class PlayPauseToggle {

    private final AtomicBoolean playNext = new AtomicBoolean();

    /** Whether this press sends play; otherwise it sends pause. */
    public boolean playNext() {
        return playNext.getAndSet(!playNext.get());
    }
}
```

- [ ] **Step 2: Run the test to see it fail**

Run: `scripts/gradle.sh test --tests dev.andre.homecontrol.adapters.support.PlayPauseToggleTest`
Expected: `concurrentPressesStillAlternate` FAILS (the play count is off from 400000, or the last press plays). If a
run passes by chance (the lost flips can cancel out), run it again; it fails on nearly every run.

- [ ] **Step 3: Make the flip atomic, and use the toggle in webOS and Tizen**

In `PlayPauseToggle.playNext()`:

```java
    /** Whether this press sends play; otherwise it sends pause. One atomic flip, so concurrent presses alternate too. */
    public boolean playNext() {
        return playNext.getAndUpdate(play -> !play);
    }
```

In `WebOsSession`: replace the field `private final AtomicBoolean nextPlayPauseIsPlay = new AtomicBoolean();` with
`private final PlayPauseToggle playPause = new PlayPauseToggle();`, the `PLAY_PAUSE` arm with
`case PLAY_PAUSE -> button(playPause.playNext() ? "PLAY" : "PAUSE");`, add the import
`dev.andre.homecontrol.adapters.support.PlayPauseToggle` and remove `java.util.concurrent.atomic.AtomicBoolean`.

In `TizenSession` the same, with `case PLAY_PAUSE -> sendKey(playPause.playNext() ? "KEY_PLAY" : "KEY_PAUSE");`.

- [ ] **Step 4: Run the tests to see them pass**

Run: `scripts/gradle.sh test --tests dev.andre.homecontrol.adapters.support.PlayPauseToggleTest --tests dev.andre.homecontrol.adapters.webos.WebOsSessionTest --tests dev.andre.homecontrol.adapters.tizen.TizenSessionTest`
Expected: PASS (`playPauseAlternatesPauseAndPlay` in both session tests still sees PAUSE, then PLAY).

- [ ] **Step 5: Run the build**

Run: `scripts/gradle.sh build`
Expected: green.

- [ ] **Step 6: Commit**

Stage `PlayPauseToggle.java`, `PlayPauseToggleTest.java`, `WebOsSession.java` and `TizenSession.java`. Message:

```
fix: concurrent play/pause presses alternate on webOS and Tizen

The play/pause key read its toggle and then set it, so a press that came in between left the
toggle unflipped and the next press repeated the command before it: PAUSE, PLAY, PLAY. Both TVs
now take the next command from one PlayPauseToggle, which flips atomically.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Q6dsbK16wqsU1DrkTAV32K
```

---

### Task 4: Cast on the session toolkit

**Files:**
- Modify: `src/main/java/dev/andre/homecontrol/adapters/cast/CastSession.java` (whole file below)
- Test: `src/test/java/dev/andre/homecontrol/adapters/cast/CastSessionTest.java`

**Interfaces:**
- Consumes: `SessionLoop` (`execute`, `every`, `close`), `Reconnector` (`start`, `lost`; outcomes `CONNECTED`,
  `RETRY`, `STOP`), `StatePublisher` (`current`, `update`, `close`), `ConnectionSlot<CastConnection>` (`set`,
  `current`, `takeIf`, `close`), `Backoff`, `DeviceCalls` (`run`, `notConnected`) from Task 1 and PR 1.
- Produces: nothing new. `CastSession`'s constructors, `start()`, `state()`, `execute`, `query` and `close()` keep
  their signatures; `CastAdapter` is unchanged.

Behaviour that changes: the loop is a virtual thread; a state that changes nothing visible is not published again;
a command whose connection fails says "{name} could not be reached to {what}" instead of "{name} dropped the
connection while trying to {what}". The generation counter goes: a callback runs only while its connection is the
one in the slot.

- [ ] **Step 1: Write the tests**

Add to `CastSessionTest` (with the import `dev.andre.homecontrol.core.Action` if it is not there yet):

```java
    @Test
    void aStatusThatChangesNothingIsNotPublishedAgain() throws Exception {
        start(receiver.port());
        awaitStatus();
        int published = seen.all().size();

        receiver.pushReceiverStatus();
        receiver.setVolume(0.8, false);
        receiver.pushReceiverStatus();

        await().until(() -> session.state().volumeLevel() == 80);
        assertThat(seen.all()).hasSize(published + 1);
    }

    @Test
    void aCommandAfterCloseIsOfflineAndClosingTwiceIsQuiet() {
        start(receiver.port());
        awaitStatus();

        session.close();
        session.close();

        var volume = new Action.SetVolume(30);
        assertThatThrownBy(() -> session.execute(volume))
                .isInstanceOf(DeviceOfflineException.class)
                .hasMessage("Living Room TV is not connected");
    }

    @Test
    void aStateListenerThatThrowsDoesNotStopReconnecting() throws Exception {
        session = new CastSession(device(receiver.port()), TIMINGS, state -> {
            seen.accept(state);
            throw new IllegalStateException("a subscriber failed");
        });
        session.start();
        awaitStatus();

        receiver.dropConnection();

        await().until(() -> receiver.connections() == 2 && session.state().connected());
    }
```

- [ ] **Step 2: Run them**

Run: `scripts/gradle.sh test --tests dev.andre.homecontrol.adapters.cast.CastSessionTest`
Expected: `aStatusThatChangesNothingIsNotPublishedAgain` FAILS (today every receiver status is published, so the
count is `published + 2`). The other two pass: they pin behaviour the move must keep (Review Focus 3 and 4).

- [ ] **Step 3: Write the implementation**

```java
// File: src/main/java/dev/andre/homecontrol/adapters/cast/CastSession.java
package dev.andre.homecontrol.adapters.cast;

import dev.andre.homecontrol.adapters.cast.protocol.CastConnection;
import dev.andre.homecontrol.adapters.cast.protocol.CastDisconnectCause;
import dev.andre.homecontrol.adapters.cast.protocol.CastIncoming;
import dev.andre.homecontrol.adapters.cast.protocol.CastPayloads;
import dev.andre.homecontrol.adapters.cast.protocol.MediaStatus;
import dev.andre.homecontrol.adapters.cast.protocol.ReceiverStatus;
import dev.andre.homecontrol.adapters.net.DeviceTimeoutException;
import dev.andre.homecontrol.adapters.support.Backoff;
import dev.andre.homecontrol.adapters.support.ConnectionSlot;
import dev.andre.homecontrol.adapters.support.DeviceCalls;
import dev.andre.homecontrol.adapters.support.Reconnector;
import dev.andre.homecontrol.adapters.support.SessionLoop;
import dev.andre.homecontrol.adapters.support.StatePublisher;
import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.CastAppQuery;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.NowPlaying;
import dev.andre.homecontrol.core.PlaybackState;
import dev.andre.homecontrol.core.ReceiverApps;
import dev.andre.homecontrol.core.UnsupportedActionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import static dev.andre.homecontrol.adapters.cast.protocol.CastNamespaces.CONNECTION;
import static dev.andre.homecontrol.adapters.cast.protocol.CastNamespaces.MEDIA;
import static dev.andre.homecontrol.adapters.cast.protocol.CastNamespaces.PLATFORM_RECEIVER_ID;
import static dev.andre.homecontrol.adapters.cast.protocol.CastNamespaces.RECEIVER;

/**
 * One Cast receiver's live connection. Connection, receiver and media state change only on the session's loop, and
 * the {@link Reconnector} retries with a growing backoff. Commands run on the caller's thread and fail at once when
 * they cannot be sent (nothing is queued). The session follows the media channel of whichever app is in front, so
 * casts started from a phone show up as now playing too. Cast has no pairing, so there is no UNPAIRED state.
 */
public class CastSession implements DeviceHandle, ReceiverApps {

    private static final String RECEIVER_STATUS_TYPE = "RECEIVER_STATUS";
    private static final String REACH_PREFIX = "reach ";
    private static final String STATUS_FIELD = "status";
    private static final Set<String> CUSTOM_ERROR_TYPES = Set.of("error", "connectionerror", "playbackerror");

    private static final Logger log = LoggerFactory.getLogger(CastSession.class);

    private final Device device;
    private final CastSettings settings;
    private final CastTimings timings;
    private final StatePublisher publisher;
    private final SessionLoop loop;
    private final Reconnector reconnector;
    private final ConnectionSlot<CastConnection> connection;

    // Immutable record replaced wholesale on the loop; command threads only read it.
    @SuppressWarnings("java:S3077")
    private volatile ReceiverStatus receiver;
    /** Transport of the foreground app whose media channel we follow, or null. */
    private volatile String mediaTransportId;
    /** The last media status, so partial statuses (without {@code media}) keep their title. Loop thread only. */
    private MediaStatus lastMedia;

    public CastSession(Device device, CastProperties properties, Consumer<DeviceState> onChange) {
        this(device, CastTimings.from(properties), onChange);
    }

    CastSession(Device device, CastTimings timings, Consumer<DeviceState> onChange) {
        this.device = device;
        this.settings = CastSettings.of(device);
        this.timings = timings;
        this.publisher = new StatePublisher(device.id(), DeviceState.initial(), onChange);
        this.loop = new SessionLoop("cast-session-" + device.id());
        this.reconnector = new Reconnector(loop, new Backoff(timings.reconnectInitialDelay(),
                timings.reconnectMaxDelay()), this::connect);
        this.connection = new ConnectionSlot<>("cast-session-" + device.id());
    }

    public void start() {
        reconnector.start();
        loop.every(this::pollMediaPosition, timings.mediaStatusInterval());
    }

    @Override
    public DeviceState state() {
        return publisher.current();
    }

    @Override
    public void execute(Action action) {
        switch (action) {
            case Action.PressKey _ -> throw new UnsupportedActionException(
                    device.name() + " is a Cast receiver and has no remote keys");
            case Action.OpenAppLink _ -> throw new UnsupportedActionException(
                    device.name() + " is a Cast receiver and cannot open app links");
            case Action.SetVolume(var level) -> receiverCommand(CastPayloads.setVolumeLevel(level / 100.0), "set the volume");
            case Action.Mute(var muted) -> receiverCommand(CastPayloads.setMuted(muted), muted ? "mute" : "unmute");
            case Action.Stop _ -> stopForegroundApp();
            case Action.CastLoad(var receiverAppId, var body) -> load(receiverAppId, body);
            case Action.CastMessage(var receiverAppId, var namespace, var body) -> customMessage(receiverAppId, namespace, body);
            case Action.SelectInput _ -> throw new UnsupportedActionException(
                    device.name() + " is a Cast receiver and has no inputs");
            case Action.PlayMedia _ -> throw new UnsupportedActionException(device.name() + " cannot play a direct stream");
            case Action.Pause _ -> throw new UnsupportedActionException(device.name() + " cannot pause a direct stream");
            case Action.Resume _ -> throw new UnsupportedActionException(device.name() + " cannot resume a direct stream");
            case Action.JoinGroup _ -> throw new UnsupportedActionException(device.name() + " cannot be grouped");
            case Action.LeaveGroup _ -> throw new UnsupportedActionException(device.name() + " cannot be grouped");
        }
    }

    /**
     * Receiver-namespace commands are answered with a RECEIVER_STATUS; anything else is a refusal. Runs on the
     * caller's thread: the reply arrives on the reader thread, and the loop keeps handling state updates meanwhile.
     */
    private void receiverCommand(ObjectNode payload, String what) {
        CastConnection current = requireConnected();
        CastIncoming reply = DeviceCalls.run(device.name(), what,
                () -> current.request(RECEIVER, PLATFORM_RECEIVER_ID, payload, timings.commandTimeout()));
        if (!RECEIVER_STATUS_TYPE.equals(reply.type())) {
            throw new ActionFailedException(device.name() + " refused to " + what + " (" + reply.describeFailure() + ")");
        }
    }

    private void stopForegroundApp() {
        requireConnected();
        ReceiverStatus status = receiver;
        Optional<ReceiverStatus.ReceiverApp> app = status == null ? Optional.empty() : status.foregroundApp();
        if (app.isEmpty()) {
            return; // nothing is casting, so it is already stopped
        }
        receiverCommand(CastPayloads.stop(app.get().sessionId()), "stop " + describe(app.get()));
    }

    /** Launch the receiver app unless it already runs, connect to its transport, LOAD. */
    private void load(String appId, Map<String, Object> body) {
        CastConnection current = requireConnected();
        ReceiverStatus.ReceiverApp app = Optional.ofNullable(receiver)
                .flatMap(status -> status.app(appId))
                .orElseGet(() -> launch(current, appId));
        // Harmless if the media follower already connected.
        DeviceCalls.run(device.name(), REACH_PREFIX + describe(app), () -> current.connect(app.transportId()));
        CastIncoming reply = DeviceCalls.run(device.name(), "load the media", () -> current.request(MEDIA,
                app.transportId(), CastPayloads.load(app.sessionId(), body), timings.loadTimeout()));
        if (!"MEDIA_STATUS".equals(reply.type())) {
            throw new ActionFailedException(device.name() + " could not play it (" + reply.describeFailure() + ")");
        }
    }

    /** Launch the app unless it runs, wait until it speaks {@code namespace}, connect, send; a quick error reply fails. */
    private void customMessage(String appId, String namespace, Map<String, Object> message) {
        CastConnection current = requireConnected();
        ReceiverStatus.ReceiverApp running = Optional.ofNullable(receiver)
                .flatMap(status -> status.app(appId))
                .orElseGet(() -> launch(current, appId));
        ReceiverStatus.ReceiverApp app = running.speaks(namespace) ? running : awaitNamespace(current, appId, namespace);
        DeviceCalls.run(device.name(), REACH_PREFIX + describe(app), () -> current.connect(app.transportId()));
        CastConnection.Waiter rejection = current.expect(incoming -> namespace.equals(incoming.namespace())
                && app.transportId().equals(incoming.sourceId())
                && CUSTOM_ERROR_TYPES.contains(incoming.type()));
        try {
            DeviceCalls.run(device.name(), "send the request to " + describe(app),
                    () -> current.send(namespace, app.transportId(), CastPayloads.custom(message)));
            Optional<CastIncoming> error = DeviceCalls.run(device.name(), "start playback", () -> awaitRejection(rejection));
            if (error.isEmpty()) {
                return; // no rejection: the receiver took the request
            }
            String reason = error.get().payload().path("message").asString("");
            throw new ActionFailedException(device.name() + " refused to play it ("
                    + (reason.isBlank() ? error.get().type() : reason) + ")");
        } finally {
            rejection.cancel();
        }
    }

    /** Receivers check a custom request at once: silence through the error window means they took it. */
    private Optional<CastIncoming> awaitRejection(CastConnection.Waiter rejection) throws IOException {
        try {
            return Optional.of(rejection.await(timings.customMessageErrorWindow()));
        } catch (DeviceTimeoutException _) {
            return Optional.empty();
        }
    }

    /**
     * Launch the app unless it runs, wait until it speaks the namespace, connect, send, and wait
     * (command timeout) for the reply of the asked type; an error reply fails.
     */
    @Override
    public Map<String, Object> query(CastAppQuery query) {
        CastConnection current = requireConnected();
        ReceiverStatus.ReceiverApp running = Optional.ofNullable(receiver)
                .flatMap(status -> status.app(query.receiverAppId()))
                .orElseGet(() -> launch(current, query.receiverAppId()));
        ReceiverStatus.ReceiverApp app = running.speaks(query.namespace())
                ? running : awaitNamespace(current, query.receiverAppId(), query.namespace());
        DeviceCalls.run(device.name(), REACH_PREFIX + describe(app), () -> current.connect(app.transportId()));
        CastConnection.Waiter answer = current.expect(incoming -> query.namespace().equals(incoming.namespace())
                && app.transportId().equals(incoming.sourceId())
                && (query.replyType().equals(incoming.type()) || CUSTOM_ERROR_TYPES.contains(incoming.type())));
        try {
            CastIncoming reply = DeviceCalls.run(device.name(), "answer " + query.replyType(), () -> {
                current.send(query.namespace(), app.transportId(), CastPayloads.custom(query.message()));
                return answer.await(timings.commandTimeout());
            });
            if (!query.replyType().equals(reply.type())) {
                String reason = reply.payload().path("message").asString("");
                throw new ActionFailedException(device.name() + " refused the request ("
                        + (reason.isBlank() ? reply.type() : reason) + ")");
            }
            return CastPayloads.toMap(reply.payload());
        } finally {
            answer.cancel();
        }
    }

    /** A freshly launched custom receiver announces its namespaces in a later RECEIVER_STATUS. */
    private ReceiverStatus.ReceiverApp awaitNamespace(CastConnection current, String appId, String namespace) {
        CastConnection.Waiter ready = current.expect(incoming -> RECEIVER.equals(incoming.namespace())
                && RECEIVER_STATUS_TYPE.equals(incoming.type())
                && ReceiverStatus.parse(incoming.payload().path(STATUS_FIELD)).app(appId)
                        .filter(candidate -> candidate.speaks(namespace)).isPresent());
        CastIncoming status = DeviceCalls.run(device.name(), "start receiver app " + appId, () -> {
            try {
                current.send(RECEIVER, PLATFORM_RECEIVER_ID, CastPayloads.getStatus());
                return ready.await(timings.loadTimeout());
            } finally {
                ready.cancel();
            }
        });
        return ReceiverStatus.parse(status.payload().path(STATUS_FIELD)).app(appId).orElseThrow();
    }

    private ReceiverStatus.ReceiverApp launch(CastConnection current, String appId) {
        int requestId = current.nextRequestId();
        ObjectNode launch = CastPayloads.launch(appId);
        launch.put("requestId", requestId);
        // The reply to LAUNCH can be a RECEIVER_STATUS still showing the previous app; wait for
        // the status that lists ours, or for an error answering our request.
        CastConnection.Waiter outcome = current.expect(message -> RECEIVER.equals(message.namespace())
                && ((RECEIVER_STATUS_TYPE.equals(message.type())
                        && ReceiverStatus.parse(message.payload().path(STATUS_FIELD)).app(appId).isPresent())
                    || (message.requestId() == requestId && !RECEIVER_STATUS_TYPE.equals(message.type()))));
        CastIncoming reply = DeviceCalls.run(device.name(), "start receiver app " + appId, () -> {
            try {
                current.send(RECEIVER, PLATFORM_RECEIVER_ID, launch);
                return outcome.await(timings.loadTimeout());
            } finally {
                outcome.cancel();
            }
        });
        if (!RECEIVER_STATUS_TYPE.equals(reply.type())) {
            throw new ActionFailedException(device.name() + " could not start receiver app " + appId
                    + " (" + reply.describeFailure() + ")");
        }
        return ReceiverStatus.parse(reply.payload().path(STATUS_FIELD)).app(appId).orElseThrow();
    }

    /** Receivers may send a blank display name; the app id still tells the user something. */
    private static String describe(ReceiverStatus.ReceiverApp app) {
        if (!app.displayName().isBlank()) {
            return app.displayName();
        }
        return app.appId().isBlank() ? "the current app" : app.appId();
    }

    private CastConnection requireConnected() {
        Optional<CastConnection> current = connection.current();
        if (current.isEmpty() || publisher.current().status() != DeviceStatus.CONNECTED) {
            throw DeviceCalls.notConnected(device.name());
        }
        return current.get();
    }

    /** Runs on the loop, through the {@link Reconnector}. */
    private Reconnector.Outcome connect() {
        publisher.update(state -> state.withStatus(DeviceStatus.CONNECTING));
        Link link = new Link();
        CastConnection opened = null;
        try {
            opened = CastConnection.open(settings.host(), settings.port(),
                    timings.heartbeatInterval(),
                    timings.staleTimeout(),
                    link);
            opened.send(RECEIVER, PLATFORM_RECEIVER_ID, CastPayloads.getStatus()); // the reply arrives via Link
            link.own = opened;
            if (!connection.set(opened)) {
                return Reconnector.Outcome.STOP; // closed meanwhile; the slot closed the connection
            }
            publisher.update(state -> state.withStatus(DeviceStatus.CONNECTED));
            return Reconnector.Outcome.CONNECTED;
        } catch (IOException e) {
            if (opened != null) {
                opened.close();
            }
            log.debug("Could not reach Cast receiver {} at {}:{}: {}", device.id(), settings.host(), settings.port(), e.getMessage());
            publisher.update(state -> state.withStatus(DeviceStatus.DISCONNECTED));
            return Reconnector.Outcome.RETRY;
        }
    }

    /** Receivers only push on changes; ask while playing so the position moves. Runs on the loop. */
    private void pollMediaPosition() {
        Optional<CastConnection> current = connection.current();
        String transport = mediaTransportId;
        NowPlaying playing = publisher.current().nowPlaying();
        if (current.isEmpty() || transport == null || playing == null || playing.state() != PlaybackState.PLAYING) {
            return;
        }
        try {
            sendMediaGetStatus(current.get(), transport);
        } catch (IOException e) {
            log.debug("Media status poll failed for {}: {}", device.id(), e.getMessage());
        }
    }

    /** The reply arrives via Link like any other status; the id only keeps strict receivers happy. */
    private static void sendMediaGetStatus(CastConnection current, String transportId) throws IOException {
        ObjectNode getStatus = CastPayloads.getStatus();
        getStatus.put("requestId", current.nextRequestId());
        current.send(MEDIA, transportId, getStatus);
    }

    @Override
    public void close() {
        publisher.close();
        loop.close();
        connection.close();
    }

    /** One connection's listener: its callbacks run on the loop, and only while its connection is the current one. */
    private final class Link implements CastConnection.Listener {

        /** Set on the loop before any callback of this connection runs there. */
        private CastConnection own;

        private boolean current() {
            return own != null && connection.current().filter(live -> live == own).isPresent();
        }

        private void handle(CastIncoming message) {
            if (RECEIVER.equals(message.namespace()) && RECEIVER_STATUS_TYPE.equals(message.type())) {
                onReceiverStatus(ReceiverStatus.parse(message.payload().path(STATUS_FIELD)));
            } else if (MEDIA.equals(message.namespace()) && "MEDIA_STATUS".equals(message.type())
                    && message.sourceId().equals(mediaTransportId)) {
                onMediaStatus(MediaStatus.parse(message.payload().path(STATUS_FIELD)));
            } else if (CONNECTION.equals(message.namespace()) && "CLOSE".equals(message.type())
                    && message.sourceId().equals(mediaTransportId)) {
                // The app closed our virtual connection (it stopped); the next RECEIVER_STATUS says what replaced it.
                mediaTransportId = null;
                lastMedia = null;
                publisher.update(state -> state.withNowPlaying(null));
            }
        }

        private void onReceiverStatus(ReceiverStatus status) {
            receiver = status;
            Optional<ReceiverStatus.ReceiverApp> foreground = status.foregroundApp();
            publisher.update(state -> state.withPower(!status.standBy())
                    .withCurrentApp(foreground.map(ReceiverStatus.ReceiverApp::displayName).orElse(null))
                    .withVolume(status.volumePercent(), 100, status.muted()));
            followMedia(foreground.filter(app -> app.speaks(MEDIA)).map(ReceiverStatus.ReceiverApp::transportId).orElse(null));
        }

        /** Subscribes to the media channel of whatever app is in front — including casts started from a phone. */
        private void followMedia(String transportId) {
            if (Objects.equals(transportId, mediaTransportId)) {
                return;
            }
            mediaTransportId = transportId;
            lastMedia = null;
            if (transportId == null) {
                publisher.update(state -> state.withNowPlaying(null));
                return;
            }
            try {
                own.connect(transportId);
                sendMediaGetStatus(own, transportId);
            } catch (IOException e) {
                log.debug("Could not follow media on {}: {}", device.id(), e.getMessage()); // the reader reports the drop
            }
        }

        private void onMediaStatus(List<MediaStatus> statuses) {
            if (statuses.isEmpty()) {
                lastMedia = null;
                publisher.update(state -> state.withNowPlaying(null));
                return;
            }
            MediaStatus latest = statuses.getFirst().fillFrom(lastMedia);
            lastMedia = latest;
            PlaybackState playback = latest.playbackState();
            NowPlaying playing = playback == PlaybackState.IDLE ? null
                    : new NowPlaying(latest.displayTitle(), playback, latest.currentTime(), latest.duration());
            publisher.update(state -> state.withNowPlaying(playing));
        }

        private void handleDisconnect(CastDisconnectCause cause) {
            connection.takeIf(own);
            receiver = null;
            mediaTransportId = null;
            lastMedia = null;
            log.info("Lost the Cast connection to {} ({}); reconnecting", device.id(), cause);
            publisher.update(state -> state.withStatus(DeviceStatus.DISCONNECTED).withNowPlaying(null));
            reconnector.lost();
        }

        /** Hands a reader-thread callback to the loop, which drops it once its connection is outdated. */
        private void onLoop(Runnable task) {
            loop.execute(() -> {
                if (current()) {
                    task.run();
                }
            });
        }

        @Override
        public void onMessage(CastIncoming message) {
            onLoop(() -> handle(message));
        }

        @Override
        public void onDisconnected(CastDisconnectCause cause) {
            onLoop(() -> handleDisconnect(cause));
        }
    }
}
```

- [ ] **Step 4: Run the tests to see them pass**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.adapters.cast.*'`
Expected: PASS, all of `CastSessionTest` and `CastAdapterTest` included.

- [ ] **Step 5: Run the build**

Run: `scripts/gradle.sh build`
Expected: green. If Sonar-style compiler warnings mention an unused import, remove it.

- [ ] **Step 6: Commit**

Stage `CastSession.java` and `CastSessionTest.java`. Message:

```
refactor: Cast on the session toolkit

CastSession keeps its protocol and composes adapters.support: a SessionLoop on a virtual thread
instead of its own platform thread, a Reconnector instead of its backoff and scheduling, a
StatePublisher instead of its update(), and a ConnectionSlot that recognises a stale callback by
its connection, which replaces the generation counter. Its media position poll runs as
loop.every(). Commands translate failures through DeviceCalls: a lost connection now reads
"could not be reached to …", and a status that changes nothing visible is no longer published.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Q6dsbK16wqsU1DrkTAV32K
```

---

### Task 5: Android TV on the session toolkit

**Files:**
- Modify: `src/main/java/dev/andre/homecontrol/adapters/androidtv/AndroidTvSession.java` (whole file below)
- Test: `src/test/java/dev/andre/homecontrol/adapters/androidtv/AndroidTvSessionTest.java`

**Interfaces:**
- Consumes: `SessionLoop` (`execute`, `timer()`, `close`), `SessionLoop.Timer` (`schedule`, `cancel`),
  `Reconnector` (`start`, `connected`, `lost`, `stop`; outcomes `PENDING`, `RETRY`, `STOP`), `StatePublisher`,
  `ConnectionSlot<RemoteConnection>`, `Backoff`, `DeviceCalls` from Task 1 and PR 1.
- Produces: nothing new. The public constructor, `start()`, `state()`, `sendKey(RemoteKey)`,
  `sendKey(RemoteKey, KeyPress)`, `openAppLink(URI)`, `execute`, `close()` and the package-private constructor with
  its `ConnectionOpener` keep their signatures. `AndroidTvSession` no longer implements `RemoteListener`.

Behaviour that changes: messages name the device ("Test Shield is not connected", "Test Shield could not be reached to
press DPAD_UP") instead of "The device …"; a state that changes nothing visible is not published again; the loop is
a virtual thread. The unpaired latch, the certificate-mismatch stop and the ready handshake stay as they are.

- [ ] **Step 1: Write the tests**

In `AndroidTvSessionTest`, add the imports `dev.andre.homecontrol.adapters.androidtv.protocol.RemoteListener`,
`dev.andre.homecontrol.testsupport.RecordingStateListener` and `java.util.concurrent.atomic.AtomicReference`.

`refusesCommandsWhileDisconnected` names the device:

```java
    @Test
    void refusesCommandsWhileDisconnected() {
        assertThatThrownBy(() -> session.sendKey(RemoteKey.DPAD_UP))
                .isInstanceOf(DeviceOfflineException.class)
                .hasMessage("Test Shield is not connected");
    }
```

`staysConnectingAfterTlsUntilTheRemoteHandshakeCompletes` sends its callback through the attempt's own listener
(plan ruling 7):

```java
    @Test
    void staysConnectingAfterTlsUntilTheRemoteHandshakeCompletes() throws Exception {
        FakeRemoteServer.ConnectionGate gate = fakeDevice.pauseNextRemoteHandshake();
        ClientCertificate credential = ClientCertificate.generate("shield-remote");
        AtomicReference<RemoteListener> attempt = new AtomicReference<>();
        Device device = AndroidTvSettings.device("shield-gated", "Test Shield", "127.0.0.1", fakeDevice.port(),
                null, Instant.now());

        try (AndroidTvSession gated = new AndroidTvSession(device, credential, TIMINGS, state -> {
        }, listener -> {
            attempt.set(listener);
            return RemoteConnection.connect("127.0.0.1", fakeDevice.port(), credential, 10_000, listener);
        })) {
            gated.start();
            gate.awaitEntered();
            await().until(() -> attempt.get() != null);

            // The callback reaches the loop after connect() returned, so TLS is complete.
            attempt.get().onPower(true);
            await().until(() -> gated.state().powerOn());
            assertThat(gated.state().status()).isEqualTo(DeviceStatus.CONNECTING);
            assertThatThrownBy(() -> gated.sendKey(RemoteKey.DPAD_UP))
                    .isInstanceOf(DeviceOfflineException.class);

            gate.release();
            await().until(() -> gated.state().status() == DeviceStatus.CONNECTED);
        }
    }
```

`doesNotContinueConnectingAfterAStateListenerThrowsAnError` watches a quiet period instead of queueing a sentinel
callback through the session (plan ruling 7):

```java
    @Test
    void doesNotContinueConnectingAfterAStateListenerThrowsAnError() throws Exception {
        Device device = AndroidTvSettings.device("shield-listener-error", "Test Shield", "127.0.0.1",
                fakeDevice.port(), null, Instant.now());
        CountDownLatch connecting = new CountDownLatch(1);

        try (AndroidTvSession failing = new AndroidTvSession(device,
                ClientCertificate.generate("shield-remote"), TIMINGS, state -> {
                    if (state.status() == DeviceStatus.CONNECTING) {
                        connecting.countDown();
                        throw new LinkageError("a subscriber cannot load its dependency");
                    }
                }, null)) {
            failing.start();
            assertThat(connecting.await(5, TimeUnit.SECONDS)).isTrue();

            // Ten times the 50 ms first retry: an attempt that went on, or a retry, would have connected by now.
            await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2))
                    .until(() -> fakeDevice.connections() == 0);
            assertThat(failing.state().status()).isEqualTo(DeviceStatus.CONNECTING);
        }
    }
```

And a new test:

```java
    @Test
    void aPushThatChangesNothingIsNotPublishedAgain() throws Exception {
        RecordingStateListener states = new RecordingStateListener();
        Device device = AndroidTvSettings.device("shield-recorded", "Test Shield", "127.0.0.1", fakeDevice.port(),
                null, Instant.now());

        try (AndroidTvSession recorded = new AndroidTvSession(device, ClientCertificate.generate("shield-remote"),
                TIMINGS, states, null)) {
            recorded.start();
            await().until(() -> recorded.state().status() == DeviceStatus.CONNECTED);
            fakeDevice.pushVolume(12, 100, false);
            await().until(() -> recorded.state().volumeLevel() == 12);
            int published = states.all().size();

            fakeDevice.pushVolume(12, 100, false);
            fakeDevice.pushVolume(13, 100, false);

            await().until(() -> recorded.state().volumeLevel() == 13);
            assertThat(states.all()).hasSize(published + 1);
        }
    }
```

- [ ] **Step 2: Run them**

Run: `scripts/gradle.sh test --tests dev.andre.homecontrol.adapters.androidtv.AndroidTvSessionTest`
Expected: `refusesCommandsWhileDisconnected` FAILS ("The device is not connected") and
`aPushThatChangesNothingIsNotPublishedAgain` FAILS (every push is published today). The two rewritten tests pass:
they pin behaviour the move must keep.

- [ ] **Step 3: Write the implementation**

```java
// File: src/main/java/dev/andre/homecontrol/adapters/androidtv/AndroidTvSession.java
package dev.andre.homecontrol.adapters.androidtv;

import dev.andre.homecontrol.adapters.androidtv.protocol.ClientCertificate;
import dev.andre.homecontrol.adapters.androidtv.protocol.DisconnectCause;
import dev.andre.homecontrol.adapters.androidtv.protocol.RemoteConnection;
import dev.andre.homecontrol.adapters.androidtv.protocol.RemoteListener;
import dev.andre.homecontrol.adapters.androidtv.protocol.TlsSockets;
import dev.andre.homecontrol.adapters.support.Backoff;
import dev.andre.homecontrol.adapters.support.ConnectionSlot;
import dev.andre.homecontrol.adapters.support.DeviceCalls;
import dev.andre.homecontrol.adapters.support.Reconnector;
import dev.andre.homecontrol.adapters.support.SessionLoop;
import dev.andre.homecontrol.adapters.support.StatePublisher;
import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.KeyPress;
import dev.andre.homecontrol.core.LaunchedMedia;
import dev.andre.homecontrol.core.RedactedUris;
import dev.andre.homecontrol.core.RemoteKey;
import dev.andre.homecontrol.core.UnsupportedActionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * One device's live connection: connects, keeps the last known {@link DeviceState}, and reconnects with a growing
 * backoff, except when the device has rejected the pairing, where retrying is pointless (spec §8). TLS alone does not
 * make the session usable: the {@link Reconnector}'s attempt stays pending until the device finishes the Remote v2
 * configure/active exchange. Everything that changes the session runs on its loop, and a callback of a connection that
 * is no longer the current one is dropped there.
 */
public class AndroidTvSession implements DeviceHandle {

    private static final Logger log = LoggerFactory.getLogger(AndroidTvSession.class);

    /**
     * A single UNPAIRED verdict is ambiguous: it fires both for a genuinely de-paired
     * device and for a connection that drops before the app-level handshake finishes
     * (indistinguishable from here — see {@code RemoteConnection.classify}). Requiring
     * this many in a row before latching keeps an ordinary reboot from being mistaken
     * for a de-pairing: on the default 1s-doubling ramp the fifth verdict lands about
     * half a minute after the first, past the window in which a rebooting device is
     * accepting TLS connections but tearing them down again. Latching earlier is the
     * damaging mistake, because UNPAIRED never schedules another attempt — while a real
     * re-pair only has to be noticed eventually, and a fingerprint MISMATCH, which is
     * not ambiguous at all, still latches on the first occurrence.
     */
    private static final int UNPAIRED_CONFIRMATION_THRESHOLD = 5;

    private final Device device;
    private final ConnectionOpener opener;
    private final StatePublisher publisher;
    private final SessionLoop loop;
    /** When what was launched next stops counting as playing; its own slot, so a reconnect never cancels it. */
    private final SessionLoop.Timer playbackExpiry;
    private final Reconnector reconnector;
    private final ConnectionSlot<RemoteConnection> connection;

    /** Loop thread only. */
    private int consecutiveUnpaired;
    /** Loop thread only. Outlives a reconnect: the device keeps playing. */
    private final InferredPlayback playback = new InferredPlayback();

    public AndroidTvSession(Device device, ClientCertificate credential,
                         AndroidTvProperties properties, Consumer<DeviceState> onChange) {
        this(device, credential, AndroidTvTimings.from(properties), onChange, null);
    }

    @FunctionalInterface
    interface ConnectionOpener {
        RemoteConnection open(RemoteListener listener) throws IOException;
    }

    AndroidTvSession(Device device, ClientCertificate credential,
                     AndroidTvTimings timings, Consumer<DeviceState> onChange,
                     ConnectionOpener opener) {
        this.device = device;
        AndroidTvSettings settings = AndroidTvSettings.of(device);
        this.opener = opener == null
                ? listener -> RemoteConnection.connect(device.host(), settings.port(), credential,
                        Math.toIntExact(timings.staleTimeout().toMillis()), listener, settings.certificateFingerprint())
                : opener;
        this.publisher = new StatePublisher(device.id(), DeviceState.initial(), onChange);
        this.loop = new SessionLoop("shield-session-" + device.id());
        this.playbackExpiry = loop.timer();
        this.reconnector = new Reconnector(loop, new Backoff(timings.reconnectInitialDelay(),
                timings.reconnectMaxDelay()), this::connect);
        this.connection = new ConnectionSlot<>("shield-session-" + device.id());
    }

    public void start() {
        reconnector.start();
    }

    public DeviceState state() {
        return publisher.current();
    }

    public void sendKey(RemoteKey key) {
        sendKey(key, KeyPress.SHORT);
    }

    public void sendKey(RemoteKey key, KeyPress press) {
        RemoteConnection current = requireConnected();
        DeviceCalls.run(device.name(), "press " + key, () -> current.sendKey(key, press));
    }

    public void openAppLink(URI uri) {
        RemoteConnection current = requireConnected();
        DeviceCalls.run(device.name(), "open " + RedactedUris.withoutQuery(uri), () -> current.sendAppLink(uri.toString()));
    }

    @Override
    public void execute(Action action) {
        switch (action) {
            case Action.PressKey(var key, var press) -> sendKey(key, press);
            case Action.OpenAppLink(var uri, var media) -> {
                openAppLink(uri);
                if (media != null) {
                    loop.execute(() -> handleLaunched(media));
                }
            }
            case Action.SetVolume _ -> throw new UnsupportedActionException(
                    "Android TV Remote v2 has no absolute volume; use the volume keys");
            case Action.Mute _ -> throw new UnsupportedActionException(
                    "Android TV Remote v2 cannot set mute directly; use the mute key");
            case Action.Stop _ -> throw new UnsupportedActionException(
                    "Android TV Remote v2 cannot stop a cast");
            case Action.CastLoad _ -> throw new UnsupportedActionException(
                    "Android TV Remote v2 cannot load Cast media");
            case Action.CastMessage _ -> throw new UnsupportedActionException(
                    "Android TV Remote v2 cannot run Cast receiver apps");
            case Action.SelectInput _ -> throw new UnsupportedActionException(
                    "Android TV does not list its inputs; switch inputs from the Home screen");
            case Action.PlayMedia _ -> throw new UnsupportedActionException(
                    "Android TV Remote v2 cannot play a direct stream");
            case Action.Pause _ -> throw new UnsupportedActionException(
                    "Android TV Remote v2 cannot pause a direct stream; use the play/pause key");
            case Action.Resume _ -> throw new UnsupportedActionException(
                    "Android TV Remote v2 cannot resume a direct stream; use the play/pause key");
            case Action.JoinGroup _ -> throw new UnsupportedActionException("Android TV cannot be grouped");
            case Action.LeaveGroup _ -> throw new UnsupportedActionException("Android TV cannot be grouped");
        }
    }

    private RemoteConnection requireConnected() {
        Optional<RemoteConnection> current = connection.current();
        if (current.isEmpty() || publisher.current().status() != DeviceStatus.CONNECTED) {
            throw DeviceCalls.notConnected(device.name());
        }
        return current.get();
    }

    /** Runs on the loop, through the {@link Reconnector}. */
    private Reconnector.Outcome connect() {
        publisher.update(state -> state.withStatus(DeviceStatus.CONNECTING));
        Attempt attempt = new Attempt();
        try {
            RemoteConnection opened = opener.open(attempt);
            attempt.own = opened;
            if (!connection.set(opened)) {
                return Reconnector.Outcome.STOP; // closed during the handshake; the slot closed the connection
            }
            // TLS only proves transport setup. The reader reports Remote v2 readiness after the device's
            // configure/active exchange, on this loop.
            return Reconnector.Outcome.PENDING;
        } catch (TlsSockets.CertificateMismatchException _) {
            log.warn("Device {} presented an unexpected certificate; refusing it", device.id());
            publisher.update(state -> state.withStatus(DeviceStatus.UNPAIRED));
            return Reconnector.Outcome.STOP;
        } catch (RemoteConnection.UnpairedException _) {
            return ambiguousUnpaired() ? Reconnector.Outcome.RETRY : Reconnector.Outcome.STOP;
        } catch (IOException e) {
            log.debug("Could not reach {}: {}", device.host(), e.getMessage());
            forgetAmbiguousVerdicts();
            publisher.update(state -> state.withStatus(DeviceStatus.DISCONNECTED));
            return Reconnector.Outcome.RETRY;
        }
    }

    private void handleReady() {
        if (publisher.current().status() != DeviceStatus.CONNECTING) {
            return;
        }
        forgetAmbiguousVerdicts();
        reconnector.connected();
        publisher.update(state -> state.withStatus(DeviceStatus.CONNECTED));
    }

    /**
     * Counts an ambiguous UNPAIRED verdict — from either {@link RemoteConnection.UnpairedException}
     * or {@link DisconnectCause#UNPAIRED} — and says whether to retry like an ordinary drop: until it
     * has happened {@value #UNPAIRED_CONFIRMATION_THRESHOLD} times in a row, spanning a plausible
     * device reboot. Then the session latches UNPAIRED.
     * A certificate fingerprint MISMATCH is not ambiguous and does not go through here —
     * it latches immediately, on the first occurrence (see {@link TlsSockets.CertificateMismatchException}).
     */
    private boolean ambiguousUnpaired() {
        consecutiveUnpaired++;
        if (consecutiveUnpaired < UNPAIRED_CONFIRMATION_THRESHOLD) {
            log.info("Device {} looked unpaired ({}/{}); retrying before giving up",
                    device.id(), consecutiveUnpaired, UNPAIRED_CONFIRMATION_THRESHOLD);
            publisher.update(state -> state.withStatus(DeviceStatus.DISCONNECTED));
            return true;
        }
        log.warn("Could not establish the remote session for {} after {} authentication-like failures; try pairing again",
                device.id(), consecutiveUnpaired);
        publisher.update(state -> state.withStatus(DeviceStatus.UNPAIRED));
        return false;
    }

    /**
     * Clears the ambiguous-verdict count after any outcome that was NOT ambiguous — a
     * successful connection, or a network-class failure (spec §8 class 1), or a drop the
     * device explained some other way. The latch is a rule about CONSECUTIVE verdicts: a
     * device that could not be reached at all is positive evidence that the verdicts before
     * it were not a rejected certificate, so counting them together would let a merely flaky
     * device accumulate a latch over days and tell the user to re-pair when nothing is wrong.
     * A fingerprint MISMATCH does not come through here at all; it still latches at once.
     */
    private void forgetAmbiguousVerdicts() {
        consecutiveUnpaired = 0;
    }

    private void handlePower(boolean on) {
        if (!on) {
            playback.poweredOff();
        }
        publisher.update(state -> state.withPower(on).withNowPlaying(playback.current(Instant.now())));
    }

    private void handleCurrentApp(String appPackage) {
        playback.appChanged(appPackage);
        publisher.update(state -> state.withCurrentApp(appPackage).withNowPlaying(playback.current(Instant.now())));
    }

    private void handleLaunched(LaunchedMedia media) {
        playback.launched(media, publisher.current().currentApp(), Instant.now());
        refreshPlayback();
    }

    /** Publishes what plays now, then comes back when that is next due to change by itself. */
    private void refreshPlayback() {
        Instant now = Instant.now();
        publisher.update(state -> state.withNowPlaying(playback.current(now)));
        playback.nextDeadline().ifPresentOrElse(
                deadline -> playbackExpiry.schedule(this::refreshPlayback,
                        Duration.ofMillis(Math.max(0, Duration.between(now, deadline).toMillis()) + 1)),
                playbackExpiry::cancel);
    }

    private void handleDisconnect(Attempt attempt, DisconnectCause cause) {
        if (attempt.own == null || !connection.takeIf(attempt.own)) {
            return; // a connection that is no longer the current one
        }
        if (cause == DisconnectCause.UNPAIRED) {
            if (ambiguousUnpaired()) {
                reconnector.lost();
            } else {
                reconnector.stop();
            }
            return;
        }
        log.info("Lost the connection to {} ({}); reconnecting", device.id(), cause);
        forgetAmbiguousVerdicts();
        publisher.update(state -> state.withStatus(DeviceStatus.DISCONNECTED));
        reconnector.lost();
    }

    @Override
    public void close() {
        publisher.close();
        loop.close();
        connection.close();
    }

    /**
     * One attempt's listener. Its callbacks arrive on the protocol reader or idle-watchdog thread, which
     * {@link RemoteConnection} starts before its factory returns; each hands its work to the loop, where
     * {@code connect()} itself runs, so every change of state happens on one thread and in order.
     */
    private final class Attempt implements RemoteListener {

        /** Set on the loop before any callback of this attempt runs there. */
        private RemoteConnection own;

        private void onLoop(Runnable task) {
            loop.execute(() -> {
                if (own != null && connection.current().filter(live -> live == own).isPresent()) {
                    task.run();
                }
            });
        }

        @Override
        public void onReady() {
            onLoop(AndroidTvSession.this::handleReady);
        }

        @Override
        public void onPower(boolean on) {
            onLoop(() -> handlePower(on));
        }

        @Override
        public void onCurrentApp(String appPackage) {
            onLoop(() -> handleCurrentApp(appPackage));
        }

        @Override
        public void onVolume(int level, int max, boolean muted) {
            onLoop(() -> publisher.update(state -> state.withVolume(level, max, muted)));
        }

        @Override
        public void onDisconnected(DisconnectCause cause) {
            loop.execute(() -> handleDisconnect(this, cause));
        }
    }
}
```

- [ ] **Step 4: Run the tests to see them pass**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.adapters.androidtv.*'`
Expected: PASS, the latch tests (`latchesUnpairedAfterFiveConsecutiveAmbiguousVerdicts`,
`latchesWhenRemoteRejectsAfterTlsBeforeConfiguration`, `doesNotLatchWhenANetworkFailureSeparatesTheAmbiguousVerdicts`)
and `closingWhileAConnectIsInFlightClosesTheConnectionItThenOpens` included.

- [ ] **Step 5: Run the build**

Run: `scripts/gradle.sh build`
Expected: green. A web or device test that asserted "The device is not connected" would fail here; none does today
(checked with `grep -rn "The device is not connected" src/test`), so a failure means a new one, to be updated to name
the device.

- [ ] **Step 6: Commit**

Stage `AndroidTvSession.java` and `AndroidTvSessionTest.java`. Message:

```
refactor: Android TV on the session toolkit

AndroidTvSession keeps its protocol and its policies, the unpaired latch and the certificate
check, and composes adapters.support: a SessionLoop on a virtual thread, a Reconnector whose
attempt stays PENDING until the device reports ready (STOP on a certificate mismatch or the
latch), a StatePublisher and a ConnectionSlot. A callback is matched to its connection, which
replaces the generation counter, and the session no longer implements RemoteListener itself:
that legacy listener duplicated the per-attempt one. The playback expiry has a timer of its own.
Messages name the device ("{name} is not connected") and come from DeviceCalls.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Q6dsbK16wqsU1DrkTAV32K
```

---

### Task 6: webOS on the session toolkit

**Files:**
- Modify: `src/main/java/dev/andre/homecontrol/adapters/webos/WebOsSession.java` (whole file below)
- Delete: `src/main/java/dev/andre/homecontrol/adapters/net/Backoff.java`,
  `src/test/java/dev/andre/homecontrol/adapters/net/BackoffTest.java`
- Test: `src/test/java/dev/andre/homecontrol/adapters/webos/WebOsSessionTest.java`

**Interfaces:**
- Consumes: `SessionLoop` (`every`, `execute`, `close`), `Reconnector` (`start`, `lost`, `reconnectNow`, `retryIn`;
  outcomes `CONNECTED`, `RETRY`, `STOP`), `StatePublisher`, `ConnectionSlot<SsapConnection>`, `Backoff`,
  `DeviceCalls`, `WakeOnLanPower`, `LearnedMac` (Task 2), `PlayPauseToggle` (Task 3).
- Produces: nothing new. The package-private constructor, `start()`, `host()`, `reconnectNow()`, `state()`,
  `inputs()`, `execute` and `close()` keep their signatures; `WebOsAdapter` is unchanged.

Behaviour that changes: the loop is a virtual thread; failures read as `DeviceCalls` words them (plan ruling 8): a
refusal "LG TV refused to switch to input HDMI_2: …", a lost connection "LG TV could not be reached to set the
volume". An SSDP announcement while connected or unpaired does nothing, as before, now because the `Reconnector` is
not trying.

- [ ] **Step 1: Write the test**

Add to `WebOsSessionTest`:

```java
    @Test
    void anAnnouncementWhileConnectedOpensNoSecondConnection() throws Exception {
        started();
        connected();
        int opened = tv.connections();

        session.reconnectNow();
        session.reconnectNow();

        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2)).until(() -> tv.connections() == opened);
        assertThat(session.state().status()).isEqualTo(DeviceStatus.CONNECTED);
    }
```

- [ ] **Step 2: Run it**

Run: `scripts/gradle.sh test --tests dev.andre.homecontrol.adapters.webos.WebOsSessionTest`
Expected: PASS. It pins Review Focus 2, which the move must keep; the session's other tests are the rest of the gate.

- [ ] **Step 3: Write the implementation, and delete `net.Backoff` with its test**

```java
// File: src/main/java/dev/andre/homecontrol/adapters/webos/WebOsSession.java
package dev.andre.homecontrol.adapters.webos;

import dev.andre.homecontrol.adapters.net.DeviceTimeoutException;
import dev.andre.homecontrol.adapters.net.WakeOnLan;
import dev.andre.homecontrol.adapters.support.Backoff;
import dev.andre.homecontrol.adapters.support.ConnectionSlot;
import dev.andre.homecontrol.adapters.support.DeviceCalls;
import dev.andre.homecontrol.adapters.support.LearnedMac;
import dev.andre.homecontrol.adapters.support.PlayPauseToggle;
import dev.andre.homecontrol.adapters.support.Reconnector;
import dev.andre.homecontrol.adapters.support.SessionLoop;
import dev.andre.homecontrol.adapters.support.StatePublisher;
import dev.andre.homecontrol.adapters.support.WakeOnLanPower;
import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DeviceRegistry;
import dev.andre.homecontrol.core.DeviceSecrets;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.InputListing;
import dev.andre.homecontrol.core.KeyPress;
import dev.andre.homecontrol.core.LearnedSettings;
import dev.andre.homecontrol.core.RemoteKey;
import dev.andre.homecontrol.core.TvInput;
import dev.andre.homecontrol.core.UnsupportedActionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * One LG webOS TV. Connects with the stored client key, mirrors foreground app, volume and power
 * into {@link DeviceState}, and reconnects with backoff until closed — unless the TV no longer
 * accepts the key: then it is UNPAIRED and only re-pairing helps (connecting again would re-prompt).
 *
 * <p>Settings are read from the registry (the MAC may be typed in while connected) but written only
 * through {@link LearnedSettings}, i.e. by the device package under its registry lock. A client key the TV hands out goes to
 * the device secrets, under the reference the settings already name.
 *
 * <p>Threading: connect, loss and the liveness check run on the session's loop; commands run on the
 * caller's thread against the current connection and never wait for a reconnect; subscription
 * callbacks only update state.
 */
public class WebOsSession implements DeviceHandle, InputListing {

    private static final Logger log = LoggerFactory.getLogger(WebOsSession.class);
    private static final Set<String> STANDBY_STATES = Set.of("Suspend", "Active Standby", "Power Off");

    private final Device device;
    private final WebOsProperties properties;
    private final WebOsTimings timings;
    private final HttpClient http;
    private final DeviceRegistry registry;
    private final DeviceSecrets secrets;
    private final Runnable onClose;
    private final StatePublisher publisher;
    private final SessionLoop loop;
    private final Reconnector reconnector;
    private final ConnectionSlot<SsapConnection> connection;
    private final WakeOnLanPower power;
    private final LearnedMac learnedMac;
    private final PlayPauseToggle playPause = new PlayPauseToggle();

    // Immutable list replaced wholesale on the loop; request threads only read it.
    @SuppressWarnings("java:S3077")
    private volatile List<TvInput> inputs = List.of();

    // Package-private, built only by WebOsAdapter: ten distinct collaborator types, nothing to group.
    @SuppressWarnings("java:S107")
    WebOsSession(Device device, WebOsProperties properties, WebOsTimings timings, HttpClient http, DeviceRegistry registry,
                 LearnedSettings learned, DeviceSecrets secrets, WakeOnLan wakeOnLan, Consumer<DeviceState> onChange,
                 Runnable onClose) {
        this.device = device;
        this.properties = properties;
        this.timings = timings;
        this.http = http;
        this.registry = registry;
        this.secrets = secrets;
        this.onClose = onClose;
        this.publisher = new StatePublisher(device.id(), DeviceState.initial(), onChange);
        this.loop = new SessionLoop("webos-" + device.id());
        this.reconnector = new Reconnector(loop, new Backoff(timings.reconnectInitialDelay(),
                timings.reconnectMaxDelay()), this::connect);
        this.connection = new ConnectionSlot<>("webos-" + device.id());
        this.power = new WakeOnLanPower(device.name(), this::current, WebOsSettings.ADAPTER_ID, wakeOnLan);
        this.learnedMac = new LearnedMac(this::current, WebOsSettings.ADAPTER_ID, learned);
    }

    void start() {
        reconnector.start();
        loop.every(this::checkLiveness, timings.livenessInterval());
    }

    /**
     * SSAP has no heartbeat and the JDK WebSocket does not ping, so a TV that lost power without
     * closing TCP would stay CONNECTED forever. A cheap request every {@link WebOsTimings#livenessInterval()}
     * settles it: any answer (even an error) proves the TV is there; silence past the request
     * timeout means the connection is gone. Runs on the loop, so it never races {@link #connect} or {@link #lost}.
     */
    private void checkLiveness() {
        connection.current().ifPresent(current -> {
            try {
                current.request(SsapUris.SYSTEM_INFO, SsapMessages.empty());
            } catch (DeviceTimeoutException _) {
                lost(current, "no answer to the liveness check");
            } catch (SsapException _) {
                // The TV answered; it is alive even if it refuses this request.
            } catch (IOException e) {
                lost(current, e.getMessage());
            }
        });
    }

    String host() {
        return device.host();
    }

    /** SSDP heard the TV announce itself: skip whatever is left of the backoff. */
    void reconnectNow() {
        reconnector.reconnectNow();
    }

    @Override
    public DeviceState state() {
        return publisher.current();
    }

    @Override
    public List<TvInput> inputs() {
        return inputs;
    }

    @Override
    public void execute(Action action) {
        switch (action) {
            case Action.PressKey(var key, var press) -> pressKey(key, press);
            case Action.OpenAppLink(var uri, _) -> {
                WebOsLaunch launch = WebOsLaunches.forUri(uri);
                call(launch.ssapUri(), launch.payload(), "open " + uri);
            }
            case Action.SelectInput(var inputId) -> call(SsapUris.SWITCH_INPUT,
                    SsapMessages.empty().put("inputId", inputId), "switch to input " + inputId);
            case Action.SetVolume(var level) -> call(SsapUris.SET_VOLUME,
                    SsapMessages.empty().put("volume", Math.clamp(level, 0, 100)), "set the volume");
            case Action.Mute(var muted) -> call(SsapUris.SET_MUTE, SsapMessages.empty().put("mute", muted),
                    muted ? "mute" : "unmute");
            case Action.Stop _ -> call(SsapUris.MEDIA_STOP, SsapMessages.empty(), "stop playback");
            case Action.CastLoad _ -> throw new UnsupportedActionException(device.name() + " is not a Cast receiver");
            case Action.CastMessage _ -> throw new UnsupportedActionException(device.name() + " is not a Cast receiver");
            case Action.PlayMedia _ -> throw new UnsupportedActionException(device.name() + " cannot play a direct stream");
            case Action.Pause _ -> throw new UnsupportedActionException(device.name() + " cannot pause a direct stream");
            case Action.Resume _ -> throw new UnsupportedActionException(device.name() + " cannot resume a direct stream");
            case Action.JoinGroup _ -> throw new UnsupportedActionException(device.name() + " cannot be grouped");
            case Action.LeaveGroup _ -> throw new UnsupportedActionException(device.name() + " cannot be grouped");
        }
    }

    /**
     * The pointer socket has no press-and-hold: a long press sends its button once when it starts
     * and nothing when it ends, so a held key still does something instead of failing.
     */
    private void pressKey(RemoteKey key, KeyPress press) {
        if (press == KeyPress.END_LONG) {
            requireConnected();
            return;
        }
        switch (key) {
            case POWER -> togglePower();
            case VOLUME_UP -> call(SsapUris.VOLUME_UP, SsapMessages.empty(), "raise the volume");
            case VOLUME_DOWN -> call(SsapUris.VOLUME_DOWN, SsapMessages.empty(), "lower the volume");
            case VOLUME_MUTE -> call(SsapUris.SET_MUTE, SsapMessages.empty().put("mute", !publisher.current().muted()),
                    "mute");
            case PLAY_PAUSE -> button(playPause.playNext() ? "PLAY" : "PAUSE");
            default -> button(WebOsKeys.button(key).orElseThrow(() ->
                    new UnsupportedActionException(device.name() + " has no " + key + " button")));
        }
    }

    private void button(String name) {
        SsapConnection current = requireConnected();
        DeviceCalls.run(device.name(), "press " + name, () -> current.button(name));
    }

    /** A TV that is reachable but silent fails the command; the liveness check decides whether the connection is gone. */
    private void call(String uri, ObjectNode payload, String what) {
        SsapConnection current = requireConnected();
        DeviceCalls.run(device.name(), what, () -> current.request(uri, payload));
    }

    private void togglePower() {
        Optional<SsapConnection> current = connection.current();
        if (current.isPresent() && publisher.current().powerOn()) {
            DeviceCalls.run(device.name(), "switch off", () -> current.get().fire(SsapUris.TURN_OFF, SsapMessages.empty()));
            publisher.update(state -> state.withPower(false));
            return;
        }
        power.wake();
        reconnector.retryIn(timings.wakeGrace());
    }

    /** Runs on the loop, through the {@link Reconnector}. */
    private Reconnector.Outcome connect() {
        WebOsSettings settings = WebOsSettings.of(current(), secrets);
        String clientKey = settings.clientKey();
        if (clientKey == null) {
            publisher.publish(DeviceState.unpaired());
            return Reconnector.Outcome.STOP;
        }
        publisher.update(state -> state.withStatus(DeviceStatus.CONNECTING));
        AtomicReference<SsapConnection> attempt = new AtomicReference<>();
        SsapConnection opened = null;
        try {
            opened = SsapConnection.open(http, device.host(), properties,
                    reason -> loop.execute(() -> lost(attempt.get(), reason)));
            attempt.set(opened);
            String key = opened.register(clientKey, timings.registerTimeout());
            if (!connection.set(opened)) {
                return Reconnector.Outcome.STOP; // closed while registering; the slot closed the connection
            }
            if (!key.equals(clientKey)) {
                secrets.putDeviceSecret(WebOsSettings.secretName(settings.keyRef()), key); // a key implies a reference
            }
            publisher.update(state -> state.withStatus(DeviceStatus.CONNECTED).withPower(true));
            subscribeToState(opened);
            loadInputs(opened);
            learnMacAddress(opened);
            return Reconnector.Outcome.CONNECTED;
        } catch (SsapPairingException e) {
            closeQuietly(opened);
            log.warn("{} no longer accepts this server ({}); pair it again on the setup page", device.name(), e.getMessage());
            publisher.publish(DeviceState.unpaired());
            return Reconnector.Outcome.STOP;
        } catch (IOException e) {
            closeQuietly(opened);
            log.debug("{} is not reachable: {}", device.name(), e.getMessage());
            publisher.update(state -> state.withStatus(DeviceStatus.DISCONNECTED).withPower(false).withCurrentApp(null));
            return Reconnector.Outcome.RETRY;
        }
    }

    private void subscribeToState(SsapConnection opened) {
        subscribe(opened, SsapUris.FOREGROUND_APP, payload -> {
            String appId = payload.path("appId").asString("");
            publisher.update(state -> state.withCurrentApp(appId.isEmpty() ? null : appId));
        });
        subscribe(opened, SsapUris.GET_VOLUME, payload -> publisher.update(state -> WebOsPayloads.volume(state, payload)));
        subscribe(opened, SsapUris.POWER_STATE, payload -> {
            String powerState = payload.path("state").asString("");
            if (!powerState.isEmpty()) {
                publisher.update(state -> state.withPower(!STANDBY_STATES.contains(powerState)));
            }
        });
    }

    private void subscribe(SsapConnection opened, String uri, Consumer<JsonNode> onPayload) {
        try {
            opened.subscribe(uri, onPayload);
        } catch (IOException e) {
            // Older firmware lacks some services (e.g. tvpower); everything else still works.
            log.debug("{} does not offer {}: {}", device.name(), uri, e.getMessage());
        }
    }

    private void loadInputs(SsapConnection opened) {
        try {
            inputs = WebOsPayloads.inputs(opened.request(SsapUris.EXTERNAL_INPUTS, SsapMessages.empty()));
        } catch (IOException e) {
            log.debug("{} did not list its inputs: {}", device.name(), e.getMessage());
            inputs = List.of();
        }
    }

    private void learnMacAddress(SsapConnection opened) {
        try {
            WebOsPayloads.macAddress(opened.request(SsapUris.CONNECTION_INFO, SsapMessages.empty()), device.host())
                    .ifPresent(learnedMac::offer);
        } catch (IOException e) {
            log.debug("{} did not report its MAC address: {}", device.name(), e.getMessage());
        }
    }

    /** Runs on the loop. */
    private void lost(SsapConnection which, String reason) {
        if (which == null || !connection.takeIf(which)) {
            return;
        }
        inputs = List.of();
        log.info("Lost the connection to {} ({}); reconnecting", device.name(), reason);
        publisher.update(state -> state.withStatus(DeviceStatus.DISCONNECTED).withPower(false).withCurrentApp(null));
        reconnector.lost();
    }

    private SsapConnection requireConnected() {
        Optional<SsapConnection> current = connection.current();
        if (current.isPresent()) {
            return current.get();
        }
        if (publisher.current().status() == DeviceStatus.UNPAIRED) {
            throw new DeviceOfflineException(device.name() + " must be paired again before it can be controlled");
        }
        throw DeviceCalls.notConnected(device.name());
    }

    /** The registry's copy: settings (MAC, key) may have changed since this handle was created. */
    private Device current() {
        return registry.findById(device.id()).orElse(device);
    }

    private static void closeQuietly(SsapConnection opened) {
        if (opened != null) {
            opened.close();
        }
    }

    @Override
    public void close() {
        publisher.close();
        loop.close();
        connection.close();
        onClose.run();
    }
}
```

Delete the old backoff and its test, whose last caller this was:

```bash
git rm src/main/java/dev/andre/homecontrol/adapters/net/Backoff.java \
  src/test/java/dev/andre/homecontrol/adapters/net/BackoffTest.java
```

- [ ] **Step 4: Run the tests to see them pass**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.adapters.webos.*' --tests 'dev.andre.homecontrol.adapters.net.*'`
Expected: PASS: every `WebOsSessionTest` test (the liveness, wake, unpaired and listener-failure ones included),
`WebOsAdapterTest`, and the `adapters.net` tests without `BackoffTest`.

- [ ] **Step 5: Run the build**

Run: `scripts/gradle.sh build`
Expected: green; `grep -rn "adapters.net.Backoff" src` prints nothing.

- [ ] **Step 6: Commit**

Stage `WebOsSession.java`, `WebOsSessionTest.java` and the two deletions. Message:

```
refactor: webOS on the session toolkit

WebOsSession keeps its protocol and its policies, the key-rejected stop and the wake-triggered
reconnect, and composes adapters.support: a SessionLoop on a virtual thread, a Reconnector
(STOP without a key or when the TV rejects it, retryIn after a Wake-on-LAN packet, reconnectNow
on an SSDP announcement), a StatePublisher, a ConnectionSlot, WakeOnLanPower and LearnedMac. The
liveness check runs as loop.every(). Commands translate failures through DeviceCalls, so a
refusal reads "refused to …: …" and a lost connection "could not be reached to …". The static
net.Backoff goes with its last caller.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Q6dsbK16wqsU1DrkTAV32K
```

---

### Task 7: Tizen on the session toolkit; a failing state listener no longer reaches a button press

**Files:**
- Modify: `src/main/java/dev/andre/homecontrol/adapters/tizen/TizenSession.java` (whole file below)
- Test: `src/test/java/dev/andre/homecontrol/adapters/tizen/TizenSessionTest.java`

**Interfaces:**
- Consumes: `SessionLoop` (`execute`, `every`, `schedule`, `close`), `StatePublisher`,
  `ConnectionSlot<TizenRemoteConnection>`, `Backoff`, `DeviceCalls`, `WakeOnLanPower`, `LearnedMac` (Task 2),
  `PlayPauseToggle` (Task 3). No `Reconnector`: Tizen stays poll-driven (spec section 4).
- Produces: nothing new. The package-private constructor, `start()`, `host()`, `pollNow()`, `state()`, `execute` and
  `close()` keep their signatures; `TizenAdapter` is unchanged.

Behaviour that changes: a state listener that throws is logged, as everywhere else, instead of reaching whoever
pressed a button; the loop is a virtual thread; failures read as `DeviceCalls` words them (plan ruling 8); the
handshake gate takes its delay from a `Backoff` (2, 4, 8 … poll intervals up to the cap, as before); the MAC check no
longer resolves the pairing key.

- [ ] **Step 1: Write the tests**

In `TizenSessionTest`, add the imports `dev.andre.homecontrol.core.DeviceState`, `java.util.function.Consumer` and
the static `org.assertj.core.api.Assertions.assertThatCode`, and give `start` a listener parameter:

```java
    private TizenSession start(Map<String, String> settings, TizenTimings timings) {
        return start(settings, timings, states);
    }

    private TizenSession start(Map<String, String> settings, TizenTimings timings, Consumer<DeviceState> listener) {
        Device device = new Device("samsung", "Samsung TV", DeviceKind.TIZEN, "127.0.0.1",
                Map.of("tizen", stored(settings)), Instant.now());
        registry.save(device);
        session = new TizenSession(device, TizenRestTest.properties(tv), timings, InsecureTls.httpClient(Duration.ofSeconds(2)),
                registry, learned(), secrets, new WakeOnLan(receiver.address()), listener, () -> { });
        session.start();
        return session;
    }
```

Then add:

```java
    @Test
    void aFailingStateListenerNeverReachesAButtonPress() throws Exception {
        start(PAIRED, TIMINGS, state -> {
            states.accept(state);
            throw new IllegalStateException("a subscriber failed");
        });
        connected();

        var power = new Action.PressKey(RemoteKey.POWER);
        assertThatCode(() -> session.execute(power)).doesNotThrowAnyException();

        assertThat(tv.nextKey()).isEqualTo("KEY_POWER");
    }

    @Test
    void aWokenTvIsPolledAfterTheWakeGraceNotTheNextInterval() throws Exception {
        TizenTimings slowPoll = new TizenTimings(Duration.ofSeconds(30), Duration.ofMillis(100), Duration.ofMillis(500),
                TizenTimings.HANDSHAKE_BACKOFF_CAP);
        Map<String, String> settings = new LinkedHashMap<>(PAIRED);
        settings.put("macAddress", "70:2A:D5:01:02:03");
        start(settings, slowPoll);
        connected();
        tv.switchOff();
        tv.dropConnections();
        awaitStatus(DeviceStatus.DISCONNECTED);
        tv.switchOn();

        session.execute(new Action.PressKey(RemoteKey.POWER));

        assertThat(receiver.nextPacket()).containsExactly(WakeOnLan.magicPacket("70:2A:D5:01:02:03"));
        connected();
    }
```

- [ ] **Step 2: Run them**

Run: `scripts/gradle.sh test --tests dev.andre.homecontrol.adapters.tizen.TizenSessionTest`
Expected: `aFailingStateListenerNeverReachesAButtonPress` FAILS (the press throws `IllegalStateException: a subscriber
failed`, because `update` calls the listener unguarded). `aWokenTvIsPolledAfterTheWakeGraceNotTheNextInterval`
passes: it pins Review Focus 5, which the move must keep.

- [ ] **Step 3: Write the implementation**

```java
// File: src/main/java/dev/andre/homecontrol/adapters/tizen/TizenSession.java
package dev.andre.homecontrol.adapters.tizen;

import dev.andre.homecontrol.adapters.net.WakeOnLan;
import dev.andre.homecontrol.adapters.support.Backoff;
import dev.andre.homecontrol.adapters.support.ConnectionSlot;
import dev.andre.homecontrol.adapters.support.DeviceCalls;
import dev.andre.homecontrol.adapters.support.LearnedMac;
import dev.andre.homecontrol.adapters.support.PlayPauseToggle;
import dev.andre.homecontrol.adapters.support.SessionLoop;
import dev.andre.homecontrol.adapters.support.StatePublisher;
import dev.andre.homecontrol.adapters.support.WakeOnLanPower;
import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DeviceRegistry;
import dev.andre.homecontrol.core.DeviceSecrets;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.KeyPress;
import dev.andre.homecontrol.core.LearnedSettings;
import dev.andre.homecontrol.core.RemoteKey;
import dev.andre.homecontrol.core.UnsupportedActionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * One Samsung Tizen TV. Samsung pushes no state, so a poll (every {@link TizenTimings#pollInterval()})
 * reads power and MAC from the REST API, (re)opens the remote channel with the stored token when
 * the TV is on, and derives the current app from the visibility of the known service apps.
 * An explicit refusal ({@code ms.channel.unauthorized}) makes the session UNPAIRED and stops it.
 * Silence after opening the channel is transient (a slow-booting TV): the token is kept and the
 * next handshake waits with a doubling backoff, so a stale token cannot put the Allow prompt on
 * screen every few seconds.
 *
 * <p>Settings are read from the registry (the MAC may be typed in meanwhile) but written only
 * through {@link LearnedSettings}, i.e. by the device package under its registry lock.
 *
 * <p>Tizen never publishes CONNECTING: a poll every few seconds against a switched-off TV would
 * otherwise emit two SSE events per interval.
 */
public class TizenSession implements DeviceHandle {

    private static final Logger log = LoggerFactory.getLogger(TizenSession.class);

    private final Device device;
    private final TizenProperties properties;
    private final TizenTimings timings;
    private final HttpClient http;
    private final TizenRest rest;
    private final DialClient dial;
    private final DeviceRegistry registry;
    private final LearnedSettings learned;
    private final DeviceSecrets secrets;
    private final Runnable onClose;
    private final StatePublisher publisher;
    private final SessionLoop loop;
    private final ConnectionSlot<TizenRemoteConnection> connection;
    private final WakeOnLanPower power;
    private final LearnedMac learnedMac;
    private final PlayPauseToggle playPause = new PlayPauseToggle();
    /** How long the next handshake waits after one went unanswered: from two poll intervals up to the cap. */
    private final Backoff handshakeBackoff;

    private volatile boolean stopped;
    private boolean handshakeWaiting; // loop thread only
    private long connectNotBefore;    // loop thread only; System.nanoTime(), meaningful while handshakeWaiting

    // Package-private, built only by TizenAdapter: ten distinct collaborator types, nothing to group.
    @SuppressWarnings("java:S107")
    TizenSession(Device device, TizenProperties properties, TizenTimings timings, HttpClient http, DeviceRegistry registry,
                 LearnedSettings learned, DeviceSecrets secrets, WakeOnLan wakeOnLan, Consumer<DeviceState> onChange,
                 Runnable onClose) {
        this.device = device;
        this.properties = properties;
        this.timings = timings;
        this.http = http;
        this.rest = new TizenRest(http, properties);
        this.dial = new DialClient(http, properties);
        this.registry = registry;
        this.learned = learned;
        this.secrets = secrets;
        this.onClose = onClose;
        this.publisher = new StatePublisher(device.id(), DeviceState.initial(), onChange);
        this.loop = new SessionLoop("tizen-" + device.id());
        this.connection = new ConnectionSlot<>("tizen-" + device.id());
        this.power = new WakeOnLanPower(device.name(), this::current, TizenSettings.ADAPTER_ID, wakeOnLan);
        this.learnedMac = new LearnedMac(this::current, TizenSettings.ADAPTER_ID, learned);
        this.handshakeBackoff = new Backoff(timings.pollInterval().multipliedBy(2), timings.handshakeBackoffCap());
    }

    void start() {
        loop.execute(this::poll);
        loop.every(this::poll, timings.pollInterval());
    }

    String host() {
        return device.host();
    }

    /** SSDP heard the TV: poll now instead of at the next interval. */
    void pollNow() {
        loop.execute(this::poll);
    }

    @Override
    public DeviceState state() {
        return publisher.current();
    }

    @Override
    public void execute(Action action) {
        switch (action) {
            case Action.PressKey(var key, var press) -> pressKey(key, press);
            case Action.OpenAppLink(var uri, _) -> openAppLink(uri);
            case Action.SelectInput _ -> throw new UnsupportedActionException(
                    device.name() + " does not list its inputs; use the Source button of the TV remote");
            case Action.SetVolume _ -> throw volumeKeysOnly();
            case Action.Mute _ -> throw volumeKeysOnly();
            case Action.Stop _ -> sendKey("KEY_STOP");
            case Action.CastLoad _ -> throw new UnsupportedActionException(device.name() + " is not a Cast receiver");
            case Action.CastMessage _ -> throw new UnsupportedActionException(device.name() + " is not a Cast receiver");
            case Action.PlayMedia _ -> throw new UnsupportedActionException(device.name() + " cannot play a direct stream");
            case Action.Pause _ -> throw new UnsupportedActionException(device.name() + " cannot pause a direct stream");
            case Action.Resume _ -> throw new UnsupportedActionException(device.name() + " cannot resume a direct stream");
            case Action.JoinGroup _ -> throw new UnsupportedActionException(device.name() + " cannot be grouped");
            case Action.LeaveGroup _ -> throw new UnsupportedActionException(device.name() + " cannot be grouped");
        }
    }

    private UnsupportedActionException volumeKeysOnly() {
        return new UnsupportedActionException(device.name() + " only takes volume up, down and mute keys");
    }

    private void pressKey(RemoteKey key, KeyPress press) {
        if (press != KeyPress.SHORT) {
            // Held keys (navigation only) map onto the remote channel's own Press and Release.
            String code = TizenKeys.code(key).orElseThrow(() ->
                    new UnsupportedActionException(device.name() + " cannot hold " + key));
            TizenRemoteConnection current = requireConnected();
            boolean starts = press == KeyPress.START_LONG;
            DeviceCalls.run(device.name(), (starts ? "hold " : "release ") + key,
                    () -> current.key(code, starts ? "Press" : "Release"));
            return;
        }
        switch (key) {
            case POWER -> togglePower();
            case PLAY_PAUSE -> sendKey(playPause.playNext() ? "KEY_PLAY" : "KEY_PAUSE");
            default -> sendKey(TizenKeys.code(key).orElseThrow(() ->
                    new UnsupportedActionException(device.name() + " has no " + key + " key")));
        }
    }

    private void sendKey(String code) {
        TizenRemoteConnection current = requireConnected();
        DeviceCalls.run(device.name(), "press " + code, () -> current.key(code));
    }

    private void openAppLink(URI uri) {
        TizenRemoteConnection current = requireConnected();
        switch (TizenLaunches.forUri(uri, current.installedApps())) {
            case TizenLaunch.Dial(var app, var body) ->
                    DeviceCalls.run(device.name(), "start " + app, () -> dial.launch(device.host(), app, body));
            case TizenLaunch.App app ->
                    DeviceCalls.run(device.name(), "open " + app.name(), () -> current.launchApp(app.appId(), app.actionType()));
            case TizenLaunch.Unsupported(var reason) -> throw new UnsupportedActionException(
                    device.name() + ": " + reason);
        }
        pollNow(); // show the app that just came to the front without waiting for the next interval
    }

    private void togglePower() {
        if (connection.current().isPresent() && publisher.current().powerOn()) {
            sendKey("KEY_POWER");
            publisher.update(state -> state.withPower(false));
            return;
        }
        power.wake();
        loop.schedule(this::poll, timings.wakeGrace());
    }

    /** Runs on the loop; a poll that throws is logged there and the next one still comes. */
    private void poll() {
        if (stopped) {
            return;
        }
        Optional<TizenDeviceInfo> info = rest.deviceInfo(device.host());
        info.flatMap(TizenDeviceInfo::macAddress).ifPresent(learnedMac::offer);
        if (info.isPresent() && !info.get().on()) {
            connection.current().ifPresent(connection::takeIf);
            publisher.update(state -> state.withStatus(DeviceStatus.DISCONNECTED).withPower(false).withCurrentApp(null));
            return;
        }
        if (connection.current().isEmpty() && (handshakeBackingOff() || !connect())) {
            return;
        }
        String app = visibleKnownApp();
        publisher.update(state -> state.withStatus(DeviceStatus.CONNECTED).withPower(true).withCurrentApp(app));
    }

    private boolean handshakeBackingOff() {
        return handshakeWaiting && System.nanoTime() - connectNotBefore < 0;
    }

    private boolean connect() {
        TizenSettings settings = TizenSettings.of(current(), secrets);
        if (!settings.paired()) {
            stopped = true;
            publisher.publish(DeviceState.unpaired());
            return false;
        }
        AtomicReference<TizenRemoteConnection> attempt = new AtomicReference<>();
        TizenRemoteConnection opened = null;
        boolean held = false;
        try {
            opened = TizenRemoteConnection.open(http, device.host(), properties, settings.token(),
                    reason -> loop.execute(() -> lost(attempt.get(), reason)));
            attempt.set(opened);
            TizenRemoteConnection.Authorization answer = opened.awaitAuthorization(timings.requestTimeout());
            if (answer == TizenRemoteConnection.Authorization.CONNECTED) {
                handshakeWaiting = false;
                handshakeBackoff.reset();
                held = connection.set(opened);
                if (!held) {
                    return false; // closed while waiting for the TV; the slot closed the channel
                }
                opened.token().filter(token -> !token.equals(settings.token())).ifPresent(this::storeToken);
                opened.requestInstalledApps();
                return true;
            }
            opened.close();
            if (answer == TizenRemoteConnection.Authorization.NO_ANSWER) {
                // A slow-booting TV, or a forgotten token putting the Allow prompt on screen: never unpair
                // for silence. Keep the token and back off so a real prompt does not reappear every poll.
                Duration delay = handshakeBackoff.next();
                handshakeWaiting = true;
                connectNotBefore = System.nanoTime() + delay.toNanos();
                log.info("{} did not answer the connection within {} ms; retrying in {} ms",
                        device.name(), timings.requestTimeout().toMillis(), delay.toMillis());
                publisher.update(state -> state.withStatus(DeviceStatus.DISCONNECTED).withCurrentApp(null));
                return false;
            }
            stopped = true;
            log.warn("{} refused the stored pairing; pair it again on the setup page", device.name());
            publisher.publish(DeviceState.unpaired());
            return false;
        } catch (IOException e) {
            if (held) {
                connection.takeIf(opened); // the slot closes it, unless it was already given up
            } else if (opened != null) {
                opened.close();
            }
            log.debug("{} is not reachable: {}", device.name(), e.getMessage());
            publisher.update(state -> state.withStatus(DeviceStatus.DISCONNECTED).withPower(false).withCurrentApp(null));
            return false;
        }
    }

    private String visibleKnownApp() {
        Optional<TizenRemoteConnection> current = connection.current();
        if (current.isEmpty()) {
            return null;
        }
        for (TizenLaunch.App app : TizenLaunches.knownApps(current.get().installedApps())) {
            if (rest.appVisible(device.host(), app.appId()).orElse(false)) {
                return app.name();
            }
        }
        return null;
    }

    /** A token the TV issued: stored as a device secret, under a new reference if the device has none yet. */
    private void storeToken(String token) {
        String keyRef = TizenSettings.of(current(), secrets).keyRef();
        if (keyRef == null) {
            keyRef = DeviceSecrets.newReference();
            TizenSettings.keys(secrets).store(keyRef, token);
            learned.store(Map.of(TizenSettings.KEY_REF, keyRef));
        } else {
            TizenSettings.keys(secrets).store(keyRef, token);
        }
    }

    /** Runs on the loop. */
    private void lost(TizenRemoteConnection which, String reason) {
        if (which == null || !connection.takeIf(which)) {
            return;
        }
        log.info("Lost the connection to {} ({})", device.name(), reason);
        publisher.update(state -> state.withStatus(DeviceStatus.DISCONNECTED).withPower(false).withCurrentApp(null));
    }

    private TizenRemoteConnection requireConnected() {
        Optional<TizenRemoteConnection> current = connection.current();
        if (current.isPresent()) {
            return current.get();
        }
        if (publisher.current().status() == DeviceStatus.UNPAIRED) {
            throw new DeviceOfflineException(device.name() + " must be paired again before it can be controlled");
        }
        throw DeviceCalls.notConnected(device.name());
    }

    /** The registry's copy: settings (MAC, token) may have changed since this handle was created. */
    private Device current() {
        return registry.findById(device.id()).orElse(device);
    }

    @Override
    public void close() {
        publisher.close();
        loop.close();
        connection.close();
        onClose.run();
    }
}
```

- [ ] **Step 4: Run the tests to see them pass**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.adapters.tizen.*'`
Expected: PASS: every `TizenSessionTest` test (the handshake backoff, unpaired, token and drop tests included) and
`TizenAdapterTest`.

- [ ] **Step 5: Run the build**

Run: `scripts/gradle.sh build`
Expected: green.

- [ ] **Step 6: Commit**

Stage `TizenSession.java` and `TizenSessionTest.java`. Message:

```
fix: a Tizen state listener's failure no longer reaches a button press

TizenSession published state by calling its listener unguarded, so a subscriber that threw made
the power button fail for whoever pressed it, after the TV had already switched off. The session
now publishes through a StatePublisher, which logs a listener's failure like every other session.

With it, Tizen moves onto adapters.support and stays poll-driven: a SessionLoop on a virtual
thread runs the poll with loop.every(), the handshake gate takes its delay from a Backoff, the
channel lives in a ConnectionSlot, which closes it exactly once, and WakeOnLanPower and LearnedMac
replace its copies. Commands translate failures through DeviceCalls.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Q6dsbK16wqsU1DrkTAV32K
```

---

### Task 8: Device sessions create no executors

**Files:**
- Modify: `src/test/java/dev/andre/homecontrol/ArchitectureTest.java`

**Interfaces:**
- Consumes: Tasks 4–7 (no session builds an executor any more).
- Produces: the strict rule `sessionsRunOnTheirSessionLoop`, named in the architecture guide (Task 9).

- [ ] **Step 1: Write the rule**

Add the imports `java.util.concurrent.Executors`, `java.util.concurrent.ForkJoinPool`,
`java.util.concurrent.ScheduledThreadPoolExecutor` and `java.util.concurrent.ThreadPoolExecutor`, and, after
`servletTypesStayAtTheWebEdge`:

```java
    @ArchTest
    static final ArchRule sessionsRunOnTheirSessionLoop = noClasses()
            .that().resideInAPackage("dev.andre.homecontrol.adapters..")
            .and().haveSimpleNameEndingWith("Session")
            .should().dependOnClassesThat().belongToAnyOf(Executors.class, ThreadPoolExecutor.class,
                    ScheduledThreadPoolExecutor.class, ForkJoinPool.class)
            .because("a session's thread is its SessionLoop, which never throws once the session is closed");
```

- [ ] **Step 2: See it fail on a session that builds an executor**

Add a field to `CastSession` for a moment:

```java
    private final java.util.concurrent.ExecutorService probe = java.util.concurrent.Executors.newSingleThreadExecutor();
```

Run: `scripts/gradle.sh test --tests dev.andre.homecontrol.ArchitectureTest`
Expected: FAIL, `sessionsRunOnTheirSessionLoop` naming `CastSession` and `Executors.newSingleThreadExecutor()`.

- [ ] **Step 3: Remove the probe field and run the rule again**

Run: `scripts/gradle.sh test --tests dev.andre.homecontrol.ArchitectureTest`
Expected: PASS; `git diff --stat` shows only `ArchitectureTest.java`, and the store under `src/test/archunit-store`
is unchanged.

- [ ] **Step 4: Run the build**

Run: `scripts/gradle.sh build`
Expected: green.

- [ ] **Step 5: Commit**

Stage `ArchitectureTest.java`. Message:

```
test: device sessions create no executors

Every session now runs on a SessionLoop, whose calls are no-ops once it is closed. A strict
ArchUnit rule keeps it that way: no class named *Session in adapters depends on Executors or
builds a thread pool of its own.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Q6dsbK16wqsU1DrkTAV32K
```

---

### Task 9: Testing with the session toolkit, and the guides' numbers

**Files:**
- Modify: `docs/dev/testing.md` (section "Fakes and fixtures")
- Modify: `docs/dev/architecture.md` (the `adapters` row, the rule table, "Progress measures")

**Interfaces:**
- Consumes: Tasks 1–8.

- [ ] **Step 1: The testing guide**

In `docs/dev/testing.md`, after the bullet that begins "Device sessions take their waits as a `*Timings` record",
add:

```markdown
- A device session composes the toolkit in `adapters.support`: a `SessionLoop` (one virtual thread), a
  `StatePublisher`, a `ConnectionSlot` and, for Cast, Android TV and webOS, a `Reconnector`. The publisher drops a
  state that repeats the last one in everything the UI shows, so a test counts connection attempts at the fake
  (`connections()`), or counts CONNECTING states only where each follows a DISCONNECTED one.
- `close()` interrupts the session's loop, and on a virtual thread that aborts a blocking socket call. A test that
  needs a step to finish after `close()`, as a step that had already finished would, holds it in a step that ignores
  the interrupt (`UpnpSessionTest.awaitIgnoringInterrupts`, `SonosSessionTest.HeldClock`).
- A session's commands fail through `DeviceCalls`, so their messages read the same everywhere: "… did not answer in
  time when asked to …", "… refused to …: …", "… could not be reached to …" and "… is not connected". A test asserts
  one of these, or only the reason the device gave.
```

- [ ] **Step 2: The architecture guide**

In the `adapters` row of the package table, the `adapters.support` parenthesis becomes:

```markdown
(the sessions' lifecycle toolkit: `StatePublisher`, `SessionLoop`, `Backoff`, `ConnectionSlot`, `Reconnector`, `ReconnectingPoller`; `DeviceCalls`, which turns protocol failures into core exceptions; `WakeOnLanPower`, `LearnedMac`, `PlayPauseToggle` and `SessionRegistry`, which the TV and speaker adapters share; the renderer helpers Sonos and UPnP share; and TV pairing keys kept as device secrets)
```

In the rule table, after the `jakarta.servlet` row:

```markdown
| Classes named `*Session` in `adapters` create no executors: their thread is a `SessionLoop` | strict |
```

In "Progress measures", the frozen violations read `25` (PR 1 left the old 42 there). For the largest class, run:

```bash
find src/main/java -name '*.java' -exec wc -l {} + | sort -n | tail -3
```

and write the largest file's line count and class name into the "Now" cell, in the form `NNN lines (`ClassName`)`.

- [ ] **Step 3: Run the build and the browser tests**

Run: `scripts/gradle.sh build`
Expected: green.

Run: `scripts/e2e.sh -Pe2eBrowsers=chromium`
Expected: 68 tests, 0 failures.

- [ ] **Step 4: Commit**

Stage the two guides. Message:

```
docs: testing with the session toolkit

The testing guide says how a session test counts attempts now that states are deduplicated, how
a race test holds a step through close()'s interrupt, and which words a failed command uses. The
architecture guide lists the TV adapters' shared helpers and the rule that sessions create no
executors, and its measures catch up: 25 frozen violations, and the largest class.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Q6dsbK16wqsU1DrkTAV32K
```

---

## Self-review

- **Spec coverage.** Section 4: the Reconnector outcomes per session (Tasks 4–6), `ConnectionSlot` in each session
  replacing the generation counters (4, 5), `loop.every` for Cast's position poll and webOS's liveness check (4, 6),
  the legacy `RemoteListener` dropped (5), Tizen poll-driven with a `Backoff` gate and a `ConnectionSlot` (7), a
  throwing Tizen listener logged (7), `WakeOnLanPower`, `LearnedMac`, `SessionRegistry` (2), `DeviceCalls` in all four
  (4–7), the atomic play/pause toggle (3), `net.Backoff` deleted (6). Section 5: the executor rule (8). Testing: the
  helper unit tests (2), the toggle (3, plan ruling 5), the Tizen double close (plan ruling 6), the attempt counts
  (plan ruling 9). Delivery: the testing guide (9). PR 1's deferred minors that PR 2 builds on (1).
- **Measures** (spec): executors owned by sessions 6 → 0 (Tasks 4–7 and PR 1; Task 8 keeps it), backoff
  implementations 4 → 1 (`support.Backoff`), timeout exception types 3 → 1 and trust-any managers 2 → 1 (PR 1),
  session registries in adapters 4 → 1 (Task 2), frozen violations 25 (unchanged here).
- **Types.** `SessionLoop.Timer` (Task 1) is used by Task 5; `Reconnector.stop()` by Task 5; `WakeOnLanPower`,
  `LearnedMac` (Task 2) by Tasks 6 and 7; `PlayPauseToggle` (Task 3) by Tasks 6 and 7; `SessionRegistry.matching` and
  `open` (Task 2) by the four adapters in Task 2.
