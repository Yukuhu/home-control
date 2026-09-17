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

        private static final Map<String, String> SERVICE_NAMES = Map.of(
                "youtube", "YouTube", "netflix", "Netflix", "primevideo", "Prime Video",
                "dazn", "DAZN", "jellyfin", "Jellyfin");

        public Action action() {
            return new Action.OpenAppLink(uri);
        }

        @Override
        public String describe() {
            String name = SERVICE_NAMES.get(service);
            return name == null ? "Open " + uri.getHost() + " on the device" : "Open in the " + name + " app";
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

    record Unroutable(String reason) implements Route {
        @Override
        public String describe() {
            return reason;
        }
    }
}
