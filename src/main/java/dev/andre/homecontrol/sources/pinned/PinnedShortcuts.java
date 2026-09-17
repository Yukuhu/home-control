package dev.andre.homecontrol.sources.pinned;

import dev.andre.homecontrol.core.content.ContentChangedEvent;
import dev.andre.homecontrol.core.content.ContentSource;
import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.core.content.ContentSources;
import dev.andre.homecontrol.core.content.PinOffers;
import dev.andre.homecontrol.core.content.PinnedLinks;
import dev.andre.homecontrol.core.playback.AppLinks;
import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.ContentKind;
import dev.andre.homecontrol.core.playback.PlayableRef;
import dev.andre.homecontrol.core.playback.ServiceLinks;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The household's pinned shortcuts: one JSON file, one writer, in-memory after first load. */
public class PinnedShortcuts implements PinnedLinks {

    private static final int MAX_URL_LENGTH = 2048;
    private static final int MAX_TITLE = 120;
    private static final Pattern UPGRADE_OF =
            Pattern.compile("^([a-z0-9][a-z0-9._-]{0,63})/([A-Za-z0-9._:-]{1,128})$");

    private final JsonFilePinStore store;
    private final PinnedProperties properties;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final SecureRandom random;
    private final ObjectProvider<ContentSources> sources;

    /** Guarded by {@code this}; {@code null} until first use, then kept in sync with the file. */
    private List<Pin> pins;

    public PinnedShortcuts(JsonFilePinStore store, PinnedProperties properties, ApplicationEventPublisher events,
                           Clock clock, SecureRandom random, ObjectProvider<ContentSources> sources) {
        this.store = store;
        this.properties = properties;
        this.events = events;
        this.clock = clock;
        this.random = random;
        this.sources = sources;
    }

    public synchronized List<Pin> all() {
        return List.copyOf(ensureLoaded());
    }

    public synchronized Optional<Pin> find(String id) {
        return ensureLoaded().stream().filter(pin -> pin.id().equals(id)).findFirst();
    }

    @Override
    public synchronized Optional<PlayableRef.AppLink> linkFor(String sourceId, String itemId) {
        String key = sourceId + "/" + itemId;
        return ensureLoaded().stream()
                .filter(pin -> key.equals(pin.upgradeOf()))
                .findFirst()
                .map(pin -> ServiceLinks.appLink(pin.url()));
    }

    public Pin add(String url, String title) {
        Pin pin;
        synchronized (this) {
            List<Pin> current = ensureLoaded();
            String trimmedUrl = url == null ? "" : url.strip();
            if (trimmedUrl.isEmpty()) {
                throw new IllegalArgumentException("Enter a link to pin");
            }
            if (trimmedUrl.length() > MAX_URL_LENGTH) {
                throw new IllegalArgumentException("That link is too long to pin");
            }
            URI parsed = ServiceLinks.canonical(AppLinks.parseHttpUrl(trimmedUrl));
            String service = AppLinks.serviceOf(parsed.getHost().toLowerCase(Locale.ROOT), parsed.getPath());
            boolean duplicate = current.stream().anyMatch(existing -> existing.url().toString().equals(parsed.toString()));
            if (duplicate) {
                throw new IllegalArgumentException("That link is already pinned");
            }
            if (current.size() >= properties.maxPins()) {
                throw new IllegalArgumentException("You can pin up to " + properties.maxPins() + " links");
            }
            String trimmedTitle = title == null ? "" : title.strip();
            if (trimmedTitle.length() > MAX_TITLE) {
                throw new IllegalArgumentException("Keep the title under 120 characters");
            }
            if (trimmedTitle.isEmpty()) {
                trimmedTitle = ServiceLinks.displayName(service).map(name -> name + " link").orElse(parsed.getHost());
            }
            String subtitle = ServiceLinks.label(service, parsed);
            String id = newId(current);
            pin = new Pin(id, parsed, service, trimmedTitle, subtitle, null, ContentKind.VIDEO, null, clock.instant());
            List<Pin> next = new ArrayList<>(current);
            next.add(pin);
            store.save(next);
            pins = next;
        }
        events.publishEvent(new ContentChangedEvent("pinned"));
        return pin;
    }

    /**
     * Pastes a link for an item a source could only open at the app level (spec §11). Reads the
     * item and validates it can be upgraded before taking the lock, so a slow or failing source
     * lookup never blocks other pin operations.
     */
    public Pin addUpgrade(String url, String upgradeOf) {
        Matcher matcher = UPGRADE_OF.matcher(upgradeOf == null ? "" : upgradeOf);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("That item cannot be pinned");
        }
        String sourceId = matcher.group(1);
        String itemId = matcher.group(2);

        ContentSources registry = sources.getIfAvailable();
        ContentSource source = registry == null ? null : registry.find(sourceId).orElse(null);
        if (source == null) {
            throw new IllegalArgumentException("That item is no longer available");
        }
        ContentItem item;
        try {
            item = source.item(itemId).orElseThrow(() -> new IllegalArgumentException("That item is no longer available"));
        } catch (ContentSourceException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
        PinOffers.Offer offer = PinOffers.offer(item)
                .orElseThrow(() -> new IllegalArgumentException("This item already opens directly"));

        String trimmedUrl = url == null ? "" : url.strip();
        if (trimmedUrl.isEmpty()) {
            throw new IllegalArgumentException("Enter a link to pin");
        }
        if (trimmedUrl.length() > MAX_URL_LENGTH) {
            throw new IllegalArgumentException("That link is too long to pin");
        }
        URI link = ServiceLinks.canonical(AppLinks.parseHttpUrl(trimmedUrl));
        String service = AppLinks.serviceOf(link.getHost().toLowerCase(Locale.ROOT), link.getPath());
        String subtitle = ServiceLinks.label(service, link);
        URI artwork = safeArtwork(item.artwork());

        Pin pin;
        synchronized (this) {
            List<Pin> current = ensureLoaded();
            int existingIndex = indexOfUpgrade(current, offer.upgradeOf());
            if (existingIndex >= 0) {
                Pin old = current.get(existingIndex);
                pin = new Pin(old.id(), link, service, item.title(), subtitle, artwork, item.kind(),
                        offer.upgradeOf(), old.createdAt());
                List<Pin> next = new ArrayList<>(current);
                next.set(existingIndex, pin);
                store.save(next);
                pins = next;
            } else {
                boolean duplicate = current.stream().anyMatch(existing -> existing.url().toString().equals(link.toString()));
                if (duplicate) {
                    throw new IllegalArgumentException("That link is already pinned");
                }
                String id = newId(current);
                pin = new Pin(id, link, service, item.title(), subtitle, artwork, item.kind(),
                        offer.upgradeOf(), clock.instant());
                List<Pin> next = new ArrayList<>(current);
                next.add(pin);
                store.save(next);
                pins = next;
            }
        }
        events.publishEvent(new ContentChangedEvent("pinned"));
        events.publishEvent(new ContentChangedEvent(sourceId));
        return pin;
    }

    public void rename(String id, String title) {
        synchronized (this) {
            List<Pin> current = ensureLoaded();
            int index = indexOf(current, id);
            String trimmedTitle = title == null ? "" : title.strip();
            if (trimmedTitle.length() > MAX_TITLE) {
                throw new IllegalArgumentException("Keep the title under 120 characters");
            }
            if (trimmedTitle.isEmpty()) {
                throw new IllegalArgumentException("Enter a title");
            }
            Pin old = current.get(index);
            Pin renamed = new Pin(old.id(), old.url(), old.service(), trimmedTitle, old.subtitle(), old.artwork(),
                    old.kind(), old.upgradeOf(), old.createdAt());
            List<Pin> next = new ArrayList<>(current);
            next.set(index, renamed);
            store.save(next);
            pins = next;
        }
        events.publishEvent(new ContentChangedEvent("pinned"));
    }

    public void move(String id, boolean up) {
        boolean changed = false;
        synchronized (this) {
            List<Pin> current = ensureLoaded();
            int index = indexOf(current, id);
            int swapWith = up ? index - 1 : index + 1;
            if (swapWith >= 0 && swapWith < current.size()) {
                List<Pin> next = new ArrayList<>(current);
                Pin a = next.get(index);
                Pin b = next.get(swapWith);
                next.set(index, b);
                next.set(swapWith, a);
                store.save(next);
                pins = next;
                changed = true;
            }
        }
        if (changed) {
            events.publishEvent(new ContentChangedEvent("pinned"));
        }
    }

    public void remove(String id) {
        synchronized (this) {
            List<Pin> current = ensureLoaded();
            int index = indexOf(current, id);
            List<Pin> next = new ArrayList<>(current);
            next.remove(index);
            store.save(next);
            pins = next;
        }
        events.publishEvent(new ContentChangedEvent("pinned"));
    }

    private static int indexOf(List<Pin> current, String id) {
        for (int i = 0; i < current.size(); i++) {
            if (current.get(i).id().equals(id)) {
                return i;
            }
        }
        throw new IllegalArgumentException("No pinned link " + id);
    }

    private static int indexOfUpgrade(List<Pin> current, String upgradeOf) {
        for (int i = 0; i < current.size(); i++) {
            if (upgradeOf.equals(current.get(i).upgradeOf())) {
                return i;
            }
        }
        return -1;
    }

    private static URI safeArtwork(URI artwork) {
        if (artwork == null) {
            return null;
        }
        String raw = artwork.toString();
        boolean acceptable = raw.startsWith("https://") || (raw.startsWith("/") && !raw.startsWith("//"));
        return acceptable ? artwork : null;
    }

    private String newId(List<Pin> current) {
        Set<String> existing = new HashSet<>();
        current.forEach(pin -> existing.add(pin.id()));
        for (int attempt = 0; attempt < 1000; attempt++) {
            byte[] bytes = new byte[6];
            random.nextBytes(bytes);
            StringBuilder id = new StringBuilder("p-");
            for (byte b : bytes) {
                id.append(String.format(Locale.ROOT, "%02x", b));
            }
            if (existing.add(id.toString())) {
                return id.toString();
            }
        }
        throw new IllegalStateException("Could not generate a unique pin id");
    }

    private List<Pin> ensureLoaded() {
        if (pins == null) {
            pins = new ArrayList<>(store.load());
        }
        return pins;
    }
}
