package dev.andre.homecontrol.core.playback;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
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

    /** Opaque workflow identity; never contains a resolved URL or credentials. */
    record WorkflowCast(String workflowId, long revision, String entryKey) implements PlayableRef {
        @Override public String kindLabel() { return "workflow Cast"; }
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

    /** An open, controllable Jellyfin app on the device (spec §5.3 rung 1). Created at play time by a resolver. */
    record JellyfinSession(String sessionId, String itemId, long startPositionTicks, String client) implements PlayableRef {
        @Override
        public String kindLabel() {
            return "Jellyfin app";
        }
    }

    /** A paired Android TV can open Jellyfin before a controllable session exists. */
    record JellyfinApp(String itemId, long startPositionTicks) implements PlayableRef {
        @Override
        public String kindLabel() {
            return "Jellyfin app";
        }
    }

    record StreamUrl(URI url, String mimeType) implements PlayableRef {
        @Override
        public String kindLabel() {
            return "direct stream";
        }

        /** Stream URLs may carry an ApiKey; print them without the query. */
        @Override
        public String toString() {
            String where = url.getScheme() == null ? url.getRawPath()
                    : url.getScheme() + "://" + url.getRawAuthority() + url.getRawPath();
            return "StreamUrl[url=" + where + (url.getRawQuery() == null ? "" : "?…") + ", mimeType=" + mimeType + "]";
        }
    }

    /** A custom-namespace message for a Cast receiver app. Built at play time only: it may carry a token. */
    record CastMessage(String receiverAppId, String namespace, Map<String, Object> message, String receiverLabel)
            implements PlayableRef {
        public CastMessage {
            message = message == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(message));
        }

        @Override
        public String kindLabel() {
            return "cast";
        }

        @Override
        public String toString() {
            return "CastMessage[receiverAppId=" + receiverAppId + ", namespace=" + namespace + "]";
        }
    }

    /**
     * A video to start on a Cast receiver through the receiver's own remote-control pairing (an
     * unofficial, best-effort interface). Created at play time by a resolver, only for devices whose
     * switch is on.
     */
    record YouTubeLounge(String videoId) implements PlayableRef {
        @Override
        public String kindLabel() {
            return "YouTube Cast";
        }
    }
}
