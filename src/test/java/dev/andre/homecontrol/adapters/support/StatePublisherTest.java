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
