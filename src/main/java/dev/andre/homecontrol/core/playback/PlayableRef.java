package dev.andre.homecontrol.core.playback;

import java.net.URI;
import java.util.Map;

/**
 * One way an item could be played. A source attaches every reference it can build; the
 * planner picks. Variants beyond {@link AppLink} are defined here so later sub-projects
 * conform to one contract, but only app links route in sub-project A (spec §5.2).
 */
public sealed interface PlayableRef {

    /** Human word for the reference kind, used in "no route" explanations. */
    String kindLabel();

    /** A URI the device should hand to whichever app claims it. {@code service} is a lower-case key such as {@code youtube}. */
    record AppLink(URI uri, String service) implements PlayableRef {
        @Override
        public String kindLabel() {
            return "app link";
        }
    }

    record CastLoad(String receiverAppId, Map<String, Object> payload) implements PlayableRef {
        @Override
        public String kindLabel() {
            return "cast";
        }
    }

    record JellyfinItem(String serverId, String itemId, long resumeTicks) implements PlayableRef {
        @Override
        public String kindLabel() {
            return "Jellyfin";
        }
    }

    record StreamUrl(URI url, String mimeType) implements PlayableRef {
        @Override
        public String kindLabel() {
            return "direct stream";
        }
    }
}
