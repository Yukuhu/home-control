package dev.andre.homecontrol.core.playback;

import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.RedactedUris;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** The planner's answer: an executable route, or the reason there is none. Shown to the user before playing. */
public sealed interface Route {

    String describe();

    record OpenAppLink(URI uri, String service) implements Route {

        public Action action() {
            return new Action.OpenAppLink(uri);
        }

        @Override
        public String describe() {
            Optional<String> name = ServiceLinks.displayName(service);
            if (ServiceLinks.isAppHome(uri)) {
                return "Open the " + name.orElse(uri.getHost()) + " app (not this title)";
            }
            return name.map(n -> "Open in the " + n + " app").orElseGet(() -> "Open " + uri.getHost() + " on the device");
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

    /** Hand a direct stream to a DLNA/UPnP/Sonos media renderer (spec §5.3 rung 4). */
    record Render(URI url, String mimeType, String title, String subtitle) implements Route {

        public Action action() {
            return new Action.PlayMedia(url, mimeType, title, subtitle);
        }

        @Override
        public String describe() {
            return "Stream directly to this device (DLNA/UPnP)";
        }

        /** The URL can carry a Jellyfin ApiKey; never print its query. */
        @Override
        public String toString() {
            return "Render[url=" + RedactedUris.withoutQuery(url) + ", mimeType=" + mimeType + ", title=" + title + "]";
        }
    }

    record Unroutable(String reason) implements Route {
        @Override
        public String describe() {
            return reason;
        }
    }
}
