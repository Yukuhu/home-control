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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * The server-sent event stream every open browser tab follows: device state ({@code state}), a rail's new content
 * ({@code rail}) and the list of rails ({@code rails}), plus a comment line as a heartbeat, so a reverse proxy does not
 * close a stream that is quiet for a while.
 */
@Component
public class EventStream {

    private static final Logger log = LoggerFactory.getLogger(EventStream.class);

    private static final long NO_TIMEOUT = 0L;

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    /** Whether each subscriber may still receive state; checked before every send and on {@link #revalidate()}. */
    private final Map<SseEmitter, BooleanSupplier> allowed = new ConcurrentHashMap<>();

    /**
     * The fan-out's own thread. {@code publishEvent} is synchronous, so without this the
     * loop below would run on the publishing {@code AndroidTvSession}'s single scheduler
     * thread, and one wedged browser blocking in {@code send} would stall that device's
     * reconnects. Single-threaded, so events and heartbeats still reach each tab in the order published. A plain
     * executor, not a scheduled one: that would wrap each task in a future and swallow an Error in a send.
     */
    private final ExecutorService fanOut = Executors.newSingleThreadExecutor(daemon("home-control-sse-broadcast"));
    /** Only keeps time: each tick queues a heartbeat on {@link #fanOut}, which sends it in order with the events. */
    private final ScheduledExecutorService ticks = Executors.newSingleThreadScheduledExecutor(daemon("home-control-sse-heartbeat"));
    private final ScheduledFuture<?> heartbeat;

    public EventStream(EventStreamProperties properties) {
        long interval = properties.heartbeatInterval().toMillis();
        heartbeat = ticks.scheduleWithFixedDelay(() -> enqueue(this::sendHeartbeat), interval, interval,
                TimeUnit.MILLISECONDS);
    }

    private static ThreadFactory daemon(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    /** @param stillAllowed false once this subscriber's browser logged out or lost its login */
    public SseEmitter subscribe(BooleanSupplier stillAllowed) {
        return register(new SseEmitter(NO_TIMEOUT), stillAllowed);
    }

    SseEmitter register(SseEmitter emitter) {
        return register(emitter, () -> true);
    }

    /** Wires one emitter's lifecycle callbacks and adds it to the fan-out. */
    SseEmitter register(SseEmitter emitter, BooleanSupplier stillAllowed) {
        emitter.onCompletion(() -> drop(emitter));
        emitter.onTimeout(() -> drop(emitter));
        emitter.onError(error -> drop(emitter));
        allowed.put(emitter, stillAllowed);
        emitters.add(emitter);
        return emitter;
    }

    /** Ends every stream whose subscriber may no longer see device state (after a login change). */
    public void revalidate() {
        for (SseEmitter emitter : emitters) {
            if (!isAllowed(emitter)) {
                drop(emitter);
                completeQuietly(emitter);
            }
        }
    }

    private boolean isAllowed(SseEmitter emitter) {
        BooleanSupplier check = allowed.get(emitter);
        try {
            return check != null && check.getAsBoolean();
        } catch (RuntimeException _) {
            return false;
        }
    }

    private void drop(SseEmitter emitter) {
        emitters.remove(emitter);
        allowed.remove(emitter);
    }

    /**
     * Undoes a {@link #subscribe(BooleanSupplier)} whose caller never got to hand the emitter back to
     * Spring — e.g. the initial state send failed. Without this, that emitter's
     * onCompletion/onTimeout/onError never fire (Spring never adopted it), so it would
     * otherwise sit in this list forever.
     */
    void unsubscribe(SseEmitter emitter) {
        drop(emitter);
    }

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
        heartbeat.cancel(false);
        for (SseEmitter emitter : List.copyOf(emitters)) {
            drop(emitter);
            completeQuietly(emitter);
        }
    }

    private void enqueue(Send send) {
        try {
            fanOut.execute(() -> broadcast(send));
        } catch (RejectedExecutionException _) {
            // The application is shutting down; there is nobody left to tell.
        }
    }

    private void broadcast(Send send) {
        for (SseEmitter emitter : emitters) {
            if (!isAllowed(emitter)) {
                drop(emitter);
                completeQuietly(emitter);
                continue;
            }
            boolean delivered = false;
            try {
                send.to(emitter);
                delivered = true;
            } catch (IOException | RuntimeException e) {
                // Not just IOException: send throws an unchecked IllegalStateException when the
                // emitter completed after this loop took its snapshot of the list, which happens
                // routinely on tab close. Either way this subscriber is finished — drop it, and
                // never let it stop the event reaching the remaining tabs.
                log.debug("Dropping an SSE subscriber after a failed send", e);
                completeQuietly(emitter, e);
            } finally {
                // An Error still ends this task (the executor starts a fresh thread), but the subscriber
                // that raised it is dropped too, so it cannot cut off the tabs after it on every event.
                if (!delivered) {
                    drop(emitter);
                }
            }
        }
    }

    /** The one place a device-state event becomes an SSE frame; package-private so a test can observe the object. */
    void sendData(SseEmitter emitter, DeviceStateChangedEvent event) throws IOException {
        emitter.send(SseEmitter.event().name("state").data(event));
    }

    /** A comment line: browsers ignore it, proxies see traffic, and a closed tab shows up as a failed send. */
    void sendHeartbeat(SseEmitter emitter) throws IOException {
        emitter.send(SseEmitter.event().comment("keep-alive"));
    }

    /** Named non-device events; package-private so a test can observe them. */
    void sendNamed(SseEmitter emitter, String name, Object data) throws IOException {
        emitter.send(SseEmitter.event().name(name).data(data));
    }

    private void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (RuntimeException _) {
            // Already gone; the emitter is off the list either way.
        }
    }

    /** {@code completeWithError} throws in turn on an emitter that has already completed. */
    private void completeQuietly(SseEmitter emitter, Exception cause) {
        try {
            emitter.completeWithError(cause);
        } catch (RuntimeException _) {
            // Already gone; the emitter is off the list either way.
        }
    }

    @PreDestroy
    void shutdown() {
        ticks.shutdownNow();
        fanOut.shutdownNow();
    }
}
