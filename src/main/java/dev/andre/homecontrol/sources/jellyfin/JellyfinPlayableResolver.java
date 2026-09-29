package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.PlayableRef;
import dev.andre.homecontrol.core.playback.PlayableResolver;
import java.util.function.Predicate;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Jellyfin item → native Android TV app, open session, or receiver message/direct stream. */
public class JellyfinPlayableResolver implements PlayableResolver {

    private final JellyfinSetupService setup;
    private final JellyfinSessions sessions;
    private final JellyfinClient client;
    private final JellyfinStreams streams;
    private final Predicate<String> adapterEnabled;

    /** {@code adapterEnabled}: whether an adapter's module is switched on ({@code DeviceManager.adapterEnabled}). */
    public JellyfinPlayableResolver(JellyfinSetupService setup, JellyfinSessions sessions, JellyfinClient client,
                                    JellyfinStreams streams, Predicate<String> adapterEnabled) {
        this.setup = setup;
        this.sessions = sessions;
        this.client = client;
        this.streams = streams;
        this.adapterEnabled = adapterEnabled;
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
        boolean nativeApp = device.hasAdapter("androidtv") && adapterEnabled.test("androidtv")
                && capabilities.contains(Capability.APP_LINK) && capabilities.contains(Capability.REMOTE_KEYS);
        List<PlayableRef> playables = new ArrayList<>();
        Set<Capability> liveCapabilities = nativeApp ? Set.of(Capability.JELLYFIN_CLIENT) : Set.of();
        List<String> notes = new ArrayList<>();
        if (nativeApp) {
            playables.add(nativeStart(wanted, settings.get(), device));
        } else {
            Optional<Resolution> session = openSession(wanted, device, notes);
            if (session.isPresent()) {
                return session.get();
            }
        }
        addReceiverPlayable(wanted, settings.get(), connection.get(), device, capabilities, playables, notes);
        return new Resolution(playables, liveCapabilities, notes);
    }

    /** Preview remains read-only; native startup wins but Cast remains an explicit retry. */
    private static PlayableRef nativeStart(PlayableRef.JellyfinItem wanted, JellyfinSettings settings, Device device) {
        if (settings.player(device.id()) == JellyfinSettings.Player.VLC) {
            return new PlayableRef.JellyfinVlc(wanted.itemId());
        }
        return new PlayableRef.JellyfinApp(wanted.itemId(), wanted.resumeTicks());
    }

    /** The whole resolution when a controllable Jellyfin app is open on the device; otherwise a note why not. */
    private Optional<Resolution> openSession(PlayableRef.JellyfinItem wanted, Device device, List<String> notes) {
        try {
            Optional<JellyfinSession> open = sessions.sessionFor(device);
            if (open.isEmpty()) {
                notes.add("no Jellyfin app is open on " + device.name());
            }
            return open.map(session -> new Resolution(List.of(new PlayableRef.JellyfinSession(session.id(),
                    wanted.itemId(), wanted.resumeTicks(), session.client())), Set.of(Capability.JELLYFIN_CLIENT), List.of()));
        } catch (JellyfinException e) {
            notes.add("could not ask Jellyfin which apps are open (" + e.getMessage() + ")");
            return Optional.empty();
        }
    }

    /** The receiver message on a Cast device, or else a direct stream on a media renderer or local audio sink. */
    private void addReceiverPlayable(PlayableRef.JellyfinItem wanted, JellyfinSettings settings, JellyfinConnection connection,
                                     Device device, Set<Capability> capabilities, List<PlayableRef> playables,
                                     List<String> notes) {
        boolean cast = capabilities.contains(Capability.CAST_RECEIVER);
        boolean renderer = capabilities.contains(Capability.MEDIA_RENDERER);
        boolean local = capabilities.contains(Capability.LOCAL_AUDIO_SINK);
        if (!cast && !renderer && !local) {
            return;
        }
        JsonNode fetched;
        try {
            fetched = client.get(connection, "/Items/" + JellyfinClient.id(wanted.itemId()),
                    Map.of("userId", connection.userId()));
        } catch (JellyfinException | IllegalArgumentException e) {
            notes.add("could not load the item from Jellyfin (" + e.getMessage() + ")");
            return;
        }
        if (cast) {
            playables.add(JellyfinCastMessages.playable(settings, connection.token(), fetched,
                    wanted.resumeTicks(), device.name()));
            return;
        }
        // The direct stream is unreachable on a Cast device (the receiver message always wins in the
        // planner), so it is only worth asking Jellyfin for one on a plain media renderer or a local
        // audio sink (spec §5.3, groundwork for Wi-Fi speakers and Bluetooth speakers, sub-projects I/J).
        // The server's own player fetches the stream for a local sink; TVs and speakers need the device-facing address.
        URI streamBase = renderer ? settings.deviceServerUrl() : settings.serverUrl();
        try {
            streams.directStream(connection, streamBase, fetched).ifPresentOrElse(playables::add,
                    () -> notes.add("Jellyfin reports no format this device can play directly"));
        } catch (JellyfinException e) {
            notes.add("could not ask Jellyfin how to stream the item (" + e.getMessage() + ")");
        }
    }
}
