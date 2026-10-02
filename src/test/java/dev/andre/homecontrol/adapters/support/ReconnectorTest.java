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

        // On the loop, as sessions call it: it then runs after the fourth attempt has connected and reset the backoff.
        loop.execute(reconnector::lost);

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
        Reconnector reconnector = reconnector(Duration.ofSeconds(10), Duration.ofSeconds(10), RETRY);
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
        await().atMost(Duration.ofSeconds(1)).until(() -> !attempts.isEmpty());

        loop.close();
        int atClose = attempts.size();

        await().during(Duration.ofMillis(400)).atMost(Duration.ofSeconds(2)).until(() -> attempts.size() == atClose);
    }

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
}
