package dev.andre.homecontrol.content;

import dev.andre.homecontrol.core.content.ContentSource;
import dev.andre.homecontrol.core.content.RailDescriptor;
import dev.andre.homecontrol.core.content.SourcePreferences;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link RailPreferences} backed by the stored {@link SourcePreferences} (spec D4): rail order,
 * hidden rails, disabled sources and per-source refresh intervals all come from here now.
 */
public class StoredRailPreferences implements RailPreferences {

    private final SourcePreferencesService preferences;
    private final ContentProperties properties;

    public StoredRailPreferences(SourcePreferencesService preferences, ContentProperties properties) {
        this.preferences = preferences;
        this.properties = properties;
    }

    @Override
    public List<RailDescriptor> rails(List<ContentSource> sources) {
        SourcePreferences current = preferences.current();
        return allRailsInOrder(sources, current).stream()
                .filter(descriptor -> !current.hiddenRails().contains(RailSnapshot.key(descriptor)))
                .toList();
    }

    /** Every rail of every available, enabled source, in display order — hidden rails included, for the setup page. */
    public List<RailDescriptor> allRailsInOrder(List<ContentSource> sources) {
        return allRailsInOrder(sources, preferences.current());
    }

    private List<RailDescriptor> allRailsInOrder(List<ContentSource> sources, SourcePreferences current) {
        List<RailDescriptor> gathered = sources.stream()
                .filter(ContentSource::available)
                .filter(source -> !current.disabledSources().contains(source.id()))
                .flatMap(source -> source.rails().stream())
                .toList();

        Map<String, RailDescriptor> remaining = new LinkedHashMap<>();
        gathered.forEach(descriptor -> remaining.put(RailSnapshot.key(descriptor), descriptor));

        List<RailDescriptor> ordered = new ArrayList<>();
        for (String key : current.railOrder()) {
            RailDescriptor descriptor = remaining.remove(key);
            if (descriptor != null) {
                ordered.add(descriptor);
            }
        }
        ordered.addAll(remaining.values());
        return ordered;
    }

    @Override
    public Duration refreshInterval(ContentSource source) {
        Integer minutes = preferences.current().refreshMinutes().get(source.id());
        return minutes != null ? Duration.ofMinutes(minutes) : defaultRefreshInterval(source);
    }

    /** What {@link #refreshInterval} would return with no stored override — the setup page's placeholder. */
    public Duration defaultRefreshInterval(ContentSource source) {
        Duration configured = properties.rails().refreshIntervals().get(source.id());
        return configured != null ? configured : source.defaultRefreshInterval();
    }

    @Override
    public boolean sourceEnabled(String sourceId) {
        return !preferences.current().disabledSources().contains(sourceId);
    }
}
