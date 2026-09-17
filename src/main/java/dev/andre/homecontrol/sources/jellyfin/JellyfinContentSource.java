package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.core.content.ContentSource;
import dev.andre.homecontrol.core.content.Rail;
import dev.andre.homecontrol.core.content.RailDescriptor;
import dev.andre.homecontrol.core.playback.ContentItem;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Continue watching, next up and latest-in-library rails, read straight from Jellyfin every time. */
public class JellyfinContentSource implements ContentSource {

    static final RailDescriptor RESUME = new RailDescriptor(JellyfinSettings.SOURCE_ID, "resume", "Continue watching");
    static final RailDescriptor NEXT_UP = new RailDescriptor(JellyfinSettings.SOURCE_ID, "next-up", "Next up");
    static final RailDescriptor LATEST = new RailDescriptor(JellyfinSettings.SOURCE_ID, "latest", "Latest in library");
    private static final String IMAGE_TYPES = "Primary,Thumb,Backdrop";

    private final JellyfinClient client;
    private final JellyfinSetupService setup;
    private final JellyfinProperties properties;
    private final Clock clock;

    public JellyfinContentSource(JellyfinClient client, JellyfinSetupService setup, JellyfinProperties properties, Clock clock) {
        this.client = client;
        this.setup = setup;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public String id() {
        return JellyfinSettings.SOURCE_ID;
    }

    @Override
    public String displayName() {
        return "Jellyfin";
    }

    @Override
    public boolean available() {
        return setup.connection().isPresent();
    }

    @Override
    public List<RailDescriptor> rails() {
        return available() ? List.of(RESUME, NEXT_UP, LATEST) : List.of();
    }

    @Override
    public Rail rail(String railId) {
        JellyfinConnection connection = connection();
        return switch (railId) {
            case "resume" -> new Rail(RESUME, JellyfinItemMapper.toItems(client.get(connection, "/UserItems/Resume",
                    listQuery(connection, "mediaTypes", "Video")).path("Items")), clock.instant());
            case "next-up" -> new Rail(NEXT_UP, JellyfinItemMapper.toItems(client.get(connection, "/Shows/NextUp",
                    listQuery(connection, "enableResumable", "false")).path("Items")), clock.instant());
            case "latest" -> new Rail(LATEST, JellyfinItemMapper.toItems(client.get(connection, "/Items/Latest",
                    listQuery(connection, "includeItemTypes", "Movie,Episode", "groupItems", "false"))), clock.instant());
            default -> throw new IllegalArgumentException("Jellyfin has no rail '" + railId + "'");
        };
    }

    @Override
    public Optional<ContentItem> item(String itemId) {
        JellyfinConnection connection = connection();
        String id;
        try {
            id = JellyfinClient.id(itemId);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        try {
            return JellyfinItemMapper.toItem(client.get(connection, "/Items/" + id, Map.of("userId", connection.userId())));
        } catch (JellyfinException e) {
            if (e.kind() == JellyfinException.Kind.NOT_FOUND) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public boolean searchable() {
        return true;
    }

    @Override
    public List<ContentItem> search(String query, int limit) {
        JellyfinConnection connection = connection();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("userId", connection.userId());
        params.put("searchTerm", query);
        params.put("recursive", "true");
        params.put("includeItemTypes", "Movie,Episode,Video,MusicVideo,Audio");
        params.put("limit", String.valueOf(limit));
        params.put("enableUserData", "true");
        params.put("enableImageTypes", IMAGE_TYPES);
        params.put("imageTypeLimit", "1");
        return JellyfinItemMapper.toItems(client.get(connection, "/Items", params).path("Items"));
    }

    JellyfinConnection connection() {
        return setup.connection().orElseThrow(() ->
                new JellyfinException(JellyfinException.Kind.INVALID_INPUT, "Jellyfin is not connected"));
    }

    Map<String, String> listQuery(JellyfinConnection connection, String... extra) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("userId", connection.userId());
        query.put("limit", String.valueOf(properties.railSize()));
        for (int i = 0; i + 1 < extra.length; i += 2) {
            query.put(extra[i], extra[i + 1]);
        }
        query.put("enableUserData", "true");
        query.put("enableImageTypes", IMAGE_TYPES);
        query.put("imageTypeLimit", "1");
        return query;
    }
}
