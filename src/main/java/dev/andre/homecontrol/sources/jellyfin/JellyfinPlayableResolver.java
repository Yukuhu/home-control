package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.PlayableRef;
import dev.andre.homecontrol.core.playback.PlayableResolver;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Jellyfin item → open app session, else Jellyfin receiver message and direct stream (spec §4.2, §5.3). */
public class JellyfinPlayableResolver implements PlayableResolver {

    private final JellyfinSetupService setup;
    private final JellyfinSessions sessions;
    private final JellyfinClient client;
    private final JellyfinStreams streams;

    public JellyfinPlayableResolver(JellyfinSetupService setup, JellyfinSessions sessions, JellyfinClient client, JellyfinStreams streams) {
        this.setup = setup;
        this.sessions = sessions;
        this.client = client;
        this.streams = streams;
    }

    @Override
    public boolean resolves(PlayableRef ref) {
        return ref instanceof PlayableRef.JellyfinItem;
    }

    @Override
    public Resolution resolve(PlayableRef ref, ContentItem item, Device device, Set<Capability> capabilities) {
        PlayableRef.JellyfinItem wanted = (PlayableRef.JellyfinItem) ref;
        Optional<JellyfinSettings> settings = setup.settings();
        Optional<JellyfinConnection> connection = setup.connection();
        if (settings.isEmpty() || connection.isEmpty()) {
            return Resolution.note("Jellyfin is not connected");
        }
        if (wanted.serverId() != null && !wanted.serverId().isBlank()
                && !wanted.serverId().equalsIgnoreCase(settings.get().serverId())) {
            return Resolution.note("this item is from a different Jellyfin server");
        }
        List<String> notes = new ArrayList<>();
        try {
            Optional<JellyfinSession> open = sessions.sessionFor(device);
            if (open.isPresent()) {
                return new Resolution(List.of(new PlayableRef.JellyfinSession(open.get().id(), wanted.itemId(),
                        wanted.resumeTicks(), open.get().client())), Set.of(Capability.JELLYFIN_CLIENT), List.of());
            }
            notes.add("no Jellyfin app is open on " + device.name());
        } catch (JellyfinException e) {
            notes.add("could not ask Jellyfin which apps are open (" + e.getMessage() + ")");
        }
        boolean cast = capabilities.contains(Capability.CAST_RECEIVER);
        boolean renderer = capabilities.contains(Capability.MEDIA_RENDERER);
        if (!cast && !renderer) {
            return new Resolution(List.of(), Set.of(), notes);
        }
        JsonNode fetched;
        try {
            fetched = client.get(connection.get(), "/Items/" + JellyfinClient.id(wanted.itemId()),
                    Map.of("userId", connection.get().userId()));
        } catch (JellyfinException | IllegalArgumentException e) {
            notes.add("could not load the item from Jellyfin (" + e.getMessage() + ")");
            return new Resolution(List.of(), Set.of(), notes);
        }
        List<PlayableRef> playables = new ArrayList<>();
        if (cast) {
            playables.add(JellyfinCastMessages.playable(settings.get(), connection.get().token(), fetched,
                    wanted.resumeTicks(), device.name()));
        }
        // The direct stream is unreachable on a Cast device (the receiver message always wins in the
        // planner), so it is only worth asking Jellyfin for one on a plain media renderer (spec §5.3,
        // groundwork for Wi-Fi speakers, sub-project I).
        if (!cast && renderer) {
            try {
                streams.directStream(connection.get(), settings.get().deviceServerUrl(), fetched).ifPresentOrElse(playables::add,
                        () -> notes.add("Jellyfin reports no format this device can play directly"));
            } catch (JellyfinException e) {
                notes.add("could not ask Jellyfin how to stream the item (" + e.getMessage() + ")");
            }
        }
        return new Resolution(playables, Set.of(), notes);
    }
}
