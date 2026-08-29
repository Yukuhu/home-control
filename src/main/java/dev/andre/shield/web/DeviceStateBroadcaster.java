package dev.andre.shield.web;

import dev.andre.shield.device.DeviceStateChangedEvent;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Fans device state changes out to every open browser tab. */
@Component
public class DeviceStateBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(DeviceStateBroadcaster.class);

    private static final long NO_TIMEOUT = 0L;

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    /**
     * The fan-out's own thread. {@code publishEvent} is synchronous, so without this the
     * loop below would run on the publishing {@code DeviceSession}'s single scheduler
     * thread, and one wedged browser blocking in {@code send} would stall that device's
     * reconnects. Single-threaded, so events still reach each tab in the order published.
     */
    private final ExecutorService fanOut = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "shield-sse-broadcast");
        thread.setDaemon(true);
        return thread;
    });

    public SseEmitter subscribe() {
        return register(new SseEmitter(NO_TIMEOUT));
    }

    /** Wires one emitter's lifecycle callbacks and adds it to the fan-out. */
    SseEmitter register(SseEmitter emitter) {
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(error -> emitters.remove(emitter));
        emitters.add(emitter);
        return emitter;
    }

    /**
     * Undoes a {@link #subscribe()} whose caller never got to hand the emitter back to
     * Spring — e.g. the initial state send failed. Without this, that emitter's
     * onCompletion/onTimeout/onError never fire (Spring never adopted it), so it would
     * otherwise sit in this list forever.
     */
    void unsubscribe(SseEmitter emitter) {
        emitters.remove(emitter);
    }

    @EventListener
    public void onStateChanged(DeviceStateChangedEvent event) {
        try {
            fanOut.execute(() -> broadcast(event));
        } catch (RejectedExecutionException e) {
            // The application is shutting down; there is nobody left to tell.
        }
    }

    private void broadcast(DeviceStateChangedEvent event) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("state").data(event.state()));
            } catch (Throwable t) {
                // Not just IOException: send throws an unchecked IllegalStateException when the
                // emitter completed after this loop took its snapshot of the list, which happens
                // routinely on tab close. Either way this subscriber is finished — drop it, and
                // never let it stop the event reaching the remaining tabs.
                log.debug("Dropping an SSE subscriber after a failed send", t);
                emitters.remove(emitter);
                completeQuietly(emitter, t);
            }
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
