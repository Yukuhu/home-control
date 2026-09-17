package dev.andre.homecontrol.content;

import dev.andre.homecontrol.core.content.SourcePreferences;
import dev.andre.homecontrol.storage.JsonFileSourceSettings;
import org.springframework.context.ApplicationEventPublisher;

import java.util.function.UnaryOperator;

/**
 * The single writer of {@code sources.json}'s {@code preferences} object (spec D4). Fills in the
 * configured locale/region defaults when nothing has been stored yet, validates every change by
 * constructing a new {@link SourcePreferences}, and tells the rail cache to reschedule.
 */
public class SourcePreferencesService {

    private final JsonFileSourceSettings settings;
    private final ContentProperties properties;
    private final ApplicationEventPublisher events;

    public SourcePreferencesService(JsonFileSourceSettings settings, ContentProperties properties,
                                    ApplicationEventPublisher events) {
        this.settings = settings;
        this.properties = properties;
        this.events = events;
    }

    public synchronized SourcePreferences current() {
        SourcePreferences stored = settings.preferences()
                .orElseGet(() -> SourcePreferences.defaults(properties.locale(), properties.region()));
        if (stored.locale() != null && stored.region() != null) {
            return stored;
        }
        return stored.withLocale(
                stored.locale() == null ? properties.locale() : stored.locale(),
                stored.region() == null ? properties.region() : stored.region(),
                stored.providers());
    }

    /** Validates by construction: an invalid change throws and leaves storage untouched. */
    public synchronized SourcePreferences update(UnaryOperator<SourcePreferences> change) {
        SourcePreferences next = change.apply(current());
        settings.putPreferences(next);
        events.publishEvent(new SourcePreferencesChangedEvent());
        return next;
    }
}
