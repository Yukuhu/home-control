package dev.andre.homecontrol.sources.sports;

import dev.andre.homecontrol.core.content.ContentSource;
import dev.andre.homecontrol.core.content.PinnedLinks;
import dev.andre.homecontrol.core.content.Rail;
import dev.andre.homecontrol.core.content.RailDescriptor;
import dev.andre.homecontrol.core.content.SourcePreferences;
import dev.andre.homecontrol.core.playback.ContentItem;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/** Live and upcoming sport, read from whichever feeds the household configured. */
public class SportsContentSource implements ContentSource {

    private final SportsSettingsService settings;
    private final SportsSchedule schedule;
    private final SportsTimeZones zones;
    private final Supplier<SourcePreferences> preferences;
    private final Clock clock;
    private final ObjectProvider<PinnedLinks> pinnedLinks;
    private final SportsProperties properties;

    public SportsContentSource(SportsSettingsService settings, SportsSchedule schedule, SportsTimeZones zones,
                               Supplier<SourcePreferences> preferences, Clock clock,
                               ObjectProvider<PinnedLinks> pinnedLinks, SportsProperties properties) {
        this.settings = settings;
        this.schedule = schedule;
        this.zones = zones;
        this.preferences = preferences;
        this.clock = clock;
        this.pinnedLinks = pinnedLinks;
        this.properties = properties;
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
        return available() ? List.of(new RailDescriptor("sports", LiveTodayRail.ID, LiveTodayRail.TITLE)) : List.of();
    }

    @Override
    public Rail rail(String railId) {
        if (!LiveTodayRail.ID.equals(railId)) {
            throw new IllegalArgumentException("Sports has no rail '" + railId + "'");
        }
        List<SportsEvent> events = schedule.events();
        Instant now = clock.instant();
        var zone = zones.effective();
        SportsSettings currentSettings = settings.current();
        Locale locale = locale();
        PinnedLinks links = pinnedLinks.getIfAvailable();
        List<ContentItem> items = LiveTodayRail.select(events, now, zone, properties.railSize()).stream()
                .map(event -> SportsItems.toItem(event, currentSettings, zone, locale, now, links))
                .toList();
        return new Rail(new RailDescriptor("sports", LiveTodayRail.ID, LiveTodayRail.TITLE), items, now);
    }

    @Override
    public Optional<ContentItem> item(String itemId) {
        return schedule.find(itemId)
                .map(event -> SportsItems.toItem(event, settings.current(), zones.effective(), locale(),
                        clock.instant(), pinnedLinks.getIfAvailable()));
    }

    @Override
    public Duration defaultRefreshInterval() {
        return Duration.ofMinutes(5);
    }

    private Locale locale() {
        return Locale.forLanguageTag(preferences.get().locale());
    }
}
