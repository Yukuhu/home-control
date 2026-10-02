package dev.andre.homecontrol.sources.sports.feed;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class FeedFetchesTest {

    private final FeedFetches<String> fetches = new FeedFetches<>();
    private final CountDownLatch release = new CountDownLatch(1);
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicInteger runs = new AtomicInteger();

    @AfterEach
    void tearDown() {
        release.countDown();
        pool.shutdownNow();
    }

    /** A fetch that counts itself and then waits for the test. */
    private void heldFetch() {
        runs.incrementAndGet();
        try {
            release.await();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private Future<?> startHeld(String key) {
        Future<?> running = pool.submit(() -> fetches.run(key, this::heldFetch));
        await().until(() -> runs.get() == 1);
        return running;
    }

    private Future<?> submitWaiting(String key, Runnable fetch) {
        return FeedFetchWaiters.submitWaiting(pool, () -> {
            fetches.run(key, fetch);
            return null;
        });
    }

    @Test
    void aSecondCallerWaitsForTheRunningFetchInsteadOfStartingOne() throws Exception {
        Future<?> first = startHeld("calendar");
        AtomicInteger secondRuns = new AtomicInteger();
        Future<?> second = submitWaiting("calendar", secondRuns::incrementAndGet);

        release.countDown();
        first.get(5, TimeUnit.SECONDS);
        second.get(5, TimeUnit.SECONDS);

        assertThat(runs).hasValue(1);
        assertThat(secondRuns).hasValue(0);
    }

    @Test
    void anotherKeyDoesNotWait() {
        startHeld("calendar");
        AtomicInteger other = new AtomicInteger();

        fetches.run("competition", other::incrementAndGet);

        assertThat(other).hasValue(1);
    }

    @Test
    void aCallerAfterTheFetchEndedRunsItsOwn() {
        fetches.run("calendar", runs::incrementAndGet);
        fetches.run("calendar", runs::incrementAndGet);

        assertThat(runs).hasValue(2);
    }

    @Test
    void aFailingFetchReachesTheRunnerAndEveryWaiterAndFreesTheKey() {
        // Not an IllegalStateException: FeedFetches wraps checked causes in one, so the same type could hide a wrap.
        RuntimeException parserBug = new UnsupportedOperationException("parser bug");
        Future<?> first = pool.submit(() -> fetches.run("calendar", () -> {
            heldFetch();
            throw parserBug;
        }));
        await().until(() -> runs.get() == 1);
        Future<?> second = submitWaiting("calendar", runs::incrementAndGet);

        release.countDown();

        assertThatThrownBy(() -> first.get(5, TimeUnit.SECONDS)).cause().isSameAs(parserBug);
        assertThatThrownBy(() -> second.get(5, TimeUnit.SECONDS)).cause().isSameAs(parserBug);
        fetches.run("calendar", runs::incrementAndGet);
        assertThat(runs).hasValue(2);
    }

    @Test
    void anInterruptedWaiterReturnsWhileTheFetchRunsAndKeepsItsFlag() throws Exception {
        startHeld("calendar");
        AtomicBoolean stillInterrupted = new AtomicBoolean();
        Thread waiter = FeedFetchWaiters.startWaiting(() -> {
            fetches.run("calendar", runs::incrementAndGet);
            stillInterrupted.set(Thread.currentThread().isInterrupted());
        });

        waiter.interrupt();

        assertThat(waiter.join(Duration.ofSeconds(5))).isTrue();
        assertThat(stillInterrupted).isTrue();
        assertThat(runs).hasValue(1);
    }
}
