package dev.andre.homecontrol.sources.sports;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Optional;

/** The household's time zone: stored choice, else the configured default, else the JVM's. */
public class SportsTimeZones {

    private final SportsSettingsService settings;
    private final SportsProperties properties;

    public SportsTimeZones(SportsSettingsService settings, SportsProperties properties) {
        this.settings = settings;
        this.properties = properties;
    }

    public ZoneId effective() {
        Optional<ZoneId> stored = parse(settings.current().timeZone());
        if (stored.isPresent()) {
            return stored.get();
        }
        Optional<ZoneId> configured = parse(properties.timeZone());
        if (configured.isPresent()) {
            return configured.get();
        }
        return ZoneId.systemDefault();
    }

    public boolean chosen() {
        return parse(settings.current().timeZone()).isPresent() || parse(properties.timeZone()).isPresent();
    }

    public static Optional<ZoneId> parse(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(id.strip());
        } catch (DateTimeException e) {
            return Optional.empty();
        }
        return zone.getId().contains("/") || zone.getId().equals("UTC") ? Optional.of(zone) : Optional.empty();
    }
}
