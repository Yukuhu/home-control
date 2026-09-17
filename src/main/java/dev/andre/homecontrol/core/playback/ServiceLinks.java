package dev.andre.homecontrol.core.playback;

import java.net.URI;
import java.util.Map;
import java.util.Optional;

/** Service URL grammar shared by sources, the planner's descriptions and adapters. Pure; no I/O. */
public final class ServiceLinks {

    public static final String YOUTUBE = "youtube";
    public static final String NETFLIX = "netflix";
    public static final String PRIME_VIDEO = "primevideo";
    public static final String DAZN = "dazn";
    public static final String WEB = "web";

    private static final Map<String, String> NAMES = Map.of(
            YOUTUBE, "YouTube", NETFLIX, "Netflix", PRIME_VIDEO, "Prime Video", DAZN, "DAZN", "jellyfin", "Jellyfin");

    private ServiceLinks() {
    }

    public static Optional<String> displayName(String service) {
        return Optional.ofNullable(service == null ? null : NAMES.get(service));
    }

    /** The service's name, or the link's host for a plain web link. */
    public static String label(String service, URI uri) {
        return displayName(service).orElse(uri.getHost());
    }
}
