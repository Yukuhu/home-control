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
