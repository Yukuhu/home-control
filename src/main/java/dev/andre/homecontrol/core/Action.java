package dev.andre.homecontrol.core;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** A command for one device. Each action names the capabilities that can carry it and what it asks of a device. */
public sealed interface Action {

    /** The capabilities an adapter may declare to carry this action; it needs one of them. */
    Set<Capability> requires();

    /** What this action asks of a device, for "<device> cannot <purpose>", e.g. "switch inputs". */
    String purpose();

    /** Whether an adapter declaring {@code capabilities} may be asked to perform this action. */
    default boolean acceptedBy(Set<Capability> capabilities) {
        return !Collections.disjoint(requires(), capabilities);
    }

    record PressKey(RemoteKey key, KeyPress press) implements Action {
        public PressKey {
            press = press == null ? KeyPress.SHORT : press;
        }

        public PressKey(RemoteKey key) {
            this(key, KeyPress.SHORT);
        }

        @Override
        public Set<Capability> requires() {
            return Set.of(Capability.REMOTE_KEYS);
        }

        @Override
        public String purpose() {
            return "take remote keys";
        }
    }

    /**
     * Ask the device to open a URI in whatever app claims it. Optimistic by design (spec §5.3).
     * {@code media} is what the link starts playing, when the sender knows; otherwise null.
     */
    record OpenAppLink(URI uri, LaunchedMedia media) implements Action {
        public OpenAppLink(URI uri) {
            this(uri, null);
        }

        /** App links can carry authenticated media URLs, including nested VLC links. */
        @Override
        public String toString() {
            return "OpenAppLink[uri=" + RedactedUris.withoutQuery(uri) + "]";
        }

        @Override
        public Set<Capability> requires() {
            return Set.of(Capability.APP_LINK);
        }

        @Override
        public String purpose() {
            return "open app links";
        }
    }

    /** Switch a TV to an input its handle listed through {@link InputListing}. */
    record SelectInput(String inputId) implements Action {
        @Override
        public Set<Capability> requires() {
            return Set.of(Capability.INPUTS);
        }

        @Override
        public String purpose() {
            return "switch inputs";
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
        public Set<Capability> requires() {
            return Set.of(Capability.VOLUME);
        }

        @Override
        public String purpose() {
            return "change the volume";
        }
    }

    record Mute(boolean muted) implements Action {
        @Override
        public Set<Capability> requires() {
            return Set.of(Capability.VOLUME);
        }

        @Override
        public String purpose() {
            return "mute";
        }
    }

    /** Stop whatever is being cast or played. */
    record Stop() implements Action {
        @Override
        public Set<Capability> requires() {
            return Set.of(Capability.CAST_RECEIVER, Capability.MEDIA_RENDERER, Capability.LOCAL_AUDIO_SINK);
        }

        @Override
        public String purpose() {
            return "stop playback";
        }
    }

    /** Play a direct stream on a media renderer (spec §5.3 rung 4). The URL may carry a credential. */
    record PlayMedia(URI url, String mimeType, String title, String subtitle) implements Action {
        public PlayMedia {
            if (url == null) {
                throw new IllegalArgumentException("A stream URL is required");
            }
            mimeType = mimeType == null || mimeType.isBlank() ? "application/octet-stream" : mimeType.strip();
        }

        @Override
        public Set<Capability> requires() {
            return Set.of(Capability.MEDIA_RENDERER, Capability.LOCAL_AUDIO_SINK);
        }

        @Override
        public String purpose() {
            return "play a stream";
        }

        /** The query can hold a Jellyfin ApiKey; never print it. */
        @Override
        public String toString() {
            return "PlayMedia[url=" + RedactedUris.withoutQuery(url) + ", mimeType=" + mimeType + ", title=" + title + "]";
        }
    }

    /** Pause what a media renderer, or the server's own player on a local audio sink, plays. */
    record Pause() implements Action {
        @Override
        public Set<Capability> requires() {
            return Set.of(Capability.MEDIA_RENDERER, Capability.LOCAL_AUDIO_SINK);
        }

        @Override
        public String purpose() {
            return "pause";
        }
    }

    /** Resume what a media renderer, or the server's own player on a local audio sink, paused. */
    record Resume() implements Action {
        @Override
        public Set<Capability> requires() {
            return Set.of(Capability.MEDIA_RENDERER, Capability.LOCAL_AUDIO_SINK);
        }

        @Override
        public String purpose() {
            return "resume";
        }
    }

    /** Join the speaker group that contains {@code memberId} (a {@link GroupMember#memberId()}). */
    record JoinGroup(String memberId) implements Action {
        public JoinGroup {
            if (memberId == null || memberId.isBlank()) {
                throw new IllegalArgumentException("Pick a speaker to join");
            }
        }

        @Override
        public Set<Capability> requires() {
            return Set.of(Capability.GROUPING);
        }

        @Override
        public String purpose() {
            return "be grouped";
        }
    }

    /** Leave the speaker group and play on its own. */
    record LeaveGroup() implements Action {
        @Override
        public Set<Capability> requires() {
            return Set.of(Capability.GROUPING);
        }

        @Override
        public String purpose() {
            return "be grouped";
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
        public Set<Capability> requires() {
            return Set.of(Capability.CAST_RECEIVER);
        }

        @Override
        public String purpose() {
            return "receive Cast media";
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
        public Set<Capability> requires() {
            return Set.of(Capability.CAST_RECEIVER);
        }

        @Override
        public String purpose() {
            return "receive Cast media";
        }

        /** The body can carry credentials; never print it. */
        @Override
        public String toString() {
            return "CastMessage[receiverAppId=" + receiverAppId + ", namespace=" + namespace + "]";
        }
    }
}
