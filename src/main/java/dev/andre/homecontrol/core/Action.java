package dev.andre.homecontrol.core;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** A command for one device. Each action names the capability an adapter must declare to accept it. */
public sealed interface Action {

    Capability requires();

    record PressKey(RemoteKey key) implements Action {
        @Override
        public Capability requires() {
            return Capability.REMOTE_KEYS;
        }
    }

    /** Ask the device to open a URI in whatever app claims it. Optimistic by design (spec §5.3). */
    record OpenAppLink(URI uri) implements Action {
        @Override
        public Capability requires() {
            return Capability.APP_LINK;
        }
    }

    /** Absolute volume as a percentage of the device's range. */
    record SetVolume(int level) implements Action {
        public SetVolume {
            if (level < 0 || level > 100) {
                throw new IllegalArgumentException("Volume must be between 0 and 100");
            }
        }

        @Override
        public Capability requires() {
            return Capability.VOLUME;
        }
    }

    record Mute(boolean muted) implements Action {
        @Override
        public Capability requires() {
            return Capability.VOLUME;
        }
    }

    /** Stop whatever is being cast. */
    record Stop() implements Action {
        @Override
        public Capability requires() {
            return Capability.CAST_RECEIVER;
        }
    }

    /**
     * Start receiver app {@code receiverAppId} if it is not running and send it a media LOAD
     * whose body is {@code load} (without type, requestId, sessionId). Spec §5.2 {@code CastLoad}.
     */
    record CastLoad(String receiverAppId, Map<String, Object> load) implements Action {
        public CastLoad {
            Objects.requireNonNull(receiverAppId, "receiverAppId");
            load = load == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(load));
        }

        @Override
        public Capability requires() {
            return Capability.CAST_RECEIVER;
        }
    }
}
