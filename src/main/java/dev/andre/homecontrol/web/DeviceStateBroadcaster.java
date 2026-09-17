package dev.andre.homecontrol.web;

import dev.andre.homecontrol.content.RailUpdatedEvent;
import dev.andre.homecontrol.content.RailsChangedEvent;
import dev.andre.homecontrol.core.DeviceStateChangedEvent;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.function.BooleanSupplier;

/** Fans device state changes out to every open browser tab. */
@Component
public class DeviceStateBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(DeviceStateBroadcaster.class);

    private static final long NO_TIMEOUT = 0L;

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    /** Whether each subscriber may still receive state; checked before every send and on {@link #revalidate()}. */
    private final Map<SseEmitter, BooleanSupplier> allowed = new ConcurrentHashMap<>();

    /**
     * The fan-out's own thread. {@code publishEvent} is synchronous, so without this the
     * loop below would run on the publishing {@code AndroidTvSession}'s single scheduler
     * thread, and one wedged browser blocking in {@code send} would stall that device's
     * reconnects. Single-threaded, so events still reach each tab in the order published.
     */
    private final ExecutorService fanOut = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "home-control-sse-broadcast");
        thread.setDaemon(true);
        return thread;
    });

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
        } catch (RuntimeException e) {
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

    private void enqueue(Send send) {
        try {
            fanOut.execute(() -> broadcast(send));
        } catch (RejectedExecutionException e) {
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
            try {
                send.to(emitter);
            } catch (Throwable t) {
                // Not just IOException: send throws an unchecked IllegalStateException when the
                // emitter completed after this loop took its snapshot of the list, which happens
                // routinely on tab close. Either way this subscriber is finished — drop it, and
                // never let it stop the event reaching the remaining tabs.
                log.debug("Dropping an SSE subscriber after a failed send", t);
                drop(emitter);
                completeQuietly(emitter, t);
            }
        }
    }

    /** The one place a device-state event becomes an SSE frame; package-private so a test can observe the object. */
    void sendData(SseEmitter emitter, DeviceStateChangedEvent event) throws IOException {
        emitter.send(SseEmitter.event().name("state").data(event));
    }

    /** Named non-device events; package-private so a test can observe them. */
    void sendNamed(SseEmitter emitter, String name, Object data) throws IOException {
        emitter.send(SseEmitter.event().name(name).data(data));
    }

    private void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Throwable ignored) {
            // Already gone; the emitter is off the list either way.
        }
    }

    /** {@code completeWithError} throws in turn on an emitter that has already completed. */
    private void completeQuietly(SseEmitter emitter, Throwable cause) {
        try {
            emitter.completeWithError(cause);
        } catch (Throwable ignored) {
            // Already gone; the emitter is off the list either way.
        }
    }

    @PreDestroy
    void shutdown() {
        fanOut.shutdownNow();
    }
}
