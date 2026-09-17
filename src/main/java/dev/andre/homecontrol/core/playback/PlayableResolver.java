package dev.andre.homecontrol.core.playback;

import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;

import java.util.List;
import java.util.Set;

/**
 * Turns a source's abstract reference (e.g. {@link PlayableRef.JellyfinItem}) into concrete ones at
 * play time. Resolvers may do I/O and may build references that carry credentials; those never
 * leave the server. The planner itself stays pure.
 */
public interface PlayableResolver {

    boolean resolves(PlayableRef ref);

    Resolution resolve(PlayableRef ref, ContentItem item, Device device, Set<Capability> capabilities);

    /**
     * {@code liveCapabilities} are true for this request only (an app is open right now);
     * {@code notes} explain missing routes and are shown only when nothing routes.
     */
    record Resolution(List<PlayableRef> playables, Set<Capability> liveCapabilities, List<String> notes) {
        public Resolution {
            playables = List.copyOf(playables);
            liveCapabilities = Set.copyOf(liveCapabilities);
            notes = List.copyOf(notes);
        }

        public static Resolution note(String note) {
            return new Resolution(List.of(), Set.of(), List.of(note));
        }
    }
}
