package dev.andre.homecontrol.web;

import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStateChangedEvent;
import dev.andre.homecontrol.core.DeviceStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
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

    @Test
    void forwardsTheDeviceIdWithTheState() {
        List<DeviceStateChangedEvent> sent = new CopyOnWriteArrayList<>();
        DeviceStateBroadcaster recording = new DeviceStateBroadcaster() {
            @Override
            void sendData(SseEmitter emitter, DeviceStateChangedEvent event) {
                sent.add(event);
            }
        };
        recording.register(new SseEmitter(0L));

        recording.onStateChanged(new DeviceStateChangedEvent("bedroom", DeviceState.unpaired()));

        await().until(() -> !sent.isEmpty());
        assertThat(sent.getFirst().deviceId()).isEqualTo("bedroom");
        assertThat(sent.getFirst().state().status()).isEqualTo(DeviceStatus.UNPAIRED);
        recording.shutdown();
    }

    /** After a logout or a password change a tab must not keep receiving state it may no longer see. */
    @Test
    void closesSubscribersThatAreNoLongerAllowedWhenAskedToRevalidate() {
        AtomicBoolean allowed = new AtomicBoolean(true);
        CountingEmitter revoked = (CountingEmitter) broadcaster.register(new CountingEmitter(), allowed::get);
        CountingEmitter other = (CountingEmitter) broadcaster.register(new CountingEmitter());

        allowed.set(false);
        broadcaster.revalidate();

        assertThat(revoked.completed()).isTrue();
        assertThat(other.completed()).isFalse();
        broadcaster.onStateChanged(event());
        await().until(() -> other.count().get() == 1);
        assertThat(revoked.count()).hasValue(0);
    }

    @Test
    void neverSendsToASubscriberThatLostItsLoginBetweenRevalidations() {
        AtomicBoolean allowed = new AtomicBoolean(true);
        CountingEmitter revoked = (CountingEmitter) broadcaster.register(new CountingEmitter(), allowed::get);
        CountingEmitter other = (CountingEmitter) broadcaster.register(new CountingEmitter());

        allowed.set(false);
        broadcaster.onStateChanged(event());

        await().until(() -> other.count().get() == 1);
        assertThat(revoked.count()).hasValue(0);
        assertThat(revoked.completed()).isTrue();
    }

    private static DeviceStateChangedEvent event() {
        return new DeviceStateChangedEvent("test", DeviceState.initial());
    }

    /** An emitter that records sends instead of writing to a response Spring never gave it. */
    private static class CountingEmitter extends SseEmitter {

        private final AtomicInteger sends = new AtomicInteger();
        private final AtomicBoolean completed = new AtomicBoolean();

        AtomicInteger count() {
            return sends;
        }

        boolean completed() {
            return completed.get();
        }

        @Override
        public void complete() {
            completed.set(true);
        }

        @Override
        public void send(SseEventBuilder builder) {
            sends.incrementAndGet();
        }
    }
}
