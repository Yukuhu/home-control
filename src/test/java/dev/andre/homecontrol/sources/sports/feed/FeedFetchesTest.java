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

    private static void awaitStillWaiting(Future<?> waiter) {
        await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(2)).until(() -> !waiter.isDone());
    }

    @Test
    void aSecondCallerWaitsForTheRunningFetchInsteadOfStartingOne() throws Exception {
        Future<?> first = startHeld("calendar");
        AtomicInteger secondRuns = new AtomicInteger();
        Future<?> second = pool.submit(() -> fetches.run("calendar", secondRuns::incrementAndGet));
        awaitStillWaiting(second);

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
    void aFailingFetchReachesTheRunnerAndEveryWaiterAndFreesTheKey() throws Exception {
        Future<?> first = pool.submit(() -> fetches.run("calendar", () -> {
            heldFetch();
            throw new IllegalStateException("parser bug");
        }));
        await().until(() -> runs.get() == 1);
        Future<?> second = pool.submit(() -> fetches.run("calendar", runs::incrementAndGet));
        awaitStillWaiting(second);

        release.countDown();

        assertThatThrownBy(() -> first.get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(IllegalStateException.class).hasRootCauseMessage("parser bug");
        assertThatThrownBy(() -> second.get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(IllegalStateException.class).hasRootCauseMessage("parser bug");
        fetches.run("calendar", runs::incrementAndGet);
        assertThat(runs).hasValue(2);
    }

    @Test
    void anInterruptedWaiterReturnsWhileTheFetchRunsAndKeepsItsFlag() throws Exception {
        startHeld("calendar");
        AtomicBoolean stillInterrupted = new AtomicBoolean();
        Thread waiter = Thread.ofVirtual().start(() -> {
            fetches.run("calendar", runs::incrementAndGet);
            stillInterrupted.set(Thread.currentThread().isInterrupted());
        });
        await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(2)).until(waiter::isAlive);

        waiter.interrupt();

        assertThat(waiter.join(Duration.ofSeconds(5))).isTrue();
        assertThat(stillInterrupted).isTrue();
        assertThat(runs).hasValue(1);
    }
}
