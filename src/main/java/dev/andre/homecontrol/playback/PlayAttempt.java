package dev.andre.homecontrol.playback;

import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.playback.Route;

import java.util.List;

/** What happened when a route was tried, plus what else is left to try (the play sheet's next-route retry). */
public sealed interface PlayAttempt {

    Device device();

    record Played(Device device, Route route, List<Route> remaining) implements PlayAttempt {
        public Played {
            remaining = List.copyOf(remaining);
        }
    }

    record Failed(Device device, Route route, List<Route> remaining, RuntimeException cause) implements PlayAttempt {
        public Failed {
            remaining = List.copyOf(remaining);
        }
    }

    record Unroutable(Device device, String reason) implements PlayAttempt {
    }
}
