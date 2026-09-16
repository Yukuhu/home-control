package dev.andre.homecontrol.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class LoginRateLimiterTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-16T10:00:00Z"));

    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };

    private final LoginRateLimiter limiter = new LoginRateLimiter(clock, 5, 50, Duration.ofMinutes(15));

    private void advance(Duration duration) {
        now.updateAndGet(instant -> instant.plus(duration));
    }

    @Test
    void allowsFiveFailuresPerAddressThenBlocksUntilTheOldestExpires() {
        for (int i = 0; i < 4; i++) {
            limiter.failed("10.0.0.2");
        }
        assertThat(limiter.blockedFor("10.0.0.2")).isEmpty();
        limiter.failed("10.0.0.2");

        assertThat(limiter.blockedFor("10.0.0.2")).isEqualTo(Optional.of(Duration.ofMinutes(15)));
        assertThat(limiter.blockedFor("10.0.0.3")).isEmpty();
        advance(Duration.ofMinutes(15).plusSeconds(1));
        assertThat(limiter.blockedFor("10.0.0.2")).isEmpty();
    }

    @Test
    void aSuccessClearsThatAddress() {
        for (int i = 0; i < 4; i++) {
            limiter.failed("10.0.0.2");
        }
        limiter.succeeded("10.0.0.2");
        for (int i = 0; i < 4; i++) {
            limiter.failed("10.0.0.2");
        }

        assertThat(limiter.blockedFor("10.0.0.2")).isEmpty();
    }

    @Test
    void fiftyFailuresAcrossAddressesBlockEveryone() {
        for (int i = 0; i < 50; i++) {
            limiter.failed("10.0.1." + i);
        }

        assertThat(limiter.blockedFor("10.9.9.9")).isPresent();
    }

    @Test
    void forgetsExpiredAddresses() {
        for (int i = 0; i < 20_000; i++) {
            limiter.failed("10." + (i / 65536) + "." + (i / 256 % 256) + "." + (i % 256));
        }
        advance(Duration.ofMinutes(16));

        limiter.blockedFor("10.200.0.1");

        assertThat(limiter.trackedAddresses()).isLessThanOrEqualTo(1);
    }

    @Test
    void aParallelBurstCannotExceedThePerAddressCap() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger admitted = new AtomicInteger();
        try {
            for (int i = 0; i < 64; i++) {
                pool.execute(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (limiter.reserve("10.0.0.2").isEmpty()) {
                        admitted.incrementAndGet(); // a slow password check would run here, after the reservation
                    }
                });
            }
            start.countDown();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(admitted).hasValue(5);
        assertThat(limiter.blockedFor("10.0.0.2")).isPresent();
    }

    @Test
    void aReservationCountsAsAFailureUntilItSucceedsOrIsReleased() {
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.reserve("10.0.0.2")).isEmpty();
        }
        assertThat(limiter.reserve("10.0.0.2")).isPresent();

        limiter.release("10.0.0.2");
        assertThat(limiter.reserve("10.0.0.2")).isEmpty();
        limiter.succeeded("10.0.0.2");

        assertThat(limiter.blockedFor("10.0.0.2")).isEmpty();
    }

    @Test
    void aSuccessfulReservationNoLongerCountsTowardsTheGlobalCap() {
        for (int i = 0; i < 49; i++) {
            limiter.failed("10.0.1." + i);
        }
        assertThat(limiter.reserve("10.0.2.1")).isEmpty();
        limiter.succeeded("10.0.2.1");

        assertThat(limiter.reserve("10.0.2.2")).isEmpty();
        assertThat(limiter.blockedFor("10.9.9.9")).isPresent();
    }
}
