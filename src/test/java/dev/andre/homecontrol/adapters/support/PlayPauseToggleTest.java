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
    void concurrentPressesStillAlternate() {
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
