package dev.andre.homecontrol.core;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** A command for one device. Each action names the capability an adapter must declare to accept it. */
public sealed interface Action {

    Capability requires();

    record PressKey(RemoteKey key, KeyPress press) implements Action {
        public PressKey {
            press = press == null ? KeyPress.SHORT : press;
        }

        public PressKey(RemoteKey key) {
            this(key, KeyPress.SHORT);
        }

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

    /** Switch a TV to an input its handle listed through {@link InputListing}. */
    record SelectInput(String inputId) implements Action {
        @Override
        public Capability requires() {
            return Capability.REMOTE_KEYS;
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

        /** The load map can carry a StreamUrl with an API key; never print it. */
        @Override
        public String toString() {
            return "CastLoad[receiverAppId=" + receiverAppId + "]";
        }
    }

    /**
     * Start receiver app {@code receiverAppId} if needed and send {@code message} on its custom
     * {@code namespace} (for receivers that do not take a media LOAD, such as Jellyfin's).
     */
    record CastMessage(String receiverAppId, String namespace, Map<String, Object> message) implements Action {
        public CastMessage {
            Objects.requireNonNull(receiverAppId, "receiverAppId");
            if (namespace == null || !namespace.startsWith("urn:x-cast:")) {
                throw new IllegalArgumentException("A Cast namespace starts with urn:x-cast:");
            }
            message = message == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(message));
        }

        @Override
        public Capability requires() {
            return Capability.CAST_RECEIVER;
        }

        /** The body can carry credentials; never print it. */
        @Override
        public String toString() {
            return "CastMessage[receiverAppId=" + receiverAppId + ", namespace=" + namespace + "]";
        }
    }
}
