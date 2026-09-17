package dev.andre.homecontrol.sources.sports;

import dev.andre.homecontrol.core.content.ContentSource;
import dev.andre.homecontrol.core.content.Rail;
import dev.andre.homecontrol.core.content.RailDescriptor;
import dev.andre.homecontrol.core.content.SourcePreferences;
import dev.andre.homecontrol.core.playback.ContentItem;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/** Live and upcoming sport, read from whichever feeds the household configured. No rails yet (Task 4). */
public class SportsContentSource implements ContentSource {

    private final SportsSettingsService settings;
    private final SportsSchedule schedule;
    private final SportsTimeZones zones;
    private final Supplier<SourcePreferences> preferences;
    private final Clock clock;

    public SportsContentSource(SportsSettingsService settings, SportsSchedule schedule, SportsTimeZones zones,
                               Supplier<SourcePreferences> preferences, Clock clock) {
        this.settings = settings;
        this.schedule = schedule;
        this.zones = zones;
        this.preferences = preferences;
        this.clock = clock;
    }

    @Override
    public String id() {
        return "sports";
    }

    @Override
    public String displayName() {
        return "Sports";
    }

    @Override
    public boolean available() {
        return schedule.hasFeeds();
    }

    @Override
    public List<RailDescriptor> rails() {
        return List.of();
    }

    @Override
    public Rail rail(String railId) {
        throw new IllegalArgumentException("Sports has no rail '" + railId + "'");
    }

    @Override
    public Optional<ContentItem> item(String itemId) {
        return schedule.find(itemId)
                .map(event -> SportsItems.toItem(event, settings.current(), zones.effective(), locale(), clock.instant()));
    }

    private Locale locale() {
        return Locale.forLanguageTag(preferences.get().locale());
    }
}
