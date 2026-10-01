# Phase 2D, PR 1: The Support Toolkit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An `adapters.support` toolkit the device sessions compose, one timeout and one refusal type with one
translation, the renderer code out of `upnp.protocol`, Sonos, UPnP and Bluetooth on the toolkit with their races
fixed, `CastTls` on `InsecureTls`, and virtual request threads.

**Architecture:** `StatePublisher`, `SessionLoop`, `Backoff`, `ConnectionSlot` and `Reconnector` are small final
classes in `adapters.support`; `ReconnectingPoller` moves there and is rebuilt on `SessionLoop` and `Backoff`. Protocol
classes throw `adapters.net.DeviceTimeoutException` / `DeviceRefusedException`; `support.DeviceCalls` turns them into
core exceptions. `RendererCommands`, `RendererFaultException` and `NowPlayings` move to `adapters.support`, next to a
`RendererStatePoller` that Sonos and UPnP share.

**Tech Stack:** Java 25 (virtual threads), Spring Boot 4.1.1, JUnit 5, AssertJ, Awaitility, ArchUnit 1.5.1.

**Spec:** `docs/superpowers/specs/2026-10-01-phase-2d-adapter-lifecycle-design.md` (commit `d318709`), sections 1–3 and
5, PR 1.

## Global Constraints

- Branch `refactor/adapter-lifecycle`, from main `e9f5e9d`. PR 1 of 2; Cast, Android TV, webOS and Tizen are not
  restructured here (Task 2 only renames the exception types they catch).
- Visible changes in PR 1: web requests run on virtual threads; Sonos and UPnP neither publish nor accept commands after
  `close()`; a Bluetooth command that races `close()` answers with its own result. Every error text stays as it is
  (the renderers' texts already equal `DeviceCalls`').
- Protocol packages (`..protocol..`) depend only on protocol packages and `adapters.net`. A class a protocol class
  throws lives in `adapters.net`; anything that builds a core exception or core state lives in `adapters.support` or the
  session's package.
- A state listener's `RuntimeException` is logged ("A device state listener failed for {id}"); an `Error` propagates.
- The frozen store shrinks from 42 to 25 lines in Task 3 and is committed smaller; never refreeze.
- `scripts/gradle.sh build` green after every task; `scripts/e2e.sh -Pe2eBrowsers=chromium` green at the end.
- Stage only the files a task changed. Conventional Commits with the session trailer, in the spec's PR 1 order.

**Rulings made while planning (the spec's sketches left these open):**
- **`StatePublisher.announce()`.** Every poll-style session publishes its initial state unconditionally at start
  (`UpnpSessionTest` asserts the first state is DISCONNECTED). Deduplication would swallow it, so the publisher gets
  `announce()`: tell the listener the current state, once, whatever it was told before.
- **`SessionLoop.cancelPending()`.** The poller's `pollNow` and `reconnectNow` drop the pending task before acting.
- **`Reconnector.stopped()`**, for tests and for PR 2's sessions to ask.
- **`RendererStatePoller`** takes the device id for its logs and the timings under their existing names (`pollInterval`
  while something plays, `idlePollInterval` otherwise). `read` throws `SoapFault` as well as `IOException`, so a link can
  tell a refusal from a lost renderer. `lost()` takes no cause: each session logs it in its own words.
- **Sonos reads volume after position** (the shared order). A volume fault still fails the poll; the transport read just
  before it already counts for the next poll delay.
- **`ReconnectingPollerTest.otherPollFailuresKeepPolling` is dropped.** The poller no longer knows `SoapFault`; the
  links log a fault during a poll, which `UpnpSessionTest.garbageAnswersAreIgnoredWhilePolling` covers.
- **`RendererCommands` uses `DeviceCalls` from Task 3**, once it has left `upnp.protocol`; a protocol class may not use
  `adapters.support`. Task 2 only changes the exception type it catches.
- **Bluetooth's loop is injectable** through a package-private constructor with eight parameters (`java:S107`
  suppressed with a reason), so a test can close the loop under a command. That test's RED is a compile failure (the
  seam is new); `SessionLoopTest.afterCloseNothingRunsAndNothingThrows` proves the behaviour.
- **The race tests use a step that ignores the interrupt.** `close()` interrupts the poll loop, which aborts almost
  every connect; the race only bites when a step had already finished. UPnP's test holds its `locator`, Sonos's its
  `Clock` (read right after the topology call), each waiting through the interrupt.

## Review Focus

1. **A slow or blocked state listener must not stall readers of the state** (`state()` on request threads, the setup
   page). Pinned in Task 1: `StatePublisherTest.currentDoesNotWaitForASlowListener`.
2. **`close()` must still interrupt a running poll**, which UPnP relies on to abandon a description fetch at once.
   Pinned in Task 1: `SessionLoopTest.closeInterruptsARunningTask`.
3. **A command's poll request while a poll is scheduled must not start a second poll chain.** Pinned in Task 1:
   `SessionLoopTest.scheduleReplacesThePendingTask`.
4. **A connect that finishes after `close()` neither publishes nor re-enables commands.** Pinned in Task 4:
   `UpnpSessionTest.aConnectThatFinishesAfterCloseNeitherPublishesNorTakesCommands`,
   `SonosSessionTest.aConnectThatFinishesAfterCloseNeitherPublishesNorTakesCommands`.
5. **A listener's `Error` stops the session, a `RuntimeException` does not.** Pinned in Task 1:
   `StatePublisherTest.anErrorFromTheListenerPropagates`, `aListenerThatThrowsIsLoggedAndTheStateStillTakesEffect`.

---

## File Structure

Main, under `src/main/java/dev/andre/homecontrol/adapters/`:

| File | Change | Responsibility |
| --- | --- | --- |
| `support/StatePublisher.java` | new (T1) | a session's state as its listener sees it |
| `support/SessionLoop.java` | new (T1) | a session's one virtual thread and its pending task |
| `support/Backoff.java` | new (T1) | doubling retry delay |
| `support/ConnectionSlot.java` | new (T1) | one live connection, closed exactly once |
| `support/Reconnector.java` | new (T1) | drives a push-style `connect()` (used in PR 2) |
| `support/ReconnectingPoller.java` | moved from `upnp/protocol`, rebuilt (T1) | connect-then-poll loop |
| `net/DeviceTimeoutException.java`, `net/DeviceRefusedException.java` | new (T2) | what protocols throw |
| `support/DeviceCalls.java` | new (T2) | protocol failure → core exception |
| `cast/protocol/CastTimeoutException.java`, `upnp/protocol/SoapTimeoutException.java`, `webos/SsapTimeoutException.java` | deleted (T2) | |
| `support/RendererCommands.java`, `support/RendererFaultException.java`, `support/NowPlayings.java` | moved from `upnp/protocol` (T3) | |
| `support/RendererStatePoller.java` | new (T3) | renderer state for Sonos and UPnP |
| `upnp/UpnpSession.java`, `sonos/SonosSession.java` | T1, T3, T4 | |
| `bluetooth/BluetoothSpeakerSession.java` | T5 | |
| `cast/protocol/CastTls.java`, `net/InsecureTls.java` | T6 | |

Also `src/main/resources/application.yaml` (T7), `docs/dev/architecture.md` (T8),
`src/test/java/dev/andre/homecontrol/ArchitectureTest.java` and `src/test/archunit-store/3fa162ba-…` (T3).

The executor's helpers (`move.py`: `edit`, `add_import`, `read`, `write`; `extract.py`; `prune.py`; `t.sh`;
`commit_changed.py`) are installed in the workspace `.superpowers/sdd/2026-10-01-phase-2d-pr1-support-toolkit/` at
setup. `edit(path, [(old, new[, count])])` fails without writing when `old` does not occur exactly `count` times.

---

### Task 1: The toolkit, and the poller in `adapters.support`

**Files:**
- Create: `support/StatePublisher.java`, `support/SessionLoop.java`, `support/Backoff.java`,
  `support/ConnectionSlot.java`, `support/Reconnector.java` (main); `StatePublisherTest`, `SessionLoopTest`,
  `BackoffTest`, `ConnectionSlotTest`, `ReconnectorTest` in `src/test/java/dev/andre/homecontrol/adapters/support/`
- Move: `upnp/protocol/ReconnectingPoller.java` → `support/ReconnectingPoller.java` (rebuilt);
  `upnp/protocol/ReconnectingPollerTest.java` → `support/ReconnectingPollerTest.java`
- Modify: `upnp/UpnpSession.java`, `sonos/SonosSession.java` (the poller's import; links throw only `IOException`)

**Interfaces:**
- Produces (all `public final` in `dev.andre.homecontrol.adapters.support`):
  - `StatePublisher(String deviceId, DeviceState initial, Consumer<DeviceState> listener)`: `DeviceState current()`,
    `DeviceState update(UnaryOperator<DeviceState>)`, `void publish(DeviceState)`, `void announce()`, `void close()`.
  - `SessionLoop(String name) implements AutoCloseable`: `boolean execute(Runnable)`,
    `boolean schedule(Runnable, Duration)`, `void cancelPending()`, `boolean every(Runnable, Duration)`, `void close()`.
  - `Backoff(Duration initial, Duration max)`: `Duration next()`, `void reset()`.
  - `ConnectionSlot<C extends AutoCloseable>(String name)`: `boolean set(C)`, `Optional<C> current()`,
    `boolean takeIf(C)`, `void close()`.
  - `Reconnector(SessionLoop, Backoff, Supplier<Reconnector.Outcome>)`, `enum Outcome { CONNECTED, PENDING, RETRY, STOP }`:
    `start()`, `lost()`, `connected()`, `reconnectNow()`, `retryIn(Duration)`, `boolean stopped()`.
  - `ReconnectingPoller(String name, Duration initialBackoff, Duration maxBackoff, Link) implements AutoCloseable`,
    `interface Link { void connect() throws IOException; void poll() throws IOException; Duration nextPollDelay(); void disconnected(Exception cause); }`:
    `start()`, `boolean connected()`, `pollNow()`, `reconnectNow()`, `close()`.

- [ ] **Step 1: Write the toolkit's tests.**

```java
// File: src/test/java/dev/andre/homecontrol/adapters/support/StatePublisherTest.java
package dev.andre.homecontrol.adapters.support;

import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StatePublisherTest {

    private static final DeviceState CONNECTED = DeviceState.initial().withStatus(DeviceStatus.CONNECTED);

    private final List<DeviceState> seen = new CopyOnWriteArrayList<>();
    private final StatePublisher publisher = new StatePublisher("tv", DeviceState.initial(), seen::add);

    @Test
    void publishesAChangeTheUiShowsAndRemembersIt() {
        publisher.publish(CONNECTED);

        assertThat(seen).containsExactly(CONNECTED);
        assertThat(publisher.current()).isEqualTo(CONNECTED);
    }

    @Test
    void aStateThatDiffersOnlyInItsTimeIsNotPublished() {
        publisher.publish(CONNECTED);
        publisher.publish(CONNECTED.withStatus(DeviceStatus.CONNECTED));

        assertThat(seen).containsExactly(CONNECTED);
        assertThat(publisher.current()).isSameAs(CONNECTED);
    }

    @Test
    void announceTellsTheListenerTheCurrentStateAgain() {
        publisher.publish(CONNECTED);
        publisher.announce();

        assertThat(seen).containsExactly(CONNECTED, CONNECTED);
    }

    @Test
    void updateChangesTheCurrentStateAndPublishesTheResult() {
        DeviceState result = publisher.update(state -> state.withPower(true));

        assertThat(result.powerOn()).isTrue();
        assertThat(seen).singleElement().satisfies(state -> assertThat(state.powerOn()).isTrue());
    }

    @Test
    void concurrentUpdatesLoseNoChange() {
        StatePublisher counting = new StatePublisher("tv", DeviceState.initial(), state -> { });
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 100; i++) {
                pool.submit(() -> counting.update(state -> state.withVolume(state.volumeLevel() + 1, 1000, false)));
            }
        }

        assertThat(counting.current().volumeLevel()).isEqualTo(100);
    }

    @Test
    void nothingIsPublishedAfterClose() {
        publisher.close();

        publisher.publish(CONNECTED);
        publisher.update(state -> state.withPower(true));
        publisher.announce();

        assertThat(seen).isEmpty();
        assertThat(publisher.current().status()).isEqualTo(DeviceStatus.DISCONNECTED);
    }

    @Test
    void aListenerThatThrowsIsLoggedAndTheStateStillTakesEffect() {
        StatePublisher failing = new StatePublisher("tv", DeviceState.initial(), state -> {
            throw new IllegalStateException("a subscriber failed");
        });

        assertThatCode(() -> failing.publish(CONNECTED)).doesNotThrowAnyException();
        assertThat(failing.current()).isEqualTo(CONNECTED);
    }

    @Test
    void anErrorFromTheListenerPropagates() {
        StatePublisher failing = new StatePublisher("tv", DeviceState.initial(), state -> {
            throw new AssertionError("fatal");
        });

        assertThatThrownBy(() -> failing.publish(CONNECTED)).isInstanceOf(AssertionError.class);
    }

    @Test
    void currentDoesNotWaitForASlowListener() throws Exception {
        CountDownLatch inListener = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        StatePublisher slow = new StatePublisher("tv", DeviceState.initial(), state -> {
            inListener.countDown();
            awaitQuietly(release);
        });
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            pool.submit(() -> slow.publish(CONNECTED));
            assertThat(inListener.await(5, TimeUnit.SECONDS)).isTrue();

            CompletableFuture<DeviceState> read = CompletableFuture.supplyAsync(slow::current, pool);

            assertThat(read.get(5, TimeUnit.SECONDS)).isEqualTo(CONNECTED);
            release.countDown();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }
}
```

```java
// File: src/test/java/dev/andre/homecontrol/adapters/support/SessionLoopTest.java
package dev.andre.homecontrol.adapters.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class SessionLoopTest {

    private final SessionLoop loop = new SessionLoop("test-loop");

    @AfterEach
    void tearDown() {
        loop.close();
    }

    @Test
    void runsEveryTaskOnOneNamedVirtualThread() throws Exception {
        CompletableFuture<Thread> first = new CompletableFuture<>();
        CompletableFuture<Thread> second = new CompletableFuture<>();

        loop.execute(() -> first.complete(Thread.currentThread()));
        loop.execute(() -> second.complete(Thread.currentThread()));

        assertThat(first.get(5, TimeUnit.SECONDS).isVirtual()).isTrue();
        assertThat(first.get().getName()).isEqualTo("test-loop");
        assertThat(second.get(5, TimeUnit.SECONDS)).isSameAs(first.get());
    }

    @Test
    void scheduleReplacesThePendingTask() {
        AtomicInteger replaced = new AtomicInteger();
        AtomicInteger replacement = new AtomicInteger();

        loop.schedule(replaced::incrementAndGet, Duration.ofMillis(200));
        loop.schedule(replacement::incrementAndGet, Duration.ofMillis(50));

        await().atMost(Duration.ofSeconds(2)).until(() -> replacement.get() == 1);
        await().during(Duration.ofMillis(400)).atMost(Duration.ofSeconds(2)).until(() -> replaced.get() == 0);
    }

    @Test
    void cancelPendingDropsTheScheduledTask() {
        AtomicInteger runs = new AtomicInteger();
        loop.schedule(runs::incrementAndGet, Duration.ofMillis(100));

        loop.cancelPending();

        await().during(Duration.ofMillis(400)).atMost(Duration.ofSeconds(2)).until(() -> runs.get() == 0);
    }

    @Test
    void everyKeepsRunningAfterATaskThatThrows() {
        AtomicInteger runs = new AtomicInteger();

        loop.every(() -> {
            if (runs.incrementAndGet() == 1) {
                throw new IllegalStateException("the first run fails");
            }
        }, Duration.ofMillis(20));

        await().atMost(Duration.ofSeconds(2)).until(() -> runs.get() >= 3);
    }

    @Test
    void afterCloseNothingRunsAndNothingThrows() {
        AtomicInteger runs = new AtomicInteger();
        loop.close();

        assertThat(loop.execute(runs::incrementAndGet)).isFalse();
        assertThat(loop.schedule(runs::incrementAndGet, Duration.ZERO)).isFalse();
        assertThat(loop.every(runs::incrementAndGet, Duration.ofMillis(10))).isFalse();
        await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2)).until(() -> runs.get() == 0);
    }

    @Test
    void closeInterruptsARunningTask() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        loop.execute(() -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException _) {
                interrupted.set(true);
            }
        });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        loop.close();

        await().atMost(Duration.ofSeconds(2)).untilTrue(interrupted);
    }
}
```

```java
// File: src/test/java/dev/andre/homecontrol/adapters/support/BackoffTest.java
package dev.andre.homecontrol.adapters.support;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BackoffTest {

    @Test
    void startsAtTheInitialDelayAndDoublesUpToTheMaximum() {
        Backoff backoff = new Backoff(Duration.ofSeconds(1), Duration.ofSeconds(5));

        assertThat(List.of(backoff.next(), backoff.next(), backoff.next(), backoff.next(), backoff.next()))
                .containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4),
                        Duration.ofSeconds(5), Duration.ofSeconds(5));
    }

    @Test
    void resetStartsOverAtTheInitialDelay() {
        Backoff backoff = new Backoff(Duration.ofSeconds(1), Duration.ofSeconds(60));
        backoff.next();
        backoff.next();

        backoff.reset();

        assertThat(backoff.next()).isEqualTo(Duration.ofSeconds(1));
    }
}
```

```java
// File: src/test/java/dev/andre/homecontrol/adapters/support/ConnectionSlotTest.java
package dev.andre.homecontrol.adapters.support;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class ConnectionSlotTest {

    /** Counts how often it was closed. */
    private static class Connection implements AutoCloseable {
        final AtomicInteger closes = new AtomicInteger();

        @Override
        public void close() throws IOException {
            closes.incrementAndGet();
        }
    }

    private final ConnectionSlot<Connection> slot = new ConnectionSlot<>("test");

    @Test
    void holdsTheConnectionSetIntoIt() {
        Connection connection = new Connection();

        assertThat(slot.set(connection)).isTrue();

        assertThat(slot.current()).containsSame(connection);
        assertThat(connection.closes).hasValue(0);
    }

    @Test
    void setClosesTheConnectionItReplaces() {
        Connection first = new Connection();
        Connection second = new Connection();
        slot.set(first);

        slot.set(second);

        assertThat(first.closes).hasValue(1);
        assertThat(second.closes).hasValue(0);
        assertThat(slot.current()).containsSame(second);
    }

    @Test
    void takeIfClosesOnlyTheConnectionItExpects() {
        Connection current = new Connection();
        Connection stale = new Connection();
        slot.set(current);

        assertThat(slot.takeIf(stale)).isFalse();
        assertThat(current.closes).hasValue(0);
        assertThat(slot.takeIf(current)).isTrue();
        assertThat(slot.takeIf(current)).isFalse();

        assertThat(current.closes).hasValue(1);
        assertThat(slot.current()).isEmpty();
    }

    @Test
    void closeClosesTheCurrentConnectionAndRefusesLaterOnes() {
        Connection current = new Connection();
        Connection late = new Connection();
        slot.set(current);

        slot.close();
        boolean accepted = slot.set(late);
        slot.close();

        assertThat(current.closes).hasValue(1);
        assertThat(accepted).isFalse();
        assertThat(late.closes).hasValue(1);
        assertThat(slot.current()).isEmpty();
    }

    @Test
    void racingTakesCloseAConnectionExactlyOnce() {
        for (int round = 0; round < 200; round++) {
            ConnectionSlot<Connection> racing = new ConnectionSlot<>("race");
            Connection connection = new Connection();
            racing.set(connection);
            try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                pool.submit(() -> racing.takeIf(connection));
                pool.submit(racing::close);
                pool.submit(() -> racing.takeIf(connection));
            }
            assertThat(connection.closes).hasValue(1);
        }
    }

    @Test
    void aConnectionThatFailsToCloseIsStillGivenUp() {
        Connection broken = new Connection() {
            @Override
            public void close() throws IOException {
                super.close();
                throw new IOException("already reset");
            }
        };
        slot.set(broken);

        assertThatCode(slot::close).doesNotThrowAnyException();
        assertThat(slot.current()).isEmpty();
        assertThat(broken.closes).hasValue(1);
    }
}
```

```java
// File: src/test/java/dev/andre/homecontrol/adapters/support/ReconnectorTest.java
package dev.andre.homecontrol.adapters.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;

import static dev.andre.homecontrol.adapters.support.Reconnector.Outcome.CONNECTED;
import static dev.andre.homecontrol.adapters.support.Reconnector.Outcome.PENDING;
import static dev.andre.homecontrol.adapters.support.Reconnector.Outcome.RETRY;
import static dev.andre.homecontrol.adapters.support.Reconnector.Outcome.STOP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class ReconnectorTest {

    private final SessionLoop loop = new SessionLoop("reconnector-test");
    private final List<Long> attempts = new CopyOnWriteArrayList<>();
    private final Deque<Object> script = new ConcurrentLinkedDeque<>();

    @AfterEach
    void tearDown() {
        loop.close();
    }

    /** Each attempt takes the next scripted answer (an outcome, or an exception to throw); CONNECTED once it runs out. */
    private Reconnector reconnector(Duration initial, Duration max, Object... answers) {
        script.addAll(List.of(answers));
        return new Reconnector(loop, new Backoff(initial, max), () -> {
            attempts.add(System.nanoTime());
            Object next = script.poll();
            if (next instanceof RuntimeException failure) {
                throw failure;
            }
            return next == null ? CONNECTED : (Reconnector.Outcome) next;
        });
    }

    private long gapMillis(int from) {
        return (attempts.get(from + 1) - attempts.get(from)) / 1_000_000;
    }

    @Test
    void theFirstAttemptRunsAtOnce() {
        reconnector(Duration.ofSeconds(10), Duration.ofSeconds(10)).start();

        await().atMost(Duration.ofSeconds(1)).until(() -> attempts.size() == 1);
    }

    @Test
    void aRetryWaitsTheGrowingBackoffUntilConnected() {
        reconnector(Duration.ofMillis(100), Duration.ofMillis(400), RETRY, RETRY, RETRY).start();

        await().atMost(Duration.ofSeconds(3)).until(() -> attempts.size() == 4);
        assertThat(gapMillis(0)).isGreaterThanOrEqualTo(90);
        assertThat(gapMillis(1)).isGreaterThanOrEqualTo(180);
        assertThat(gapMillis(2)).isGreaterThanOrEqualTo(360);
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2)).until(() -> attempts.size() == 4);
    }

    @Test
    void connectedResetsTheBackoffForTheNextLoss() {
        // Without the reset after the fourth attempt connected, the next delay would be 800 ms.
        Reconnector reconnector = reconnector(Duration.ofMillis(100), Duration.ofSeconds(10), RETRY, RETRY, RETRY);
        reconnector.start();
        await().atMost(Duration.ofSeconds(3)).until(() -> attempts.size() == 4);

        reconnector.lost();

        await().atMost(Duration.ofMillis(600)).until(() -> attempts.size() == 5);
    }

    @Test
    void aPendingAttemptWaitsForTheSessionToReport() {
        Reconnector reconnector = reconnector(Duration.ofMillis(100), Duration.ofSeconds(10), PENDING);
        reconnector.start();
        await().atMost(Duration.ofSeconds(1)).until(() -> attempts.size() == 1);
        await().during(Duration.ofMillis(400)).atMost(Duration.ofSeconds(2)).until(() -> attempts.size() == 1);

        reconnector.lost();

        await().atMost(Duration.ofSeconds(2)).until(() -> attempts.size() == 2);
    }

    @Test
    void stopEndsTheAttempts() {
        Reconnector reconnector = reconnector(Duration.ofMillis(50), Duration.ofMillis(50), STOP);
        reconnector.start();
        await().atMost(Duration.ofSeconds(1)).until(() -> attempts.size() == 1);

        reconnector.lost();
        reconnector.reconnectNow();
        reconnector.retryIn(Duration.ofMillis(10));

        await().during(Duration.ofMillis(400)).atMost(Duration.ofSeconds(2)).until(() -> attempts.size() == 1);
        assertThat(reconnector.stopped()).isTrue();
    }

    @Test
    void reconnectNowSkipsTheBackoff() {
        Reconnector reconnector = reconnector(Duration.ofSeconds(10), Duration.ofSeconds(10), RETRY);
        reconnector.start();
        await().atMost(Duration.ofSeconds(1)).until(() -> attempts.size() == 1);

        reconnector.reconnectNow();

        await().atMost(Duration.ofSeconds(1)).until(() -> attempts.size() == 2);
    }

    @Test
    void retryInWaitsTheGivenTimeInsteadOfTheBackoff() {
        Reconnector reconnector = reconnector(Duration.ofSeconds(10), Duration.ofSeconds(10), PENDING);
        reconnector.start();
        await().atMost(Duration.ofSeconds(1)).until(() -> attempts.size() == 1);

        reconnector.retryIn(Duration.ofMillis(50));

        await().atMost(Duration.ofSeconds(1)).until(() -> attempts.size() == 2);
    }

    @Test
    void aConnectThatThrowsIsRetried() {
        reconnector(Duration.ofMillis(50), Duration.ofMillis(50), new IllegalStateException("a bug")).start();

        await().atMost(Duration.ofSeconds(2)).until(() -> attempts.size() == 2);
    }

    @Test
    void nothingIsAttemptedAfterTheLoopCloses() {
        reconnector(Duration.ofMillis(50), Duration.ofMillis(50), RETRY, RETRY, RETRY).start();
        await().atMost(Duration.ofSeconds(1)).until(() -> attempts.size() == 1);

        loop.close();
        int atClose = attempts.size();

        await().during(Duration.ofMillis(400)).atMost(Duration.ofSeconds(2)).until(() -> attempts.size() == atClose);
    }
}
```

- [ ] **Step 2: Run them.** `scripts/gradle.sh test --tests 'dev.andre.homecontrol.adapters.support.*'`. Expected:
  compilation fails (`StatePublisher`, `SessionLoop`, `Backoff`, `ConnectionSlot`, `Reconnector` do not exist).

- [ ] **Step 3: Write the toolkit.**

```java
// File: src/main/java/dev/andre/homecontrol/adapters/support/StatePublisher.java
package dev.andre.homecontrol.adapters.support;

import dev.andre.homecontrol.core.DeviceState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * A device's state as its listener sees it. A state is published, and becomes {@link #current()}, only when it differs
 * from the last one in something the UI shows ({@link DeviceState#sameIgnoringTime}); nothing is published after
 * {@link #close()}. Publishing is serialized, so the listener sees the states in order; reading is not, so a slow
 * listener never holds up {@link #current()}. A listener's {@link RuntimeException} is logged and the session goes on;
 * an {@link Error} propagates.
 */
public final class StatePublisher {

    private static final Logger log = LoggerFactory.getLogger(StatePublisher.class);

    private final String deviceId;
    private final Consumer<DeviceState> listener;
    private final Object lock = new Object();
    // Immutable record, replaced wholesale under lock; readers need visibility only.
    @SuppressWarnings("java:S3077")
    private volatile DeviceState current;
    private boolean closed; // guarded by lock

    public StatePublisher(String deviceId, DeviceState initial, Consumer<DeviceState> listener) {
        this.deviceId = deviceId;
        this.listener = listener;
        this.current = initial;
    }

    public DeviceState current() {
        return current;
    }

    /** Applies {@code change} to the current state and publishes the result; returns the state now current. */
    public DeviceState update(UnaryOperator<DeviceState> change) {
        synchronized (lock) {
            publishLocked(change.apply(current));
            return current;
        }
    }

    public void publish(DeviceState next) {
        synchronized (lock) {
            publishLocked(next);
        }
    }

    /** Tells the listener the current state, whatever it was told before: a session's first report. */
    public void announce() {
        synchronized (lock) {
            if (!closed) {
                notifyListener(current);
            }
        }
    }

    public void close() {
        synchronized (lock) {
            closed = true;
        }
    }

    private void publishLocked(DeviceState next) {
        if (closed || next.sameIgnoringTime(current)) {
            return;
        }
        current = next;
        notifyListener(next);
    }

    private void notifyListener(DeviceState state) {
        try {
            listener.accept(state);
        } catch (RuntimeException e) {
            log.warn("A device state listener failed for {}", deviceId, e);
        }
    }
}
```

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
 * A session's one thread, a virtual one, with at most one pending one-shot task. Once {@link #close()} has run, every
 * call is a no-op that returns false: a caller racing close() never sees a {@link RejectedExecutionException}.
 */
public final class SessionLoop implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SessionLoop.class);

    private final String name;
    private final ScheduledExecutorService executor;
    private final Object lock = new Object();
    private ScheduledFuture<?> pending; // guarded by lock
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
            executor.execute(task);
            return true;
        } catch (RejectedExecutionException _) {
            return false;
        }
    }

    /** Runs {@code task} after {@code delay} as the one pending task, replacing the one scheduled before. */
    public boolean schedule(Runnable task, Duration delay) {
        synchronized (lock) {
            cancelPendingLocked();
            if (closed) {
                return false;
            }
            try {
                pending = executor.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS);
                return true;
            } catch (RejectedExecutionException _) {
                return false;
            }
        }
    }

    /** Drops the pending task if it has not started. */
    public void cancelPending() {
        synchronized (lock) {
            cancelPendingLocked();
        }
    }

    /** Runs {@code task} every {@code interval} after the previous run ends; a failed run is logged, the next still comes. */
    public boolean every(Runnable task, Duration interval) {
        if (closed) {
            return false;
        }
        try {
            executor.scheduleWithFixedDelay(() -> {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    log.warn("{}: a periodic task failed", name, e);
                }
            }, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
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

    private void cancelPendingLocked() {
        if (pending != null) {
            pending.cancel(false);
            pending = null;
        }
    }
}
```

```java
// File: src/main/java/dev/andre/homecontrol/adapters/support/Backoff.java
package dev.andre.homecontrol.adapters.support;

import java.time.Duration;

/** A retry delay that starts at {@code initial} and doubles with every use, up to {@code max}. */
public final class Backoff {

    private final Duration initial;
    private final Duration max;
    private Duration current; // guarded by this

    public Backoff(Duration initial, Duration max) {
        this.initial = initial;
        this.max = max;
        this.current = initial;
    }

    /** The delay to wait now; the following one doubles. */
    public synchronized Duration next() {
        Duration delay = current;
        Duration doubled = current.multipliedBy(2);
        current = doubled.compareTo(max) > 0 ? max : doubled;
        return delay;
    }

    public synchronized void reset() {
        current = initial;
    }
}
```

```java
// File: src/main/java/dev/andre/homecontrol/adapters/support/ConnectionSlot.java
package dev.andre.homecontrol.adapters.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Holds one live connection. Every connection leaves the slot exactly once, replaced, taken or refused, and the slot
 * closes it then; callers never close a connection they put in. After {@link #close()} the slot closes anything set
 * into it at once.
 */
public final class ConnectionSlot<C extends AutoCloseable> {

    private static final Logger log = LoggerFactory.getLogger(ConnectionSlot.class);

    private final String name;
    private C current; // guarded by this
    private boolean closed; // guarded by this

    public ConnectionSlot(String name) {
        this.name = name;
    }

    /** Makes {@code connection} the current one and closes the one before; false, and it is closed, once the slot is. */
    public boolean set(C connection) {
        C givenUp;
        boolean accepted;
        synchronized (this) {
            accepted = !closed;
            givenUp = accepted ? current : connection;
            if (accepted) {
                current = connection;
            }
        }
        closeQuietly(givenUp);
        return accepted;
    }

    public synchronized Optional<C> current() {
        return Optional.ofNullable(current);
    }

    /** Removes and closes {@code expected} if it is still the current connection. */
    public boolean takeIf(C expected) {
        synchronized (this) {
            if (current == null || current != expected) {
                return false;
            }
            current = null;
        }
        closeQuietly(expected);
        return true;
    }

    /** Removes and closes the current connection; every later {@link #set} is refused. */
    public void close() {
        C taken;
        synchronized (this) {
            closed = true;
            taken = current;
            current = null;
        }
        closeQuietly(taken);
    }

    private void closeQuietly(C connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (Exception e) {
            log.debug("{}: closing a connection failed: {}", name, e.getMessage());
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
 * for the session to call {@link #connected()} or {@link #lost()}; {@code STOP} ends the attempts. The session's
 * policies, such as when to stop, stay in its {@code connect()}.
 */
public final class Reconnector {

    public enum Outcome { CONNECTED, PENDING, RETRY, STOP }

    private static final Logger log = LoggerFactory.getLogger(Reconnector.class);

    private final SessionLoop loop;
    private final Backoff backoff;
    private final Supplier<Outcome> connect;
    private volatile boolean stopped;

    public Reconnector(SessionLoop loop, Backoff backoff, Supplier<Outcome> connect) {
        this.loop = loop;
        this.backoff = backoff;
        this.connect = connect;
    }

    public void start() {
        loop.execute(this::attempt);
    }

    /** The connection dropped: try again after the backoff's next delay. */
    public void lost() {
        if (!stopped) {
            loop.schedule(this::attempt, backoff.next());
        }
    }

    /** A {@code PENDING} attempt became usable. */
    public void connected() {
        backoff.reset();
    }

    /** Tries at once with a fresh backoff, unless stopped. */
    public void reconnectNow() {
        if (stopped) {
            return;
        }
        backoff.reset();
        loop.execute(() -> {
            loop.cancelPending();
            attempt();
        });
    }

    /** Tries after {@code wait} with a fresh backoff, unless stopped: a TV that was just woken. */
    public void retryIn(Duration wait) {
        if (stopped) {
            return;
        }
        backoff.reset();
        loop.schedule(this::attempt, wait);
    }

    public boolean stopped() {
        return stopped;
    }

    private void attempt() {
        if (stopped) {
            return;
        }
        Outcome outcome;
        try {
            outcome = connect.get();
        } catch (RuntimeException e) {
            log.warn("A connection attempt failed unexpectedly", e);
            outcome = Outcome.RETRY;
        }
        switch (outcome) {
            case CONNECTED -> backoff.reset();
            case PENDING -> {
                // The session reports connected() or lost().
            }
            case RETRY -> loop.schedule(this::attempt, backoff.next());
            case STOP -> stopped = true;
        }
    }
}
```

- [ ] **Step 4: Run the toolkit tests.** Same command. Expected: PASS (StatePublisher 9, SessionLoop 6, Backoff 2,
  ConnectionSlot 6, Reconnector 9).

- [ ] **Step 5: Move and rebuild the poller.** Move both files with `git mv` (history kept), then write the new poller
  over the moved file, and edit the moved test:

```bash
git mv src/main/java/dev/andre/homecontrol/adapters/upnp/protocol/ReconnectingPoller.java src/main/java/dev/andre/homecontrol/adapters/support/ReconnectingPoller.java
git mv src/test/java/dev/andre/homecontrol/adapters/upnp/protocol/ReconnectingPollerTest.java src/test/java/dev/andre/homecontrol/adapters/support/ReconnectingPollerTest.java
```

```java
// File: src/main/java/dev/andre/homecontrol/adapters/support/ReconnectingPoller.java
package dev.andre.homecontrol.adapters.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;

/**
 * One device's loop on its own {@link SessionLoop}: connect with a doubling {@link Backoff}, then poll at the delay the
 * link chooses. An {@link IOException} from a poll means the device is gone; a runtime failure is logged and polling
 * continues. A link handles its protocol's own refusals. All {@link Link} callbacks run on the loop thread.
 */
public final class ReconnectingPoller implements AutoCloseable {

    public interface Link {
        void connect() throws IOException;

        void poll() throws IOException;

        Duration nextPollDelay();

        void disconnected(Exception cause);
    }

    private static final Logger log = LoggerFactory.getLogger(ReconnectingPoller.class);

    private final String name;
    private final Link link;
    private final Backoff backoff;
    private final SessionLoop loop;

    private volatile boolean connected;
    private volatile boolean closed;

    public ReconnectingPoller(String name, Duration initialBackoff, Duration maxBackoff, Link link) {
        this.name = name;
        this.link = link;
        this.backoff = new Backoff(initialBackoff, maxBackoff);
        this.loop = new SessionLoop(name);
    }

    public void start() {
        loop.execute(this::attemptConnect);
    }

    public boolean connected() {
        return connected && !closed;
    }

    public void pollNow() {
        loop.execute(() -> {
            if (connected) {
                loop.cancelPending();
                pollOnce();
            }
        });
    }

    public void reconnectNow() {
        loop.execute(() -> {
            if (!connected) {
                loop.cancelPending();
                backoff.reset();
                attemptConnect();
            }
        });
    }

    private void attemptConnect() {
        if (closed) {
            return;
        }
        try {
            link.connect();
            connected = true;
            backoff.reset();
            loop.schedule(this::pollOnce, link.nextPollDelay());
        } catch (IOException | RuntimeException e) {
            connected = false;
            link.disconnected(e);
            loop.schedule(this::attemptConnect, backoff.next());
        }
    }

    private void pollOnce() {
        if (closed || !connected) {
            return;
        }
        try {
            link.poll();
        } catch (IOException e) {
            connected = false;
            link.disconnected(e);
            loop.schedule(this::attemptConnect, backoff.next());
            return;
        } catch (RuntimeException e) {
            log.debug("{}: poll failed: {}", name, e.getMessage());
        }
        loop.schedule(this::pollOnce, link.nextPollDelay());
    }

    @Override
    public void close() {
        closed = true;
        connected = false;
        loop.close();
    }
}
```

  Save as `.superpowers/sdd/2026-10-01-phase-2d-pr1-support-toolkit/task1_poller_test.py` and run it:

```python
import sys
sys.path.insert(0, "/home/docker1/home-control/.claude/worktrees/phase-0-defect-fixes/.superpowers/sdd/2026-10-01-phase-2d-pr1-support-toolkit")
from move import TEST, edit

edit(TEST + "adapters/support/ReconnectingPollerTest.java", [
    ("package dev.andre.homecontrol.adapters.upnp.protocol;", "package dev.andre.homecontrol.adapters.support;"),
    ("        volatile SoapFault everyPollFailure;\n", ""),
    ("        public void poll() throws IOException, SoapFault {", "        public void poll() throws IOException {"),
    ("""            if (everyPollFailure != null) {
                throw everyPollFailure;
            }
""", ""),
    ("""    @Test
    void otherPollFailuresKeepPolling() {
        ScriptedLink link = new ScriptedLink();
        link.everyPollFailure = new SoapFault(501, "Action Failed");
        poller(link);

        await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> assertThat(link.polls).hasSizeGreaterThanOrEqualTo(2));
        int seen = link.polls.size();
        await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> assertThat(link.polls).hasSizeGreaterThan(seen));
        assertThat(link.disconnects).isEmpty();
    }

""", ""),
])
print("poller test moved")
```

- [ ] **Step 6: Point Sonos and UPnP at the moved poller.** Their links now throw only `IOException`: a fault while
  connecting is a failed attempt, a fault while polling is logged. Save as `task1_sessions.py` in the workspace and run
  it, then run `prune.py` on both files:

```python
import sys
sys.path.insert(0, "/home/docker1/home-control/.claude/worktrees/phase-0-defect-fixes/.superpowers/sdd/2026-10-01-phase-2d-pr1-support-toolkit")
from move import MAIN, edit, add_import

POLLER = "dev.andre.homecontrol.adapters.support.ReconnectingPoller"

upnp = MAIN + "adapters/upnp/UpnpSession.java"
edit(upnp, [
    ("import dev.andre.homecontrol.adapters.upnp.protocol.ReconnectingPoller;\n", ""),
    ("""        @Override
        public void connect() throws IOException, SoapFault {
            Endpoints resolved = resolve();
            endpoints = resolved;
            try {
                readState(resolved);
            } catch (IOException | SoapFault | RuntimeException e) {
                endpoints = null;
                throw e;
            }
        }

        @Override
        public void poll() throws IOException, SoapFault {
            Endpoints current = endpoints;
            if (current != null) {
                readState(current);
            }
        }""",
     """        @Override
        public void connect() throws IOException {
            Endpoints resolved = resolve();
            endpoints = resolved;
            try {
                readState(resolved);
            } catch (SoapFault fault) {
                endpoints = null;
                throw new IOException(device.id() + " refused to report its state: " + fault.getMessage());
            } catch (IOException | RuntimeException e) {
                endpoints = null;
                throw e;
            }
        }

        @Override
        public void poll() throws IOException {
            Endpoints current = endpoints;
            if (current != null) {
                try {
                    readState(current);
                } catch (SoapFault fault) {
                    log.debug("{}: poll failed: {}", device.id(), fault.getMessage());
                }
            }
        }"""),
])
add_import(upnp, POLLER)

sonos = MAIN + "adapters/sonos/SonosSession.java"
edit(sonos, [
    ("import dev.andre.homecontrol.adapters.upnp.protocol.ReconnectingPoller;\n", ""),
    ("""        @Override
        public void connect() throws IOException, SoapFault {
            readTopology();
            try {
                sink = commands.sink(own(SonosEndpoints.CONNECTION_MANAGER_PATH, SonosEndpoints.CONNECTION_MANAGER));
            } catch (SoapFault _) {
                sink = ProtocolInfo.UNKNOWN;
            }
            readState();
            live = true;
        }

        @Override
        public void poll() throws IOException, SoapFault {
            if (Duration.between(topologyReadAt, clock.instant()).compareTo(timings.topologyInterval()) >= 0) {
                try {
                    readTopology();
                } catch (SoapFault fault) {
                    log.debug("{}: topology unavailable: {}", device.id(), fault.getMessage());
                }
            }
            readState();
        }""",
     """        @Override
        public void connect() throws IOException {
            try {
                readTopology();
                try {
                    sink = commands.sink(own(SonosEndpoints.CONNECTION_MANAGER_PATH, SonosEndpoints.CONNECTION_MANAGER));
                } catch (SoapFault _) {
                    sink = ProtocolInfo.UNKNOWN;
                }
                readState();
            } catch (SoapFault fault) {
                throw new IOException(device.id() + " refused to report its state: " + fault.getMessage());
            }
            live = true;
        }

        @Override
        public void poll() throws IOException {
            if (Duration.between(topologyReadAt, clock.instant()).compareTo(timings.topologyInterval()) >= 0) {
                try {
                    readTopology();
                } catch (SoapFault fault) {
                    log.debug("{}: topology unavailable: {}", device.id(), fault.getMessage());
                }
            }
            try {
                readState();
            } catch (SoapFault fault) {
                log.debug("{}: poll failed: {}", device.id(), fault.getMessage());
            }
        }"""),
])
add_import(sonos, POLLER)
print("sessions on the moved poller")
```

- [ ] **Step 7: Run the adapter tests.** `scripts/gradle.sh test --tests 'dev.andre.homecontrol.adapters.support.*'
  --tests 'dev.andre.homecontrol.adapters.upnp.*' --tests 'dev.andre.homecontrol.adapters.sonos.*'`. Expected: PASS,
  including the moved `ReconnectingPollerTest` (7) and every UPnP and Sonos test.

- [ ] **Step 8: Run the build.** `scripts/gradle.sh build`. Expected: green.

- [ ] **Step 9: Commit** every path the task touched (renames included) with:

```
refactor: an adapter lifecycle toolkit in adapters.support

StatePublisher (one deduplication rule, a closed guard, a listener's RuntimeException logged and
its Error propagated), SessionLoop (one virtual thread, one pending task, a no-op after close),
Backoff, ConnectionSlot (every connection closed exactly once) and Reconnector (drives a push
session's connect, for PR 2). ReconnectingPoller moves here from upnp.protocol, rebuilt on
SessionLoop and Backoff, and no longer knows SOAP: the Sonos and UPnP links handle their own
faults as before.
```

---

### Task 2: One timeout type, one refusal type, one translation

**Files:**
- Create: `adapters/net/DeviceTimeoutException.java`, `adapters/net/DeviceRefusedException.java`,
  `adapters/support/DeviceCalls.java`; test `src/test/java/dev/andre/homecontrol/adapters/support/DeviceCallsTest.java`
- Delete: `cast/protocol/CastTimeoutException.java`, `upnp/protocol/SoapTimeoutException.java`,
  `webos/SsapTimeoutException.java`
- Modify: `cast/protocol/CastConnection.java`, `upnp/protocol/SoapClient.java`, `webos/SsapConnection.java` (throw the
  new type); `webos/SsapException.java`, `tizen/DialException.java` (extend `DeviceRefusedException`);
  `cast/CastSession.java`, `webos/WebOsSession.java`, `upnp/protocol/RendererCommands.java` (catch the new type);
  tests `SoapClientTest`, `CastConnectionTest`, `SsapConnectionTest`

**Interfaces:**
- Produces: `public class DeviceTimeoutException extends IOException` and `public class DeviceRefusedException extends
  IOException` in `adapters.net`, each with a `(String message)` constructor; `public final class DeviceCalls` with
  `static <T> T run(String deviceName, String what, Call<T> call)`, `static void run(String deviceName, String what,
  VoidCall call)`, `static DeviceOfflineException notConnected(String deviceName)`, and the nested
  `interface Call<T> { T run() throws IOException; }`, `interface VoidCall { void run() throws IOException; }`.

- [ ] **Step 1: Write the failing test.**

```java
// File: src/test/java/dev/andre/homecontrol/adapters/support/DeviceCallsTest.java
package dev.andre.homecontrol.adapters.support;

import dev.andre.homecontrol.adapters.net.DeviceRefusedException;
import dev.andre.homecontrol.adapters.net.DeviceTimeoutException;
import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.DeviceOfflineException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeviceCallsTest {

    @Test
    void aTimeoutIsAFailedAction() {
        assertThatThrownBy(() -> DeviceCalls.run("Kitchen TV", "set the volume", () -> {
            throw new DeviceTimeoutException("no answer within 1000 ms");
        })).isInstanceOf(ActionFailedException.class)
                .hasMessage("Kitchen TV did not answer in time when asked to set the volume");
    }

    @Test
    void aRefusalIsAFailedActionWithTheDevicesReason() {
        assertThatThrownBy(() -> DeviceCalls.run("Kitchen TV", "set the volume", () -> {
            throw new DeviceRefusedException("returnValue false");
        })).isInstanceOf(ActionFailedException.class)
                .hasMessage("Kitchen TV refused to set the volume: returnValue false");
    }

    @Test
    void anyOtherIoFailureMeansTheDeviceIsOffline() {
        assertThatThrownBy(() -> DeviceCalls.run("Kitchen TV", "set the volume", () -> {
            throw new IOException("Connection reset");
        })).isInstanceOf(DeviceOfflineException.class)
                .hasMessage("Kitchen TV could not be reached to set the volume");
    }

    @Test
    void aResultPassesThrough() {
        assertThat(DeviceCalls.run("Kitchen TV", "read the volume", () -> 42)).isEqualTo(42);
    }

    @Test
    void aCallWithoutResultRuns() {
        AtomicBoolean ran = new AtomicBoolean();

        DeviceCalls.run("Kitchen TV", "mute", () -> ran.set(true));

        assertThat(ran).isTrue();
    }

    @Test
    void runtimeFailuresPassThroughUntouched() {
        IllegalStateException bug = new IllegalStateException("a bug");

        assertThatThrownBy(() -> DeviceCalls.run("Kitchen TV", "mute", () -> {
            throw bug;
        })).isSameAs(bug);
    }

    @Test
    void notConnectedNamesTheDevice() {
        assertThat(DeviceCalls.notConnected("Kitchen TV")).isInstanceOf(DeviceOfflineException.class)
                .hasMessage("Kitchen TV is not connected");
    }
}
```

- [ ] **Step 2: Run it.** `scripts/gradle.sh test --tests 'dev.andre.homecontrol.adapters.support.DeviceCallsTest'`.
  Expected: compilation fails (`DeviceCalls`, `DeviceTimeoutException`, `DeviceRefusedException` do not exist).

- [ ] **Step 3: Write the types and the translation.**

```java
// File: src/main/java/dev/andre/homecontrol/adapters/net/DeviceTimeoutException.java
package dev.andre.homecontrol.adapters.net;

import java.io.IOException;

/** The device is reachable but did not answer a request in time. */
public class DeviceTimeoutException extends IOException {
    public DeviceTimeoutException(String message) {
        super(message);
    }
}
```

```java
// File: src/main/java/dev/andre/homecontrol/adapters/net/DeviceRefusedException.java
package dev.andre.homecontrol.adapters.net;

import java.io.IOException;

/** The device answered, and refused the request. The connection itself is fine. */
public class DeviceRefusedException extends IOException {
    public DeviceRefusedException(String message) {
        super(message);
    }
}
```

```java
// File: src/main/java/dev/andre/homecontrol/adapters/support/DeviceCalls.java
package dev.andre.homecontrol.adapters.support;

import dev.andre.homecontrol.adapters.net.DeviceRefusedException;
import dev.andre.homecontrol.adapters.net.DeviceTimeoutException;
import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.DeviceOfflineException;

import java.io.IOException;

/**
 * Runs a call to a device and says what went wrong in the words of the rest of the system: no answer in time or a
 * refusal is a failed action, any other I/O failure an offline device. {@code what} is a verb phrase such as
 * "set the volume".
 */
public final class DeviceCalls {

    @FunctionalInterface
    public interface Call<T> {
        T run() throws IOException;
    }

    @FunctionalInterface
    public interface VoidCall {
        void run() throws IOException;
    }

    private DeviceCalls() {
    }

    public static <T> T run(String deviceName, String what, Call<T> call) {
        try {
            return call.run();
        } catch (DeviceTimeoutException _) {
            throw new ActionFailedException(deviceName + " did not answer in time when asked to " + what);
        } catch (DeviceRefusedException e) {
            throw new ActionFailedException(deviceName + " refused to " + what + ": " + e.getMessage());
        } catch (IOException _) {
            throw new DeviceOfflineException(deviceName + " could not be reached to " + what);
        }
    }

    public static void run(String deviceName, String what, VoidCall call) {
        run(deviceName, what, () -> {
            call.run();
            return null;
        });
    }

    public static DeviceOfflineException notConnected(String deviceName) {
        return new DeviceOfflineException(deviceName + " is not connected");
    }
}
```

- [ ] **Step 4: Run it.** Same command. Expected: PASS (7).

- [ ] **Step 5: Replace the three timeout clones.** Save as `task2_timeouts.py` in the workspace and run it; it deletes
  the three classes with `git rm` and rewrites their throwers, catchers and tests:

```python
import subprocess
import sys
sys.path.insert(0, "/home/docker1/home-control/.claude/worktrees/phase-0-defect-fixes/.superpowers/sdd/2026-10-01-phase-2d-pr1-support-toolkit")
from move import REPO, MAIN, TEST, read, write, edit, add_import, remove_import

NET = "dev.andre.homecontrol.adapters.net."
for clone in ("adapters/cast/protocol/CastTimeoutException.java", "adapters/upnp/protocol/SoapTimeoutException.java",
              "adapters/webos/SsapTimeoutException.java"):
    subprocess.run(["git", "rm", "-q", MAIN + clone], cwd=REPO, check=True)

def retype(path, old_simple, old_qualified=None):
    text = read(path)
    assert old_simple in text, (path, old_simple)
    write(path, text.replace(old_simple, "DeviceTimeoutException"))
    if old_qualified:
        remove_import(path, old_qualified)
    add_import(path, NET + "DeviceTimeoutException")

retype(MAIN + "adapters/cast/protocol/CastConnection.java", "CastTimeoutException")
retype(MAIN + "adapters/upnp/protocol/SoapClient.java", "SoapTimeoutException")
retype(MAIN + "adapters/upnp/protocol/RendererCommands.java", "SoapTimeoutException")
retype(MAIN + "adapters/webos/SsapConnection.java", "SsapTimeoutException")
retype(MAIN + "adapters/webos/WebOsSession.java", "SsapTimeoutException")
retype(MAIN + "adapters/cast/CastSession.java", "CastTimeoutException",
       "dev.andre.homecontrol.adapters.cast.protocol.CastTimeoutException")
retype(TEST + "adapters/upnp/protocol/SoapClientTest.java", "SoapTimeoutException")
retype(TEST + "adapters/cast/protocol/CastConnectionTest.java", "CastTimeoutException")
retype(TEST + "adapters/webos/SsapConnectionTest.java", "SsapTimeoutException")

for path, simple in ((MAIN + "adapters/webos/SsapException.java", "SsapException"),
                     (MAIN + "adapters/tizen/DialException.java", "DialException")):
    edit(path, [(f"class {simple} extends IOException {{", f"class {simple} extends DeviceRefusedException {{")])
    remove_import(path, "java.io.IOException")
    add_import(path, NET + "DeviceRefusedException")
print("one timeout type, one refusal type")
```

- [ ] **Step 6: Compile and run the adapter tests.** `scripts/gradle.sh test --tests 'dev.andre.homecontrol.adapters.*'`.
  Expected: PASS. `grep -rn "CastTimeoutException\|SoapTimeoutException\|SsapTimeoutException" src` prints nothing.

- [ ] **Step 7: Run the build.** `scripts/gradle.sh build`. Expected: green; the frozen store unchanged (`git status`
  does not list `src/test/archunit-store`).

- [ ] **Step 8: Commit** every path the task touched with:

```
refactor: one timeout and one refusal type for devices, and DeviceCalls

DeviceTimeoutException replaces the Cast, SOAP and SSAP timeout clones, and DeviceRefusedException
becomes the base of webOS's SsapException and Tizen's DialException; both live in adapters.net so
protocol code may throw them. DeviceCalls turns them into the system's words: no answer in time
or a refusal is a failed action, any other I/O failure an offline device. The sessions adopt it
as they move onto the toolkit.
```

---

### Task 3: The renderer code leaves `upnp.protocol`; `RendererStatePoller`

**Files:**
- Move: `upnp/protocol/RendererCommands.java`, `RendererFaultException.java`, `NowPlayings.java` → `support/`;
  tests `upnp/protocol/RendererCommandsTest.java`, `NowPlayingsTest.java` → `src/test/java/.../adapters/support/`
- Create: `support/RendererStatePoller.java`; test `support/RendererStatePollerTest.java`
- Modify: `upnp/UpnpSession.java`, `sonos/SonosSession.java`, `ArchitectureTest.java`,
  `src/test/archunit-store/3fa162ba-7523-467d-adbd-20ce146a962d`

**Interfaces:**
- Consumes: `StatePublisher` (Task 1); `DeviceCalls` (Task 2).
- Produces: `public final class RendererStatePoller` with
  `record Endpoints(ServiceEndpoint avTransport, ServiceEndpoint renderingControl, int volumeMax, boolean volumeRequired)`,
  constructor `(String deviceId, RendererCommands commands, StatePublisher publisher, Duration pollInterval,
  Duration idlePollInterval)`, `void read(Endpoints) throws IOException, SoapFault`, `void played(PlayedItem)`,
  `Duration nextPollDelay()`, `void lost()`. `RendererCommands`, `RendererFaultException`, `NowPlayings` keep their
  APIs in `dev.andre.homecontrol.adapters.support`.

- [ ] **Step 1: Move the three classes and their tests.** Save as `task3_move.py` and run it:

```python
import re
import subprocess
from pathlib import Path

REPO = Path("/home/docker1/home-control/.claude/worktrees/phase-0-defect-fixes")
OLD = "dev.andre.homecontrol.adapters.upnp.protocol"
NEW = "dev.andre.homecontrol.adapters.support"
MAIN = REPO / "src/main/java/dev/andre/homecontrol/adapters"
TEST = REPO / "src/test/java/dev/andre/homecontrol/adapters"
MOVED = ["RendererCommands", "RendererFaultException", "NowPlayings"]

def move(root, name):
    source = root / "upnp/protocol" / f"{name}.java"
    target = root / "support" / f"{name}.java"
    target.parent.mkdir(exist_ok=True)
    subprocess.run(["git", "mv", str(source), str(target)], cwd=REPO, check=True)
    text = target.read_text().replace(f"package {OLD};", f"package {NEW};", 1)
    target.write_text(text)
    return target

moved = [move(MAIN, name) for name in MOVED] + [move(TEST, name) for name in ("RendererCommandsTest", "NowPlayingsTest")]

# Qualified names of the moved classes everywhere.
for path in (REPO / "src").rglob("*.java"):
    text = path.read_text()
    changed = text
    for name in MOVED:
        changed = re.sub(rf"\b{re.escape(OLD)}\.{name}\b", f"{NEW}.{name}", changed)
    if changed != text:
        path.write_text(changed)

# The moved files used the rest of upnp.protocol without imports: import what they use.
protocol = sorted(p.stem for p in (MAIN / "upnp/protocol").glob("*.java"))
for path in moved:
    text = path.read_text()
    code = re.sub(r"^import .*$", "", text, flags=re.M)
    code = re.sub(r"/\*.*?\*/", "", code, flags=re.S)
    code = re.sub(r"//.*$", "", code, flags=re.M)
    wanted = [n for n in protocol if re.search(rf"(?<![\w.]){n}\b", code)]
    lines = "".join(f"import {OLD}.{n};\n" for n in wanted if f"import {OLD}.{n};" not in text)
    if lines:
        anchor = re.search(r"^import ", text, re.M)
        text = text[:anchor.start()] + lines + text[anchor.start():] if anchor else text
        path.write_text(text)
    print(path.relative_to(REPO), "imports", wanted)
```

  Then `scripts/gradle.sh compileJava compileTestJava`. Expected: success (sort any import out of order by hand only if
  the build complains; the project does not enforce order).

- [ ] **Step 2: Translate through `DeviceCalls`.** In `support/RendererCommands.java` replace `run`:

```java
    public <T> T run(String what, SoapCall<T> call) {
        return DeviceCalls.run(deviceName, what, () -> {
            try {
                return call.call();
            } catch (SoapFault fault) {
                throw new RendererFaultException(deviceName + " refused to " + what + " (" + fault.getMessage() + ")",
                        fault.errorCode());
            }
        });
    }
```

  and remove its now-unused imports with `prune.py`. Run `scripts/gradle.sh test --tests
  'dev.andre.homecontrol.adapters.support.RendererCommandsTest'`. Expected: PASS (the texts are unchanged).

- [ ] **Step 3: Write `RendererStatePollerTest`.**

```java
// File: src/test/java/dev/andre/homecontrol/adapters/support/RendererStatePollerTest.java
package dev.andre.homecontrol.adapters.support;

import dev.andre.homecontrol.adapters.upnp.FakeUpnpRenderer;
import dev.andre.homecontrol.adapters.upnp.protocol.PlayedItem;
import dev.andre.homecontrol.adapters.upnp.protocol.ServiceEndpoint;
import dev.andre.homecontrol.adapters.upnp.protocol.SoapClient;
import dev.andre.homecontrol.adapters.upnp.protocol.SoapFault;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.NowPlaying;
import dev.andre.homecontrol.core.PlaybackState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static dev.andre.homecontrol.adapters.upnp.FakeUpnpRenderer.AV_TRANSPORT;
import static dev.andre.homecontrol.adapters.upnp.FakeUpnpRenderer.RENDERING_CONTROL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RendererStatePollerTest {

    private static final String STREAM = "http://192.168.1.20:8096/Audio/c0ffee/stream.flac";

    private final List<DeviceState> seen = new CopyOnWriteArrayList<>();
    private FakeUpnpRenderer fake;
    private StatePublisher publisher;
    private RendererStatePoller poller;
    private ServiceEndpoint av;
    private ServiceEndpoint rc;

    @BeforeEach
    void setUp() throws IOException {
        fake = new FakeUpnpRenderer();
        String base = "http://127.0.0.1:" + fake.port();
        av = new ServiceEndpoint(AV_TRANSPORT, URI.create(base + "/upnp/control/AVTransport1"), null);
        rc = new ServiceEndpoint(RENDERING_CONTROL, URI.create(base + "/upnp/control/RenderingControl1"), null);
        publisher = new StatePublisher("kitchen", DeviceState.initial(), seen::add);
        RendererCommands commands = new RendererCommands(
                new SoapClient(SoapClient.httpClient(Duration.ofSeconds(1)), Duration.ofSeconds(1)), "Kitchen Speaker");
        poller = new RendererStatePoller("kitchen", commands, publisher, Duration.ofMillis(100), Duration.ofSeconds(5));
    }

    @AfterEach
    void tearDown() {
        fake.close();
    }

    @Test
    void publishesConnectedWithTheVolumeInPercent() throws Exception {
        fake.setVolume(20);

        poller.read(new RendererStatePoller.Endpoints(av, rc, 100, false));

        DeviceState state = publisher.current();
        assertThat(state.status()).isEqualTo(DeviceStatus.CONNECTED);
        assertThat(state.powerOn()).isTrue();
        assertThat(state.volumeLevel()).isEqualTo(20);
        assertThat(state.volumeMax()).isEqualTo(100);
        assertThat(state.nowPlaying()).isNull();
        assertThat(seen).hasSize(1);
    }

    @Test
    void readsWhatPlaysFromTheRenderersMetadata() throws Exception {
        fake.playElsewhere(STREAM, Files.readString(Path.of("src/test/resources/fixtures/upnp/position-metadata.xml")));

        poller.read(new RendererStatePoller.Endpoints(av, rc, 100, false));

        assertThat(publisher.current().nowPlaying()).isNotNull()
                .extracting(NowPlaying::title, NowPlaying::state).containsExactly("Carrot Waltz", PlaybackState.PLAYING);
    }

    @Test
    void usesTheTitleItPlayedWhenTheRendererForgetsMetadata() throws Exception {
        fake.playElsewhere(STREAM, "");
        poller.played(new PlayedItem(STREAM, "Bunny Song"));

        poller.read(new RendererStatePoller.Endpoints(av, rc, 100, false));

        assertThat(publisher.current().nowPlaying()).extracting(NowPlaying::title).isEqualTo("Bunny Song");
    }

    @Test
    void anOptionalVolumeThatFaultsIsLeftOut() throws Exception {
        fake.fail("GetVolume", 501, "Action Failed", 1);

        poller.read(new RendererStatePoller.Endpoints(av, rc, 100, false));

        assertThat(publisher.current().status()).isEqualTo(DeviceStatus.CONNECTED);
        assertThat(publisher.current().volumeMax()).isZero();
    }

    @Test
    void aRequiredVolumeThatFaultsFailsTheRead() {
        fake.fail("GetVolume", 501, "Action Failed", 1);

        assertThatThrownBy(() -> poller.read(new RendererStatePoller.Endpoints(av, rc, 100, true)))
                .isInstanceOf(SoapFault.class);
        assertThat(seen).isEmpty();
    }

    @Test
    void withoutRenderingControlThereIsNoVolume() throws Exception {
        poller.read(new RendererStatePoller.Endpoints(av, null, 0, false));

        assertThat(publisher.current().status()).isEqualTo(DeviceStatus.CONNECTED);
        assertThat(publisher.current().volumeMax()).isZero();
    }

    @Test
    void pollsFasterWhileSomethingPlays() throws Exception {
        assertThat(poller.nextPollDelay()).isEqualTo(Duration.ofSeconds(5));
        fake.playElsewhere(STREAM, "");

        poller.read(new RendererStatePoller.Endpoints(av, rc, 100, false));

        assertThat(poller.nextPollDelay()).isEqualTo(Duration.ofMillis(100));
    }

    @Test
    void lostPublishesDisconnectedAndForgetsWhatPlays() throws Exception {
        fake.playElsewhere(STREAM, "");
        poller.read(new RendererStatePoller.Endpoints(av, rc, 100, false));

        poller.lost();

        assertThat(publisher.current().status()).isEqualTo(DeviceStatus.DISCONNECTED);
        assertThat(publisher.current().nowPlaying()).isNull();
        assertThat(poller.nextPollDelay()).isEqualTo(Duration.ofSeconds(5));
    }
}
```

- [ ] **Step 4: Run it.** `scripts/gradle.sh test --tests 'dev.andre.homecontrol.adapters.support.RendererStatePollerTest'`.
  Expected: compilation fails (`RendererStatePoller` does not exist).

- [ ] **Step 5: Write `RendererStatePoller`.**

```java
// File: src/main/java/dev/andre/homecontrol/adapters/support/RendererStatePoller.java
package dev.andre.homecontrol.adapters.support;

import dev.andre.homecontrol.adapters.upnp.protocol.PlayedItem;
import dev.andre.homecontrol.adapters.upnp.protocol.PositionInfo;
import dev.andre.homecontrol.adapters.upnp.protocol.ServiceEndpoint;
import dev.andre.homecontrol.adapters.upnp.protocol.SoapFault;
import dev.andre.homecontrol.adapters.upnp.protocol.TransportInfo;
import dev.andre.homecontrol.adapters.upnp.protocol.VolumeReading;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.NowPlaying;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;

/**
 * Reads a UPnP renderer's transport, position and volume and publishes them; shared by Sonos and UPnP. {@link #read},
 * {@link #nextPollDelay} and {@link #lost} run on the session's poll loop; {@link #played} on the command thread that
 * started a stream.
 */
public final class RendererStatePoller {

    /** One read's services; {@code renderingControl} may be null; a required volume fails the read when it faults. */
    public record Endpoints(ServiceEndpoint avTransport, ServiceEndpoint renderingControl, int volumeMax,
                            boolean volumeRequired) {
    }

    private static final Logger log = LoggerFactory.getLogger(RendererStatePoller.class);

    private final String deviceId;
    private final RendererCommands commands;
    private final StatePublisher publisher;
    private final Duration pollInterval;
    private final Duration idlePollInterval;
    /** Poll loop only: chooses the next poll delay. */
    private TransportInfo transport = TransportInfo.NONE;
    // Immutable record written by the command thread that played it; the poll loop only reads it.
    @SuppressWarnings("java:S3077")
    private volatile PlayedItem lastPlayed;

    public RendererStatePoller(String deviceId, RendererCommands commands, StatePublisher publisher,
                               Duration pollInterval, Duration idlePollInterval) {
        this.deviceId = deviceId;
        this.commands = commands;
        this.publisher = publisher;
        this.pollInterval = pollInterval;
        this.idlePollInterval = idlePollInterval;
    }

    /** What the session just started, so a renderer that forgets the metadata still shows its title. */
    public void played(PlayedItem item) {
        lastPlayed = item;
    }

    public void read(Endpoints endpoints) throws IOException, SoapFault {
        TransportInfo info = commands.transportInfo(endpoints.avTransport());
        transport = info;
        NowPlaying nowPlaying = null;
        if (info.active()) {
            PositionInfo position;
            try {
                position = commands.positionInfo(endpoints.avTransport());
            } catch (SoapFault _) {
                position = new PositionInfo("", "", null, null);
            }
            nowPlaying = NowPlayings.of(info, position, lastPlayed);
        }
        DeviceState next = publisher.current().withStatus(DeviceStatus.CONNECTED).withPower(true)
                .withNowPlaying(nowPlaying);
        if (endpoints.renderingControl() != null) {
            try {
                VolumeReading volume = commands.volume(endpoints.renderingControl(), endpoints.volumeMax());
                next = next.withVolume(volume.percent(), 100, volume.muted());
            } catch (SoapFault fault) {
                if (endpoints.volumeRequired()) {
                    throw fault;
                }
                log.debug("{} did not report its volume: {}", deviceId, fault.getMessage());
            }
        }
        publisher.publish(next);
    }

    public Duration nextPollDelay() {
        return transport.active() ? pollInterval : idlePollInterval;
    }

    /** The renderer is gone: publish DISCONNECTED, nothing playing. */
    public void lost() {
        transport = TransportInfo.NONE;
        publisher.update(state -> state.withStatus(DeviceStatus.DISCONNECTED).withNowPlaying(null));
    }
}
```

- [ ] **Step 6: Run it.** Same command. Expected: PASS (8).

- [ ] **Step 7: Sonos and UPnP on the publisher and the shared poller.** Save as `task3_sessions.py` and run it, then
  run `prune.py` on both session files:

```python
import sys
sys.path.insert(0, "/home/docker1/home-control/.claude/worktrees/phase-0-defect-fixes/.superpowers/sdd/2026-10-01-phase-2d-pr1-support-toolkit")
from move import MAIN, edit, add_import

SUPPORT = "dev.andre.homecontrol.adapters.support."
STATE_FIELDS = """    // Immutable record written by the command thread that played it; the poll loop only reads it.
    @SuppressWarnings("java:S3077")
    private volatile PlayedItem lastPlayed;
    // Immutable snapshot written only by Link.publish on the poll loop; request threads only read it.
    @SuppressWarnings("java:S3077")
    private volatile DeviceState state = DeviceState.initial();
"""
START = """    public void start() {
        try {
            onChange.accept(state);
        } catch (RuntimeException e) {
            log.warn("A device state listener failed for {}", device.id(), e);
        }
        poller.start();
    }"""
NEW_START = """    public void start() {
        publisher.announce();
        poller.start();
    }"""
STATE = """    public DeviceState state() {
        return state;
    }"""
NEW_STATE = """    public DeviceState state() {
        return publisher.current();
    }"""
PUBLISH = """        private void publish(DeviceState next) {
            DeviceState previous = state;
            state = next;
            if (!next.sameIgnoringTime(previous)) {
                try {
                    onChange.accept(next);
                } catch (RuntimeException e) {
                    log.warn("A device state listener failed for {}", device.id(), e);
                }
            }
        }

"""

upnp = MAIN + "adapters/upnp/UpnpSession.java"
edit(upnp, [
    ("    private final Consumer<DeviceState> onChange;\n", ""),
    ("    private final ReconnectingPoller poller;\n",
     "    private final ReconnectingPoller poller;\n    private final StatePublisher publisher;\n    private final RendererStatePoller renderer;\n"),
    ("""    /** Poll loop only: chooses the next poll delay. */
    private TransportInfo transport = TransportInfo.NONE;
""" + STATE_FIELDS, ""),
    ("        this.onChange = onChange;\n",
     """        this.publisher = new StatePublisher(device.id(), DeviceState.initial(), onChange);
        this.renderer = new RendererStatePoller(device.id(), commands, publisher, timings.pollInterval(),
                timings.idlePollInterval());
"""),
    (START, NEW_START),
    (STATE, NEW_STATE),
    ("                    lastPlayed = new PlayedItem(play.url().toString(), play.title());",
     "                    renderer.played(new PlayedItem(play.url().toString(), play.title()));"),
    ("""        /** Reads the device and publishes; runs on the poll loop only. */
        private void readState(Endpoints current) throws IOException, SoapFault {
            TransportInfo info = commands.transportInfo(current.avTransport());
            transport = info;
            NowPlaying nowPlaying = null;
            if (info.active()) {
                PositionInfo position;
                try {
                    position = commands.positionInfo(current.avTransport());
                } catch (SoapFault _) {
                    position = new PositionInfo("", "", null, null);
                }
                nowPlaying = NowPlayings.of(info, position, lastPlayed);
            }
            DeviceState next = state.withStatus(DeviceStatus.CONNECTED).withPower(true).withNowPlaying(nowPlaying);
            if (current.renderingControl() != null) {
                try {
                    VolumeReading volume = commands.volume(current.renderingControl(), current.volumeMax());
                    next = next.withVolume(volume.percent(), 100, volume.muted());
                } catch (SoapFault fault) {
                    log.debug("{} did not report its volume: {}", device.id(), fault.getMessage());
                }
            }
            publish(next);
        }

""" + PUBLISH,
     """        /** Reads the device and publishes; runs on the poll loop only. */
        private void readState(Endpoints current) throws IOException, SoapFault {
            renderer.read(new RendererStatePoller.Endpoints(current.avTransport(), current.renderingControl(),
                    current.volumeMax(), false));
        }

"""),
    ("            return transport.active() ? timings.pollInterval() : timings.idlePollInterval();",
     "            return renderer.nextPollDelay();"),
    ("""            endpoints = null;
            transport = TransportInfo.NONE;
            log.debug("Media renderer {} unreachable: {}", device.id(), cause.getMessage());
            publish(state.withStatus(DeviceStatus.DISCONNECTED).withNowPlaying(null));""",
     """            endpoints = null;
            log.debug("Media renderer {} unreachable: {}", device.id(), cause.getMessage());
            renderer.lost();"""),
])

sonos = MAIN + "adapters/sonos/SonosSession.java"
edit(sonos, [
    ("    private final Consumer<DeviceState> onChange;\n", ""),
    ("    private final ReconnectingPoller poller;\n",
     "    private final ReconnectingPoller poller;\n    private final StatePublisher publisher;\n    private final RendererStatePoller renderer;\n"),
    ("""    /** Poll loop only: chooses the next poll delay. */
    private TransportInfo transport = TransportInfo.NONE;
""" + STATE_FIELDS, ""),
    ("        this.onChange = onChange;\n",
     """        this.publisher = new StatePublisher(device.id(), DeviceState.initial(), onChange);
        this.renderer = new RendererStatePoller(device.id(), commands, publisher, timings.pollInterval(),
                timings.idlePollInterval());
"""),
    (START, NEW_START),
    (STATE, NEW_STATE),
    ("                    lastPlayed = new PlayedItem(forSonos.url().toString(), title);",
     "                    renderer.played(new PlayedItem(forSonos.url().toString(), title));"),
    (PUBLISH + """        private void readState() throws IOException, SoapFault {
            ServiceEndpoint coordinator = coordinatorAvTransport();
            TransportInfo info = commands.transportInfo(coordinator);
            VolumeReading volume = commands.volume(renderingControl(), 100);
            transport = info;
            NowPlaying nowPlaying = null;
            if (info.active()) {
                // A grouped room shows its coordinator's track; its title comes from the coordinator's metadata.
                PositionInfo position;
                try {
                    position = commands.positionInfo(coordinator);
                } catch (SoapFault _) {
                    position = new PositionInfo("", "", null, null);
                }
                nowPlaying = NowPlayings.of(info, position, lastPlayed);
            }
            publish(state.withStatus(DeviceStatus.CONNECTED).withPower(true).withVolume(volume.percent(), 100, volume.muted())
                    .withNowPlaying(nowPlaying));
        }""",
     """        /** A grouped room shows its coordinator's track, and its own volume. */
        private void readState() throws IOException, SoapFault {
            renderer.read(new RendererStatePoller.Endpoints(coordinatorAvTransport(), renderingControl(), 100, true));
        }"""),
    ("            return transport.active() ? timings.pollInterval() : timings.idlePollInterval();",
     "            return renderer.nextPollDelay();"),
    ("""            live = false;
            transport = TransportInfo.NONE;
            log.debug("Sonos player {} unreachable: {}", device.id(), cause.getMessage());
            publish(state.withStatus(DeviceStatus.DISCONNECTED).withNowPlaying(null));""",
     """            live = false;
            log.debug("Sonos player {} unreachable: {}", device.id(), cause.getMessage());
            renderer.lost();"""),
])
for path in (upnp, sonos):
    add_import(path, SUPPORT + "StatePublisher")
    add_import(path, SUPPORT + "RendererStatePoller")
print("Sonos and UPnP on the shared renderer poller")
```

- [ ] **Step 8: Let `adapters.support` use `upnp.protocol`.** In `ArchitectureTest.adaptersAreIndependent`, after the
  Sonos exception, add one more and extend the reason:

```java
            .ignoreDependency(resideInAPackage("dev.andre.homecontrol.adapters.sonos.."),
                    resideInAPackage("dev.andre.homecontrol.adapters.upnp.protocol.."))
            .ignoreDependency(resideInAPackage("dev.andre.homecontrol.adapters.support.."),
                    resideInAPackage("dev.andre.homecontrol.adapters.upnp.protocol.."))
            .because("each device adapter is a module that can be switched off; net, links and support are shared, "
                    + "and Sonos and the renderer helpers in support speak UPnP");
```

- [ ] **Step 9: Run the adapter and architecture tests.** `scripts/gradle.sh test --tests 'dev.andre.homecontrol.adapters.*'
  --tests 'dev.andre.homecontrol.ArchitectureTest'`. Expected: PASS. `wc -l
  src/test/archunit-store/3fa162ba-7523-467d-adbd-20ce146a962d` → 25 (the frozen rule dropped the 17 lines of the moved
  classes), and `grep -c "RendererCommands\|NowPlayings\|RendererFaultException" src/test/archunit-store/3fa162ba-*` → 0.

- [ ] **Step 10: Run the build.** `scripts/gradle.sh build`. Expected: green.

- [ ] **Step 11: Commit** every path the task touched, the smaller store included, with:

```
refactor: the renderer code leaves upnp.protocol, with one state poller for Sonos and UPnP

RendererCommands, RendererFaultException and NowPlayings build core exceptions and state, so they
move from upnp.protocol to adapters.support, and RendererCommands translates timeouts and lost
renderers through DeviceCalls. RendererStatePoller reads transport, position and volume and
publishes them for both sessions, which now hold their state in a StatePublisher. The frozen
store loses the moved classes' 17 lines (42 to 25).
```

---

### Task 4: Sonos and UPnP stay closed after `close()`

**Files:**
- Modify: `upnp/UpnpSession.java`, `sonos/SonosSession.java`
- Test: `src/test/java/dev/andre/homecontrol/adapters/upnp/UpnpSessionTest.java`,
  `src/test/java/dev/andre/homecontrol/adapters/sonos/SonosSessionTest.java`

**Interfaces:**
- Consumes: `StatePublisher.close()` (Task 1), `DeviceCalls.notConnected(String)` (Task 2).

- [ ] **Step 1: Write the failing tests.** Append to `UpnpSessionTest` (imports `java.util.concurrent.CountDownLatch`,
  `java.util.concurrent.TimeUnit`):

```java
    @Test
    void aConnectThatFinishesAfterCloseNeitherPublishesNorTakesCommands() throws Exception {
        CountDownLatch resolving = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        session = start(fake.device("kitchen"), udn -> {
            resolving.countDown();
            awaitIgnoringInterrupts(release);
            return Optional.empty();
        });
        assertThat(resolving.await(5, TimeUnit.SECONDS)).isTrue();

        session.close();
        int publishedAtClose = states.all().size();
        release.countDown();

        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2))
                .until(() -> states.all().size() == publishedAtClose);
        assertThatThrownBy(() -> session.execute(new Action.Pause())).isInstanceOf(DeviceOfflineException.class);
    }

    /** A step that had already finished when close() interrupted the loop: it goes on as if nothing happened. */
    @SuppressWarnings("java:S2142") // swallowing the interrupt is the point: it models a step that no longer sees it
    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
        while (true) {
            try {
                if (latch.await(10, TimeUnit.SECONDS)) {
                    return;
                }
            } catch (InterruptedException _) {
                // keep waiting
            }
        }
    }
```

  Append to `SonosSessionTest` (imports `java.time.Instant`, `java.time.ZoneId`, `java.time.ZoneOffset`,
  `java.util.concurrent.CountDownLatch`, `java.util.concurrent.TimeUnit`):

```java
    @Test
    void aConnectThatFinishesAfterCloseNeitherPublishesNorTakesCommands() throws Exception {
        HeldClock clock = new HeldClock();
        SonosSession session = new SonosSession(kitchen.device("sonos-" + kitchen.uuid()), timings,
                SoapClient.httpClient(Duration.ofSeconds(1)), states, () -> { }, clock);
        sessions.add(session);
        session.start();
        assertThat(clock.reading.await(5, TimeUnit.SECONDS)).isTrue();

        session.close();
        int publishedAtClose = states.all().size();
        clock.release.countDown();

        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2))
                .until(() -> states.all().size() == publishedAtClose);
        assertThat(session.speakerTopology()).isEmpty();
        assertThatThrownBy(() -> session.execute(new Action.Pause())).isInstanceOf(DeviceOfflineException.class);
    }

    /** Its first reading, right after the first topology call, waits through the interrupt close() sends. */
    private static final class HeldClock extends Clock {
        final CountDownLatch reading = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        @SuppressWarnings("java:S2142") // swallowing the interrupt is the point: it models a step that no longer sees it
        public Instant instant() {
            reading.countDown();
            while (true) {
                try {
                    if (release.await(10, TimeUnit.SECONDS)) {
                        return Instant.now();
                    }
                } catch (InterruptedException _) {
                    // keep waiting
                }
            }
        }
    }
```

- [ ] **Step 2: Run them.** `scripts/gradle.sh test --tests 'dev.andre.homecontrol.adapters.upnp.UpnpSessionTest'
  --tests 'dev.andre.homecontrol.adapters.sonos.SonosSessionTest'`. Expected: both new tests FAIL (a CONNECTED state is
  published after close, and the Pause is carried out instead of refused).

- [ ] **Step 3: Close the publisher first and gate on `closed`.** Save as `task4_close.py` and run it:

```python
import sys
sys.path.insert(0, "/home/docker1/home-control/.claude/worktrees/phase-0-defect-fixes/.superpowers/sdd/2026-10-01-phase-2d-pr1-support-toolkit")
from move import MAIN, edit, add_import

CALLS = "dev.andre.homecontrol.adapters.support.DeviceCalls"

upnp = MAIN + "adapters/upnp/UpnpSession.java"
edit(upnp, [
    ("    private volatile Endpoints endpoints;\n",
     "    private volatile Endpoints endpoints;\n    /** After close() nothing reopens the session, not even a connect that finishes late. */\n    private volatile boolean closed;\n"),
    ("""        if (current == null) { // resolved endpoints exist only while connected
            throw new DeviceOfflineException(device.name() + " is not connected");
        }""",
     """        if (closed || current == null) { // resolved endpoints exist only while connected
            throw DeviceCalls.notConnected(device.name());
        }"""),
    ("""    public void close() {
        poller.close();
        endpoints = null;
        onClosed.run();
    }""",
     """    public void close() {
        closed = true;
        publisher.close();
        poller.close();
        endpoints = null;
        onClosed.run();
    }"""),
])
add_import(upnp, CALLS)

sonos = MAIN + "adapters/sonos/SonosSession.java"
edit(sonos, [
    ("    private volatile boolean live;\n",
     "    private volatile boolean live;\n    /** After close() nothing reopens the session, not even a connect that finishes late. */\n    private volatile boolean closed;\n"),
    ("        if (current == null || !live) {", "        if (current == null || closed || !live) {"),
    ("""        if (!live) {
            throw new DeviceOfflineException(device.name() + " is not connected");
        }""",
     """        if (closed || !live) {
            throw DeviceCalls.notConnected(device.name());
        }"""),
    ("""    public void close() {
        live = false;
        poller.close();
        onClosed.run();
    }""",
     """    public void close() {
        closed = true;
        live = false;
        publisher.close();
        poller.close();
        onClosed.run();
    }"""),
])
add_import(sonos, CALLS)
print("Sonos and UPnP stay closed")
```

  Then `prune.py` on both files (the `DeviceOfflineException` import may now be unused in one of them).

- [ ] **Step 4: Run them.** Same command. Expected: PASS, every UPnP and Sonos test including `closeStopsPolling`.

- [ ] **Step 5: Run the build.** `scripts/gradle.sh build`. Expected: green.

- [ ] **Step 6: Commit** the two sessions and two tests with:

```
fix: Sonos and UPnP stay closed after close()

A connect still in flight when a Sonos or UPnP session closed could finish afterwards: it
published a CONNECTED state and, by setting the endpoints or the live flag again, let the closed
session take commands. close() now closes the state publisher first, and commands check that the
session is still open.
```

---

### Task 5: Bluetooth on `SessionLoop` and `StatePublisher`

**Files:**
- Modify: `bluetooth/BluetoothSpeakerSession.java`
- Test: `src/test/java/dev/andre/homecontrol/adapters/bluetooth/BluetoothSpeakerSessionTest.java`

**Interfaces:**
- Consumes: `SessionLoop`, `StatePublisher` (Task 1).
- Produces: a package-private constructor `BluetoothSpeakerSession(Device, BluetoothProperties, BluetoothTimings,
  BluezClient, MpvPlayer, AudioDeviceResolver, Consumer<DeviceState>, SessionLoop)`.

- [ ] **Step 1: Write the failing test.** Append to `BluetoothSpeakerSessionTest` (import
  `dev.andre.homecontrol.adapters.support.SessionLoop`, `static org.assertj.core.api.Assertions.assertThatCode`):

```java
    @Test
    void aCommandThatRacesCloseStillAnswers() {
        bluez.known("AA:BB:CC:DD:EE:FF", "JBL Flip 5").paired(true).connected(true).uuids(BluetoothDeviceInfo.A2DP_SINK);
        SessionLoop loop = new SessionLoop("bluetooth-race");
        MpvPlayer player = new MpvPlayer(launcher, MpvPlayer.socketFor(runtime, device.id()),
                properties.playerStartTimeout(), properties.loadTimeout(), properties.commandTimeout());
        AudioDeviceResolver resolver = new AudioDeviceResolver(launcher, properties.audioDeviceTemplate(),
                properties.playerStartTimeout());
        session = new BluetoothSpeakerSession(device, properties, TIMINGS, bluez, player, resolver, states, loop);
        session.start();
        await().atMost(WAIT).untilAsserted(() -> assertThat(session.state().status()).isEqualTo(DeviceStatus.CONNECTED));

        // What close() does first: a command already past its closed check still asks for a poll afterwards.
        loop.close();

        assertThatCode(() -> session.execute(new Action.SetVolume(30))).doesNotThrowAnyException();
    }
```

- [ ] **Step 2: Run it.** `scripts/gradle.sh test --tests 'dev.andre.homecontrol.adapters.bluetooth.BluetoothSpeakerSessionTest'`.
  Expected: compilation fails (no constructor takes a `SessionLoop`). The behaviour behind it is already pinned by
  `SessionLoopTest.afterCloseNothingRunsAndNothingThrows`.

- [ ] **Step 3: Move the session onto the toolkit.** Save as `task5_bluetooth.py` and run it, then `prune.py` on the
  session:

```python
import sys
sys.path.insert(0, "/home/docker1/home-control/.claude/worktrees/phase-0-defect-fixes/.superpowers/sdd/2026-10-01-phase-2d-pr1-support-toolkit")
from move import MAIN, edit, add_import

path = MAIN + "adapters/bluetooth/BluetoothSpeakerSession.java"
edit(path, [
    ("""    private final Consumer<DeviceState> onChange;
    private final ScheduledExecutorService loop;
    private final Object commands = new Object();

    // Immutable snapshot written only by publish() on the loop thread; request threads only read it.
    @SuppressWarnings("java:S3077")
    private volatile DeviceState state = DeviceState.initial();
""",
     """    private final StatePublisher publisher;
    private final SessionLoop loop;
    private final Object commands = new Object();

"""),
    ("    private ScheduledFuture<?> nextPoll;      // loop thread only\n", ""),
    ("""    BluetoothSpeakerSession(Device device, BluetoothProperties properties, BluetoothTimings timings, BluezClient bluez,
                            MpvPlayer player, AudioDeviceResolver audioDevices, Consumer<DeviceState> onChange) {
        this.device = device;""",
     """    BluetoothSpeakerSession(Device device, BluetoothProperties properties, BluetoothTimings timings, BluezClient bluez,
                            MpvPlayer player, AudioDeviceResolver audioDevices, Consumer<DeviceState> onChange) {
        this(device, properties, timings, bluez, player, audioDevices, onChange, new SessionLoop("bluetooth-" + device.id()));
    }

    // The collaborators of BluetoothSpeakerAdapter.connect(), plus the loop, injectable so a test can close it under a command.
    @SuppressWarnings("java:S107")
    BluetoothSpeakerSession(Device device, BluetoothProperties properties, BluetoothTimings timings, BluezClient bluez,
                            MpvPlayer player, AudioDeviceResolver audioDevices, Consumer<DeviceState> onChange,
                            SessionLoop loop) {
        this.device = device;"""),
    ("""        this.onChange = onChange;
        this.volume = properties.defaultVolume();
        this.loop = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("bluetooth-" + device.id()).factory());""",
     """        this.publisher = new StatePublisher(device.id(), DeviceState.initial(), onChange);
        this.volume = properties.defaultVolume();
        this.loop = loop;"""),
    ("""    public void start() {
        report(state);
        loop.execute(this::poll);
    }""",
     """    public void start() {
        publisher.announce();
        loop.execute(this::poll);
    }"""),
    ("""    public DeviceState state() {
        return state;
    }""",
     """    public DeviceState state() {
        return publisher.current();
    }"""),
    ("""        closed = true;
        loop.shutdownNow();
        player.stop();""",
     """        closed = true;
        publisher.close();
        loop.close();
        player.stop();"""),
    ("""            if (!closed) {
                if (nextPoll != null) {
                    nextPoll.cancel(false);
                }
                nextPoll = loop.schedule(this::poll, nextPollDelay().toMillis(), TimeUnit.MILLISECONDS);
            }""",
     """            if (!closed) {
                loop.schedule(this::poll, nextPollDelay());
            }"""),
    ("""        publish(state.withStatus(status).withPower(status == DeviceStatus.CONNECTED)
                .withVolume(volume, 100, muted).withNowPlaying(nowPlaying));""",
     """        NowPlaying shownNowPlaying = nowPlaying;
        publisher.update(current -> current.withStatus(status).withPower(status == DeviceStatus.CONNECTED)
                .withVolume(volume, 100, muted).withNowPlaying(shownNowPlaying));"""),
    ("""
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
""", ""),
])
add_import(path, "dev.andre.homecontrol.adapters.support.SessionLoop")
add_import(path, "dev.andre.homecontrol.adapters.support.StatePublisher")
print("Bluetooth on the toolkit")
```

- [ ] **Step 4: Run the Bluetooth tests.** `scripts/gradle.sh test --tests 'dev.andre.homecontrol.adapters.bluetooth.*'`.
  Expected: PASS, the new test and the existing 23 session tests.

- [ ] **Step 5: Run the build.** `scripts/gradle.sh build`. Expected: green.

- [ ] **Step 6: Commit** the session and its test with:

```
fix: a Bluetooth command that races close() gets its own result

A command that passed the session's closed check and finished while close() ran then asked for a
poll on a shut-down executor, and the caller got a RejectedExecutionException for a command that
had worked. The session now runs on a SessionLoop, where a request after close does nothing, and
publishes through a StatePublisher, which adds the closed guard its publishing lacked.
```

---

### Task 6: `CastTls` uses `InsecureTls`

**Files:**
- Modify: `adapters/net/InsecureTls.java` (`trustingAnyCertificate()` public; comment), `adapters/cast/protocol/CastTls.java`

- [ ] **Step 1: The guard.** No behaviour changes: `CastConnectionTest` and `CastSessionTest`, which connect over TLS to a
  receiver with a self-signed certificate, are this task's test. Run `scripts/gradle.sh test --tests
  'dev.andre.homecontrol.adapters.cast.*'`. Expected: PASS before the change.

- [ ] **Step 2: Delegate.** In `InsecureTls`, make `trustingAnyCertificate()` `public` and change the comment above
  `AcceptAny` to `// TVs (Tizen wss 8002, webOS wss 3001) and Cast receivers (TLS 8009): self-signed LAN certificates,
  none of them pinned.` Replace `CastTls` with:

```java
// File: src/main/java/dev/andre/homecontrol/adapters/cast/protocol/CastTls.java
package dev.andre.homecontrol.adapters.cast.protocol;

import dev.andre.homecontrol.adapters.net.InsecureTls;

import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.InetSocketAddress;

/**
 * TLS for port 8009. Receivers present self-signed certificates and senders do not authenticate them
 * (docs/adr/0001-cast-sender.md), so the socket comes from {@link InsecureTls}'s trust-any context, which also adds no
 * hostname or algorithm checks of its own.
 */
final class CastTls {

    private CastTls() {
    }

    static SSLSocket connect(String host, int port, int connectTimeoutMillis, int soTimeoutMillis) throws IOException {
        SSLSocket socket = (SSLSocket) InsecureTls.trustingAnyCertificate().getSocketFactory().createSocket();
        try {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMillis);
            socket.setSoTimeout(soTimeoutMillis);
            socket.setTcpNoDelay(true);
            socket.startHandshake();
            return socket;
        } catch (IOException | RuntimeException e) {
            try {
                socket.close();
            } catch (IOException _) {
                // Already failing.
            }
            throw e;
        }
    }
}
```

- [ ] **Step 3: Run the Cast tests and the build.** The command of Step 1, then `scripts/gradle.sh build`. Expected:
  PASS, green.

- [ ] **Step 4: Commit** the two files with:

```
refactor: CastTls takes its trust-any context from InsecureTls

CastTls carried a byte-for-byte copy of InsecureTls's trust manager. It now opens its socket from
InsecureTls's context; Android TV's TlsSockets keeps its own pinning trust model.
```

---

### Task 7: Web requests on virtual threads

**Files:**
- Modify: `src/main/resources/application.yaml`
- Test: `src/test/java/dev/andre/homecontrol/ApplicationYamlTest.java`

- [ ] **Step 1: Write the failing test.** Add to `ApplicationYamlTest`, next to the logging test:

```java
    /** A request that waits on a slow device or service holds a virtual thread, not one of Tomcat's platform threads. */
    @Test
    void requestsRunOnVirtualThreadsInTheProductionFile() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application.yaml", new ClassPathResource("application.yaml"));

        assertThat(sources.getFirst().getProperty("spring.threads.virtual.enabled")).isEqualTo(true);
    }
```

- [ ] **Step 2: Run it.** `scripts/gradle.sh test --tests 'dev.andre.homecontrol.ApplicationYamlTest'`. Expected: FAIL
  (the property is absent).

- [ ] **Step 3: Turn it on.** In `application.yaml`, under `spring:` before `web:`:

```yaml
spring:
  threads:
    # Request threads are virtual: a request waiting on a slow device holds no platform thread. Argon2 hashing stays
    # capped at two at a time (LoginService), so a burst of logins cannot multiply it.
    virtual:
      enabled: true
  web:
```

- [ ] **Step 4: Run it and the build.** Same command, then `scripts/gradle.sh build`. Expected: PASS, green.

- [ ] **Step 5: Commit** the two files with:

```
feat: handle web requests on virtual threads

spring.threads.virtual.enabled is on, so a request that waits on a slow device or content service
holds a virtual thread rather than one of Tomcat's platform threads. Nothing on a request thread
keeps a ThreadLocal, synchronized no longer pins a carrier thread on JDK 25, and Argon2 hashing
stays capped at two at a time.
```

---

### Task 8: The architecture guide, and the full check

**Files:**
- Modify: `docs/dev/architecture.md`

- [ ] **Step 1: The adapters row.** Replace "`adapters.support` (TV pairing keys kept as device secrets) are shared." with:

```
`adapters.support` (the sessions' lifecycle toolkit: `StatePublisher`, `SessionLoop`, `Backoff`, `ConnectionSlot`, `Reconnector`, `ReconnectingPoller`; `DeviceCalls`, which turns protocol failures into core exceptions; the renderer helpers Sonos and UPnP share; and TV pairing keys kept as device secrets) are shared.
```

- [ ] **Step 2: The rule rows.** In the rules table, change "and Sonos using `adapters.upnp.protocol`" to "and Sonos and
  `adapters.support` using `adapters.upnp.protocol`", and the protocol row's "frozen: 42" to "frozen: 25".

- [ ] **Step 3: Run the build and the browser tests.** `scripts/gradle.sh build`, then
  `scripts/e2e.sh -Pe2eBrowsers=chromium`. Expected: green; browser tests 68/68.

- [ ] **Step 4: Commit** the guide with:

```
docs: describe adapters.support's lifecycle toolkit
```
