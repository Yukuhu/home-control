package dev.andre.homecontrol.core.playback;

import dev.andre.homecontrol.core.Action;

import java.net.URI;
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

    record Unroutable(String reason) implements Route {
        @Override
        public String describe() {
            return reason;
        }
    }
}
