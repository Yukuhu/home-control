package dev.andre.homecontrol.web;

import dev.andre.homecontrol.content.RailSnapshot;
import dev.andre.homecontrol.content.RailStatus;
import dev.andre.homecontrol.content.RailUpdatedEvent;
import dev.andre.homecontrol.content.RailsChangedEvent;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStateChangedEvent;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.content.RailDescriptor;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

class EventStreamTest {

    private final EventStream broadcaster = new EventStream(new EventStreamProperties(Duration.ofSeconds(25)));

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
        EventStream recording = new EventStream(new EventStreamProperties(Duration.ofSeconds(25))) {
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

    @Test
    void forwardsRailEventsUnderTheirOwnName() {
        record Sent(String name, Object data) {
        }
        List<Sent> sent = new CopyOnWriteArrayList<>();
        EventStream recording = new EventStream(new EventStreamProperties(Duration.ofSeconds(25))) {
            @Override
            void sendNamed(SseEmitter emitter, String name, Object data) {
                sent.add(new Sent(name, data));
            }
        };
        recording.register(new SseEmitter(0L));

        RailDescriptor descriptor = new RailDescriptor("stub", "a", "Rail A");
        RailSnapshot snapshot = new RailSnapshot(descriptor, RailStatus.READY, List.of(),
                Instant.parse("2026-09-16T09:00:00Z"), null, false, 3);
        recording.onRailUpdated(new RailUpdatedEvent(snapshot));

        await().until(() -> !sent.isEmpty());
        assertThat(sent.getFirst().name()).isEqualTo("rail");
        assertThat(((RailEventView) sent.getFirst().data()).railId()).isEqualTo("a");

        recording.onRailsChanged(new RailsChangedEvent(List.of("stub/a")));

        await().until(() -> sent.size() == 2);
        assertThat(sent.get(1).name()).isEqualTo("rails");
        assertThat(sent.get(1).data()).isEqualTo(Map.of("rails", List.of("stub/a")));

        recording.shutdown();
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

    @Test
    void aHeartbeatReachesEveryOpenStreamWithoutAnyEvent() {
        List<SseEmitter> beats = new CopyOnWriteArrayList<>();
        EventStream stream = new EventStream(new EventStreamProperties(Duration.ofMillis(50))) {
            @Override
            void sendHeartbeat(SseEmitter emitter) {
                beats.add(emitter);
            }
        };
        try {
            SseEmitter first = stream.subscribe(() -> true);
            SseEmitter second = stream.subscribe(() -> true);

            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(beats).contains(first, second));
        } finally {
            stream.shutdown();
        }
    }

    @Test
    void aStreamWhoseHeartbeatFailsIsDropped() {
        AtomicInteger beats = new AtomicInteger();
        EventStream stream = new EventStream(new EventStreamProperties(Duration.ofMillis(50))) {
            @Override
            void sendHeartbeat(SseEmitter emitter) throws IOException {
                beats.incrementAndGet();
                throw new IOException("the tab is gone");
            }
        };
        try {
            stream.subscribe(() -> true);

            await().atMost(Duration.ofSeconds(2)).until(() -> beats.get() >= 1);
            int afterFirst = beats.get();
            await().pollDelay(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> assertThat(beats.get()).isEqualTo(afterFirst));
        } finally {
            stream.shutdown();
        }
    }

    @Test
    void theHeartbeatStopsWhenTheApplicationCloses() throws InterruptedException {
        AtomicInteger beats = new AtomicInteger();
        CountDownLatch drained = new CountDownLatch(1);
        EventStream stream = new EventStream(new EventStreamProperties(Duration.ofMillis(50))) {
            @Override
            void sendHeartbeat(SseEmitter emitter) {
                beats.incrementAndGet();
            }

            @Override
            void sendNamed(SseEmitter emitter, String name, Object data) {
                drained.countDown();
            }
        };
        try {
            stream.subscribe(() -> true);
            await().atMost(Duration.ofSeconds(2)).until(() -> beats.get() >= 1);

            stream.onContextClosed();
            stream.subscribe(() -> true);
            // Heartbeats queued before the close may still go out; the fan-out sends in order, so once this event
            // arrives they have, and every heartbeat counted after it would come from a tick after the close.
            stream.onRailsChanged(new RailsChangedEvent(List.of()));
            assertThat(drained.await(2, TimeUnit.SECONDS)).isTrue();
            int afterClose = beats.get();

            await().pollDelay(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> assertThat(beats.get()).isEqualTo(afterClose));
        } finally {
            stream.shutdown();
        }
    }
}
