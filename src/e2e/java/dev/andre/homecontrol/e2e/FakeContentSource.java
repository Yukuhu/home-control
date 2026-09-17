package dev.andre.homecontrol.e2e;

import dev.andre.homecontrol.core.content.ContentSource;
import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.core.content.Rail;
import dev.andre.homecontrol.core.content.RailDescriptor;
import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.ContentKind;
import dev.andre.homecontrol.core.playback.PlayableRef;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A {@link ContentSource} double: two fixed items, a rail that always works ("picks") and several
 * independent rails that can each be broken/healed on demand ("flaky", "flaky-2", "flaky-3").
 * There is more than one flaky rail so tests that each drive their own break/heal cycle (and the
 * RailCache failure-count/backoff state that comes with it) never interfere with one another —
 * each test uses its own id rather than needing a fresh Spring context per method.
 */
public class FakeContentSource implements ContentSource {

    private static final RailDescriptor PICKS = new RailDescriptor("e2e", "picks", "Picks");
    static final List<String> FLAKY_RAIL_IDS = List.of("flaky", "flaky-2", "flaky-3");

    private final Map<String, ContentItem> items = new LinkedHashMap<>();
    /** One flag per flaky rail id; each starts broken so a fresh test sees the failure by default. */
    private final Map<String, AtomicBoolean> broken = new LinkedHashMap<>();

    public FakeContentSource() {
        FLAKY_RAIL_IDS.forEach(id -> broken.put(id, new AtomicBoolean(true)));
        ContentItem clip1 = new ContentItem("clip-1", "e2e", ContentKind.MOVIE, "Big Buck Bunny", "2008", null,
                List.of(
                        new PlayableRef.AppLink(URI.create("https://www.youtube.com/watch?v=aqz-KE-bpKQ"), "youtube"),
                        new PlayableRef.StreamUrl(URI.create("http://127.0.0.1:9/bunny.mp4"), "video/mp4")),
                0.4);
        ContentItem clip2 = new ContentItem("clip-2", "e2e", ContentKind.MOVIE, "Sintel", null, null,
                List.of(new PlayableRef.AppLink(URI.create("https://www.youtube.com/watch?v=eRsGyueVLvQ"), "youtube")));
        items.put(clip1.id(), clip1);
        items.put(clip2.id(), clip2);
    }

    @Override
    public String id() {
        return "e2e";
    }

    @Override
    public String displayName() {
        return "E2E";
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public boolean searchable() {
        return true;
    }

    @Override
    public List<RailDescriptor> rails() {
        List<RailDescriptor> descriptors = new ArrayList<>();
        descriptors.add(PICKS);
        FLAKY_RAIL_IDS.forEach(id -> descriptors.add(new RailDescriptor("e2e", id, "Flaky rail")));
        return descriptors;
    }

    @Override
    public Rail rail(String railId) {
        if ("picks".equals(railId)) {
            return new Rail(PICKS, List.copyOf(items.values()), Instant.now());
        }
        AtomicBoolean flag = broken.get(railId);
        if (flag == null) {
            throw new IllegalArgumentException("No such rail " + railId);
        }
        if (flag.get()) {
            throw new ContentSourceException("E2E source is down");
        }
        return new Rail(new RailDescriptor("e2e", railId, "Flaky rail"), List.of(items.get("clip-2")), Instant.now());
    }

    @Override
    public Optional<ContentItem> item(String itemId) {
        return Optional.ofNullable(items.get(itemId));
    }

    @Override
    public List<ContentItem> search(String query, int limit) {
        String needle = query.toLowerCase(Locale.ROOT);
        return items.values().stream()
                .filter(item -> item.title().toLowerCase(Locale.ROOT).contains(needle))
                .limit(limit)
                .toList();
    }

    /** Breaks every flaky rail; called before each test so a fresh one always starts broken. */
    public void breakFlaky() {
        broken.values().forEach(flag -> flag.set(true));
    }

    /** Heals every flaky rail. */
    public void heal() {
        broken.values().forEach(flag -> flag.set(false));
    }

    public void breakFlaky(String railId) {
        flagFor(railId).set(true);
    }

    public void heal(String railId) {
        flagFor(railId).set(false);
    }

    private AtomicBoolean flagFor(String railId) {
        AtomicBoolean flag = broken.get(railId);
        if (flag == null) {
            throw new IllegalArgumentException("No such flaky rail " + railId);
        }
        return flag;
    }
}
