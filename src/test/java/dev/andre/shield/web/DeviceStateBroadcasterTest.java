package dev.andre.shield.web;

import dev.andre.shield.device.DeviceState;
import dev.andre.shield.device.DeviceStateChangedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

class DeviceStateBroadcasterTest {

    private final DeviceStateBroadcaster broadcaster = new DeviceStateBroadcaster();

    @AfterEach
    void shutdown() {
        broadcaster.shutdown();
    }

    /**
     * A tab closed between the fan-out's snapshot of the subscriber list and the send makes
     * {@code SseEmitter.send} throw an unchecked {@link IllegalStateException}, not an
     * {@link java.io.IOException}. The fan-out runs off a device session's control thread,
     * so letting that escape can kill the task that was about to schedule a reconnect.
     */
    @Test
    void dropsASubscriberWhoseSendFailsWithoutDisturbingTheOthers() {
        CountingEmitter healthy = (CountingEmitter) broadcaster.register(new CountingEmitter());
        CountingEmitter broken = (CountingEmitter) broadcaster.register(new CountingEmitter() {
            @Override
            public void send(SseEventBuilder builder) {
                count().incrementAndGet();
                throw new IllegalStateException("ResponseBodyEmitter has already completed");
            }
        });

        assertThatCode(() -> broadcaster.onStateChanged(event())).doesNotThrowAnyException();
        await().until(() -> healthy.count().get() == 1);
        assertThat(broken.count()).hasValue(1);

        // The broken subscriber has been dropped, so the next event reaches only the healthy
        // one — proof of removal that needs no test-only accessor on the broadcaster.
        broadcaster.onStateChanged(event());
        await().until(() -> healthy.count().get() == 2);
        assertThat(broken.count()).hasValue(1);
    }

    private static DeviceStateChangedEvent event() {
        return new DeviceStateChangedEvent(DeviceState.initial());
    }

    /** An emitter that records sends instead of writing to a response Spring never gave it. */
    private static class CountingEmitter extends SseEmitter {

        private final AtomicInteger sends = new AtomicInteger();

        AtomicInteger count() {
            return sends;
        }

        @Override
        public void send(SseEventBuilder builder) {
            sends.incrementAndGet();
        }
    }
}
