package dev.andre.homecontrol.sources.pinned;

import dev.andre.homecontrol.core.content.ContentSource;
import dev.andre.homecontrol.core.content.Rail;
import dev.andre.homecontrol.core.content.RailDescriptor;
import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.ServiceLinks;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** The household's pinned shortcuts as a single rail. Local; nothing is fetched over the network. */
public class PinnedContentSource implements ContentSource {

    public static final String SOURCE_ID = "pinned";
    private static final RailDescriptor RAIL = new RailDescriptor(SOURCE_ID, "pinned", "Pinned");

    private final PinnedShortcuts pins;

    public PinnedContentSource(PinnedShortcuts pins) {
        this.pins = pins;
    }

    @Override
    public String id() {
        return SOURCE_ID;
    }

    @Override
    public String displayName() {
        return "Pinned";
    }

    @Override
    public boolean available() {
        return !pins.all().isEmpty();
    }

    @Override
    public List<RailDescriptor> rails() {
        return available() ? List.of(RAIL) : List.of();
    }

    @Override
    public Rail rail(String railId) {
        if (!RAIL.id().equals(railId)) {
            throw new IllegalArgumentException("Pinned has no rail '" + railId + "'");
        }
        return new Rail(RAIL, pins.all().stream().map(PinnedContentSource::toItem).toList(), Instant.now());
    }

    @Override
    public Optional<ContentItem> item(String itemId) {
        return pins.find(itemId).map(PinnedContentSource::toItem);
    }

    @Override
    public boolean searchable() {
        return true;
    }

    @Override
    public List<ContentItem> search(String query, int limit) {
        String needle = query == null ? "" : query.toLowerCase(Locale.ROOT);
        return pins.all().stream()
                .filter(pin -> pin.title().toLowerCase(Locale.ROOT).contains(needle))
                .limit(limit)
                .map(PinnedContentSource::toItem)
                .toList();
    }

    @Override
    public Duration defaultRefreshInterval() {
        return Duration.ofHours(24);
    }

    static ContentItem toItem(Pin pin) {
        return new ContentItem(pin.id(), SOURCE_ID, pin.kind(), pin.title(), pin.subtitle(), pin.artwork(),
                List.of(ServiceLinks.appLink(pin.url())), null);
    }
}
