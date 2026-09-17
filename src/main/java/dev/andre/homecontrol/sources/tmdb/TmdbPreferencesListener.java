package dev.andre.homecontrol.sources.tmdb;

import dev.andre.homecontrol.content.SourcePreferencesChangedEvent;
import dev.andre.homecontrol.core.content.ContentChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;

/** A locale/region/provider change makes the trending rail's picks stale; refresh it now. */
public class TmdbPreferencesListener {

    private final ApplicationEventPublisher events;

    public TmdbPreferencesListener(ApplicationEventPublisher events) {
        this.events = events;
    }

    @EventListener
    public void onPreferencesChanged(SourcePreferencesChangedEvent event) {
        events.publishEvent(new ContentChangedEvent("tmdb"));
    }
}
