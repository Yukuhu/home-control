package dev.andre.homecontrol.e2e;

import dev.andre.homecontrol.core.content.ContentSource;
import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.core.content.PinnedLinks;
import dev.andre.homecontrol.core.content.Rail;
import dev.andre.homecontrol.core.content.RailDescriptor;
import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.ContentKind;
import dev.andre.homecontrol.core.playback.PlayableRef;
import dev.andre.homecontrol.core.playback.ServiceLinks;
import org.springframework.beans.factory.ObjectProvider;

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
 * independent rails that can each be broken/healed on demand ("flaky", "flaky-2", "flaky-3"), plus
 * a "launcher" item (G5) that is never in a rail but shows up in search and by id — it only ever
 * opens the Netflix app home, unless {@link PinnedLinks} has an upgrade link for it, exactly like a
 * real streaming source's item with no direct playback link.
 * There is more than one flaky rail so tests that each drive their own break/heal cycle (and the
 * RailCache failure-count/backoff state that comes with it) never interfere with one another —
 * each test uses its own id rather than needing a fresh Spring context per method.
 */
public class FakeContentSource implements ContentSource {

    private static final RailDescriptor PICKS = new RailDescriptor("e2e", "picks", "Picks");
    private static final String LAUNCHER_ID = "launcher-1";
    static final List<String> FLAKY_RAIL_IDS = List.of("flaky", "flaky-2", "flaky-3", "flaky-4", "flaky-5");

    private final Map<String, ContentItem> items = new LinkedHashMap<>();
    private final ContentItem launcherBase;
    private final ObjectProvider<PinnedLinks> pinnedLinks;
    /** One flag per flaky rail id; each starts broken so a fresh test sees the failure by default. */
    private final Map<String, AtomicBoolean> broken = new LinkedHashMap<>();

    public FakeContentSource(ObjectProvider<PinnedLinks> pinnedLinks) {
        this.pinnedLinks = pinnedLinks;
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
        launcherBase = new ContentItem(LAUNCHER_ID, "e2e", ContentKind.MOVIE, "Launcher Film", "On Netflix", null, List.of());
    }

    /** Recomputed on every call, like a real streaming source: a pinned upgrade wins, else the app home. */
    private ContentItem launcher() {
        PinnedLinks links = pinnedLinks.getIfAvailable();
        Optional<PlayableRef.AppLink> pinned = links == null ? Optional.empty() : links.linkFor("e2e", LAUNCHER_ID);
        PlayableRef.AppLink playable = pinned.orElseGet(() ->
                new PlayableRef.AppLink(ServiceLinks.appHome("netflix").orElseThrow(), "netflix"));
        return launcherBase.withPlayables(List.of(playable));
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
        if (LAUNCHER_ID.equals(itemId)) {
            return Optional.of(launcher());
        }
        return Optional.ofNullable(items.get(itemId));
    }

    @Override
    public List<ContentItem> search(String query, int limit) {
        String needle = query.toLowerCase(Locale.ROOT);
        List<ContentItem> candidates = new ArrayList<>(items.values());
        candidates.add(launcher());
        return candidates.stream()
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
