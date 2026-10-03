package dev.andre.homecontrol.web;

import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStateChangedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Subscribers whose send or completion fails, and a send that fails with an Error. */
class EventStreamFailuresTest {

    private final EventStream broadcaster = new EventStream(new EventStreamProperties(Duration.ofSeconds(25)));

    @AfterEach
    void shutdown() {
        broadcaster.shutdown();
    }

    private static DeviceStateChangedEvent event() {
        return new DeviceStateChangedEvent("test", DeviceState.initial());
    }

    @Test
    void aSubscriberWhoseSendAndCompletionBothFailIsDroppedAndTheOthersStillHearTheEvent() {
        FailingEmitter broken = (FailingEmitter) broadcaster.register(new FailingEmitter(new IOException("broken pipe")));
        CountingEmitter healthy = (CountingEmitter) broadcaster.register(new CountingEmitter());

        broadcaster.onStateChanged(event());
        await().until(() -> healthy.sends.get() == 1);
        await().until(() -> broken.completedWithError);
        assertThat(broken.sends).hasValue(1);

        broadcaster.onStateChanged(event());
        await().until(() -> healthy.sends.get() == 2);
        assertThat(broken.sends).hasValue(1);
    }

    @Test
    void aSubscriberWhoseCompletionFailsIsStillDroppedOnRevalidation() {
        AtomicBoolean allowed = new AtomicBoolean(true);
        FailingEmitter revoked = (FailingEmitter) broadcaster.register(new FailingEmitter(null), allowed::get);
        CountingEmitter other = (CountingEmitter) broadcaster.register(new CountingEmitter());

        allowed.set(false);
        broadcaster.revalidate();
        broadcaster.onStateChanged(event());

        await().until(() -> other.sends.get() == 1);
        await().until(() -> revoked.completed);
        assertThat(revoked.sends).hasValue(0);
    }

    /**
     * An Error reaches the thread's handler instead of being swallowed; its subscriber is dropped, and as every tab
     * has a sender of its own, the other tabs hear that event and the later ones.
     */
    @Test
    void anErrorDuringASendIsNotSwallowedAndOnlyItsSubscriberIsDropped() {
        List<Throwable> uncaught = new CopyOnWriteArrayList<>();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> uncaught.add(error));
        try {
            AtomicBoolean fail = new AtomicBoolean(true);
            CountingEmitter flaky = (CountingEmitter) broadcaster.register(new CountingEmitter() {
                @Override
                public void send(SseEventBuilder builder) {
                    super.send(builder);
                    if (fail.getAndSet(false)) {
                        throw new AssertionError("an Error, not a failed send");
                    }
                }
            });
            CountingEmitter healthy = (CountingEmitter) broadcaster.register(new CountingEmitter());

            broadcaster.onStateChanged(event());
            await().dontCatchUncaughtExceptions().until(() -> !uncaught.isEmpty());
            assertThat(uncaught).singleElement().isInstanceOf(AssertionError.class);

            broadcaster.onStateChanged(event());
            await().dontCatchUncaughtExceptions().until(() -> healthy.sends.get() == 2);
            assertThat(flaky.sends).as("the subscriber that raised the Error is off the list").hasValue(1);
            assertThat(uncaught).hasSize(1);
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
    }

    private static class CountingEmitter extends SseEmitter {
        final AtomicInteger sends = new AtomicInteger();
        volatile boolean completed;

        @Override
        public void complete() {
            completed = true;
        }

        @Override
        public void send(SseEventBuilder builder) {
            sends.incrementAndGet();
        }
    }

    /** Fails its sends with {@code failure} (none when null), and throws when completed, like an emitter that is already gone. */
    private static final class FailingEmitter extends SseEmitter {
        private final IOException failure;
        final AtomicInteger sends = new AtomicInteger();
        volatile boolean completed;
        volatile boolean completedWithError;

        FailingEmitter(IOException failure) {
            this.failure = failure;
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            sends.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
        }

        @Override
        public void complete() {
            completed = true;
            throw new IllegalStateException("ResponseBodyEmitter has already completed");
        }

        @Override
        public void completeWithError(Throwable ex) {
            completedWithError = true;
            throw new IllegalStateException("ResponseBodyEmitter has already completed");
        }
    }
}
