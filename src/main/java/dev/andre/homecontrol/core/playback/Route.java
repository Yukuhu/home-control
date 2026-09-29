package dev.andre.homecontrol.core.playback;

import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.RedactedUris;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** The planner's answer: an executable route, or the reason there is none. Shown to the user before playing. */
public sealed interface Route permits DeviceRoute, DelegatedRoute, Route.Unroutable {

    /** A stable, browser-visible identifier; never contains a payload, which may carry a token. */
    String key();

    /** True when success only means "the device accepted it" (spec §5.3: the app may not be installed). */
    default boolean optimistic() {
        return false;
    }

    String describe();

    /**
     * Same description, but allowed to name what is being opened by the item's kind (e.g. a
     * {@link ContentKind#LIVE_EVENT} is "this event", not "this title"). Routing itself never
     * special-cases a kind (spec §5.3); only the wording here does. Defaults to {@link #describe()}
     * for every route that has nothing kind-specific to say.
     */
    default String describe(ContentKind kind) {
        return describe();
    }

    record OpenAppLink(URI uri, String service) implements DeviceRoute {
        @Override
        public String key() {
            return "app-link";
        }

        @Override
        public boolean optimistic() {
            return true;
        }

        @Override
        public Action action() {
            return new Action.OpenAppLink(uri);
        }

        @Override
        public String describe() {
            return describe(ContentKind.VIDEO);
        }

        @Override
        public String describe(ContentKind kind) {
            Optional<String> name = ServiceLinks.displayName(service);
            if (ServiceLinks.isAppHome(uri)) {
                String noun = kind == ContentKind.LIVE_EVENT ? "event" : "title";
                return "Open the " + name.orElse(uri.getHost()) + " app (not this " + noun + ")";
            }
            return name.map(n -> "Open in the " + n + " app").orElseGet(() -> "Open " + uri.getHost() + " on the device");
        }
    }

    /** Run a Cast receiver app and send it a LOAD (spec §5.3 rung 3). */
    record Cast(String receiverAppId, Map<String, Object> load) implements DeviceRoute {
        @Override
        public String key() {
            return "cast:" + receiverAppId;
        }

        private static final Map<String, String> RECEIVER_NAMES = Map.of(
                "CC1AD845", "the Default Media Receiver",
                "F007D354", "the Jellyfin receiver");

        public Cast {
            load = load == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(load));
        }

        @Override
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
    record CastMessage(Action.CastMessage message, String receiverLabel) implements DeviceRoute {
        @Override
        public String key() {
            return "cast-message:" + message.receiverAppId();
        }

        @Override
        public Action action() {
            return message;
        }

        @Override
        public String describe() {
            return "Cast with " + receiverLabel;
        }

        @Override
        public String toString() {
            return message.toString();
        }
    }

    /** Hand a direct stream to a DLNA/UPnP/Sonos media renderer (spec §5.3 rung 4). */
    record Render(URI url, String mimeType, String title, String subtitle) implements DeviceRoute {
        @Override
        public String key() {
            return "render";
        }

        @Override
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

    /** Play an audio stream with the server's own player on a local audio sink such as a Bluetooth speaker (spec §5.3 rung 5). */
    record PlayLocally(URI url, String mimeType, String title, String subtitle) implements DeviceRoute {
        @Override
        public String key() {
            return "local-audio";
        }

        @Override
        public Action action() {
            return new Action.PlayMedia(url, mimeType, title, subtitle);
        }

        @Override
        public String describe() {
            return "Play through the server on this Bluetooth speaker";
        }

        @Override
        public String toString() {
            return "PlayLocally[url=" + RedactedUris.withoutQuery(url) + ", mimeType=" + mimeType + ", title=" + title + "]";
        }
    }

    record Unroutable(String reason) implements Route {
        @Override
        public String key() {
            return "unroutable";
        }

        @Override
        public String describe() {
            return reason;
        }
    }
}
