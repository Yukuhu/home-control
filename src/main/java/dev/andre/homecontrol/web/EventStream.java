package dev.andre.homecontrol.web;

import dev.andre.homecontrol.content.RailUpdatedEvent;
import dev.andre.homecontrol.content.RailsChangedEvent;
import dev.andre.homecontrol.core.DeviceStateChangedEvent;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * The server-sent event stream every open browser tab follows: device state ({@code state}), a rail's new content
 * ({@code rail}) and the list of rails ({@code rails}), plus a comment line as a heartbeat, so a reverse proxy does not
 * close a stream that is quiet for a while.
 *
 * <p>Every tab has a queue of its own, drained by a virtual thread of its own, so a tab whose sends block (a phone that
 * left the Wi-Fi without closing its connection) delays nobody else, and nothing publishing an event ever waits for a
 * browser. A tab receives its sends in the order they were queued, its snapshot first. A tab that falls
 * {@value #QUEUE_CAPACITY} sends behind is dropped: its browser reconnects and gets a fresh snapshot.
 */
@Component
public class EventStream {

    private static final Logger log = LoggerFactory.getLogger(EventStream.class);

    private static final long NO_TIMEOUT = 0L;
    /** How many sends a tab may have waiting before it is dropped. */
    static final int QUEUE_CAPACITY = 256;
    /** Ends a tab's sender once everything before it was sent. */
    private static final Send STOP = _ -> { };

    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();

    /** Only keeps time: each tick queues a heartbeat for every tab, in order with its events. */
    private final ScheduledExecutorService ticks = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "home-control-sse-heartbeat");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledFuture<?> heartbeat;
    /** Set first thing on close: a heartbeat a tick queued just before, or is queueing still, then sends nothing. */
    private volatile boolean closed;

    public EventStream(EventStreamProperties properties) {
        long interval = properties.heartbeatInterval().toMillis();
        heartbeat = ticks.scheduleWithFixedDelay(() -> enqueue(this::heartbeatUnlessClosed), interval, interval,
                TimeUnit.MILLISECONDS);
    }

    /**
     * Opens a tab's stream. Its {@code snapshot} is the first thing sent, from the tab's own queue, so no event
     * published while the snapshot is read or sent can arrive before it.
     *
     * @param stillAllowed false once this subscriber's browser logged out or lost its login
     */
    public SseEmitter subscribe(BooleanSupplier stillAllowed, Send snapshot) {
        return register(new SseEmitter(NO_TIMEOUT), stillAllowed, snapshot);
    }

    SseEmitter register(SseEmitter emitter) {
        return register(emitter, () -> true);
    }

    SseEmitter register(SseEmitter emitter, BooleanSupplier stillAllowed) {
        return register(emitter, stillAllowed, null);
    }

    /** Wires one emitter's lifecycle callbacks, queues its snapshot and starts its sender. */
    SseEmitter register(SseEmitter emitter, BooleanSupplier stillAllowed, Send snapshot) {
        Subscriber subscriber = new Subscriber(emitter, stillAllowed);
        if (snapshot != null) {
            subscriber.queue.add(snapshot);
        }
        emitter.onCompletion(subscriber::end);
        emitter.onTimeout(subscriber::end);
        emitter.onError(_ -> subscriber.end());
        subscribers.add(subscriber);
        Thread.ofVirtual().name("home-control-sse-tab").start(subscriber);
        if (closed) {
            // Subscribed once closing had begun: graceful shutdown would otherwise wait for this tab.
            subscriber.end();
        }
        return emitter;
    }

    /** Ends every stream whose subscriber may no longer see device state (after a login change). */
    public void revalidate() {
        for (Subscriber subscriber : subscribers) {
            if (!subscriber.allowed()) {
                subscriber.end();
            }
        }
    }

    /** One send to one tab's emitter. */
    @FunctionalInterface
    interface Send {
        void to(SseEmitter emitter) throws IOException;
    }

    @EventListener
    public void onStateChanged(DeviceStateChangedEvent event) {
        enqueue(emitter -> sendData(emitter, event));
    }

    @EventListener
    public void onRailUpdated(RailUpdatedEvent event) {
        RailEventView view = RailEventView.of(event.snapshot());
        enqueue(emitter -> sendNamed(emitter, "rail", view));
    }

    @EventListener
    public void onRailsChanged(RailsChangedEvent event) {
        Map<String, Object> body = Map.of("rails", event.keys());
        enqueue(emitter -> sendNamed(emitter, "rails", body));
    }

    /**
     * Ends every stream as the application starts closing, before the web server's graceful shutdown. A stream never
     * ends by itself, and graceful shutdown waits for every request in flight, so an open tab, or a closed one whose
     * disconnect no failed send has revealed yet, would otherwise hold shutdown for the whole 30 s phase timeout.
     * Browsers reconnect on their own once the application is back.
     */
    @EventListener(ContextClosedEvent.class)
    public void onContextClosed() {
        closed = true;
        heartbeat.cancel(false);
        subscribers.forEach(Subscriber::end);
    }

    private void enqueue(Send send) {
        for (Subscriber subscriber : subscribers) {
            subscriber.offer(send);
        }
    }

    /** The one place a device-state event becomes an SSE frame; package-private so a test can observe the object. */
    void sendData(SseEmitter emitter, DeviceStateChangedEvent event) throws IOException {
        emitter.send(SseEmitter.event().name("state").data(event));
    }

    private void heartbeatUnlessClosed(SseEmitter emitter) throws IOException {
        if (!closed) {
            sendHeartbeat(emitter);
        }
    }

    /** A comment line: browsers ignore it, proxies see traffic, and a closed tab shows up as a failed send. */
    void sendHeartbeat(SseEmitter emitter) throws IOException {
        emitter.send(SseEmitter.event().comment("keep-alive"));
    }

    /** Named non-device events; package-private so a test can observe them. */
    void sendNamed(SseEmitter emitter, String name, Object data) throws IOException {
        emitter.send(SseEmitter.event().name(name).data(data));
    }

    @PreDestroy
    void shutdown() {
        ticks.shutdownNow();
        subscribers.forEach(Subscriber::end);
    }

    /**
     * One open tab: its sends wait in its own queue for its own sender. Only that sender touches the emitter, also to
     * complete it, so a tab stuck in a send holds up neither a publisher nor whoever ends it.
     */
    private final class Subscriber implements Runnable {

        private final SseEmitter emitter;
        private final BooleanSupplier stillAllowed;
        private final BlockingQueue<Send> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        private volatile boolean ended;
        private volatile Exception failure;

        Subscriber(SseEmitter emitter, BooleanSupplier stillAllowed) {
            this.emitter = emitter;
            this.stillAllowed = stillAllowed;
        }

        boolean allowed() {
            try {
                return stillAllowed.getAsBoolean();
            } catch (RuntimeException _) {
                return false;
            }
        }

        void offer(Send send) {
            if (!ended && !queue.offer(send)) {
                log.debug("Dropping an SSE subscriber that fell {} sends behind", QUEUE_CAPACITY);
                end();
            }
        }

        /** Takes the tab off the stream; its sender completes it once a send it may be stuck in returns. */
        void end() {
            if (ended) {
                return;
            }
            ended = true;
            subscribers.remove(this);
            queue.clear();
            queue.offer(STOP);
        }

        private void fail(Exception cause) {
            failure = cause;
            end();
        }

        @Override
        public void run() {
            try {
                while (!ended) {
                    Send send = queue.take();
                    if (send == STOP || ended) {
                        return;
                    }
                    if (!allowed()) {
                        end();
                        return;
                    }
                    try {
                        send.to(emitter);
                    } catch (IOException | RuntimeException e) {
                        // Not just IOException: send throws an unchecked IllegalStateException when the emitter
                        // completed meanwhile, which happens routinely on tab close. Either way this tab is finished.
                        log.debug("Dropping an SSE subscriber after a failed send", e);
                        fail(e);
                    }
                }
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            } finally {
                // An Error still ends this sender (and reaches the thread's handler), but its tab is ended too.
                end();
                complete();
            }
        }

        private void complete() {
            try {
                if (failure != null) {
                    emitter.completeWithError(failure);
                } else {
                    emitter.complete();
                }
            } catch (RuntimeException _) {
                // Already gone; the tab is off the stream either way.
            }
        }
    }
}
