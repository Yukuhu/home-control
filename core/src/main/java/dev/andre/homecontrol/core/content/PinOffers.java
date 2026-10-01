package dev.andre.homecontrol.core.content;

import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.PlayableRef;
import dev.andre.homecontrol.core.playback.ServiceLinks;

import java.util.Optional;
import java.util.Set;

/** When the play sheet offers "paste a link to open this title directly". */
public final class PinOffers {

    public record Offer(String upgradeOf, String service) {
    }

    private static final Set<String> NOT_UPGRADABLE = Set.of("pinned", "manual");

    private PinOffers() {
    }

    public static Optional<Offer> offer(ContentItem item) {
        if (NOT_UPGRADABLE.contains(item.sourceId())) {
            return Optional.empty();
        }
        String service = null;
        for (PlayableRef ref : item.playables()) {
            if (!(ref instanceof PlayableRef.AppLink(var uri, var appService)) || !ServiceLinks.isAppHome(uri)) {
                return Optional.empty();
            }
            if (service == null) {
                service = appService;
            }
        }
        return Optional.of(new Offer(item.sourceId() + "/" + item.id(), service));
    }
}
