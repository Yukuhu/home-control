package dev.andre.homecontrol.content;

import dev.andre.homecontrol.core.content.ContentSource;
import dev.andre.homecontrol.core.content.RailDescriptor;

import java.time.Duration;
import java.util.List;

/** Every rail of every available source, in bean order. Replaced by stored preferences in D4. */
public class DefaultRailPreferences implements RailPreferences {

    private final ContentProperties properties;

    public DefaultRailPreferences(ContentProperties properties) {
        this.properties = properties;
    }

    @Override
    public List<RailDescriptor> rails(List<ContentSource> sources) {
        return sources.stream().filter(ContentSource::available).flatMap(s -> s.rails().stream()).toList();
    }

    @Override
    public Duration refreshInterval(ContentSource source) {
        return properties.rails().refreshIntervals().getOrDefault(source.id(), source.defaultRefreshInterval());
    }
}
