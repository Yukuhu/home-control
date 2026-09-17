package dev.andre.homecontrol.sources.sports;

import dev.andre.homecontrol.core.content.ContentChangedEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.util.function.UnaryOperator;

/** The single reader/writer of sports settings; caches after the first load and publishes on every change. */
public class SportsSettingsService {

    private final JsonFileSportsStore store;
    private final ApplicationEventPublisher events;
    private final Object lock = new Object();
    private volatile SportsSettings cached;

    public SportsSettingsService(JsonFileSportsStore store, ApplicationEventPublisher events) {
        this.store = store;
        this.events = events;
    }

    public SportsSettings current() {
        SportsSettings value = cached;
        if (value != null) {
            return value;
        }
        synchronized (lock) {
            if (cached == null) {
                cached = store.load();
            }
            return cached;
        }
    }

    public SportsSettings update(UnaryOperator<SportsSettings> op) {
        SportsSettings next;
        synchronized (lock) {
            next = op.apply(current());
            store.save(next);
            cached = next;
        }
        events.publishEvent(new ContentChangedEvent("sports"));
        return next;
    }
}
