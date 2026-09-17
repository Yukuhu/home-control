package dev.andre.homecontrol.core.playback;

import dev.andre.homecontrol.core.Action;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** The planner's answer: an executable route, or the reason there is none. Shown to the user before playing. */
public sealed interface Route {

    String describe();

    record OpenAppLink(URI uri, String service) implements Route {

        public Action action() {
            return new Action.OpenAppLink(uri);
        }

        @Override
        public String describe() {
            return ServiceLinks.displayName(service).map(name -> "Open in the " + name + " app")
                    .orElseGet(() -> "Open " + uri.getHost() + " on the device");
        }
    }

    /** Run a Cast receiver app and send it a LOAD (spec §5.3 rung 3). */
    record Cast(String receiverAppId, Map<String, Object> load) implements Route {

        private static final Map<String, String> RECEIVER_NAMES = Map.of(
                "CC1AD845", "the Default Media Receiver",
                "F007D354", "the Jellyfin receiver");

        public Cast {
            load = load == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(load));
        }

        public Action action() {
            return new Action.CastLoad(receiverAppId, load);
        }

        @Override
        public String describe() {
            String name = RECEIVER_NAMES.get(receiverAppId);
            return name == null ? "Cast with receiver app " + receiverAppId : "Cast with " + name;
        }

        /** The load map can carry a StreamUrl with an API key; never print it. */
        @Override
        public String toString() {
            return "Cast[receiverAppId=" + receiverAppId + "]";
        }
    }

    /** Run a Cast receiver app and send it a custom message (spec §5.3 rung 3). */
    record CastMessage(String receiverAppId, String namespace, Map<String, Object> message, String receiverLabel)
            implements Route {
        public CastMessage {
            message = message == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(message));
        }

        public Action action() {
            return new Action.CastMessage(receiverAppId, namespace, message);
        }

        @Override
        public String describe() {
            return "Cast with " + receiverLabel;
        }

        @Override
        public String toString() {
            return "CastMessage[receiverAppId=" + receiverAppId + ", namespace=" + namespace + "]";
        }
    }

    /** Tell the Jellyfin app already open on the device to play the item. Executed by a RouteExecutor, not an adapter. */
    record JellyfinSession(String sessionId, String itemId, long startPositionTicks, String client) implements Route {
        @Override
        public String describe() {
            return client == null || client.isBlank()
                    ? "Play in the open Jellyfin app"
                    : "Play in the open Jellyfin app (" + client + ")";
        }
    }

    /**
     * Start the video on a Cast receiver through its best-effort remote-control pairing. Executed by a
     * RouteExecutor, not an adapter; the device part goes through DeviceManager.query.
     */
    record YouTubeLounge(String videoId) implements Route {
        @Override
        public String describe() {
            return "Cast with the YouTube receiver (best effort)";
        }
    }

    record Unroutable(String reason) implements Route {
        @Override
        public String describe() {
            return reason;
        }
    }
}
