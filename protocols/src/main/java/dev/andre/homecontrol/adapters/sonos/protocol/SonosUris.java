package dev.andre.homecontrol.adapters.sonos.protocol;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;

public final class SonosUris {

    private static final String GROUP_PREFIX = "x-rincon:";

    private SonosUris() {
    }

    public static String groupWith(String coordinatorUuid) {
        return GROUP_PREFIX + coordinatorUuid;
    }

    /** The coordinator a member's TrackURI points at, if it is following a group. */
    public static Optional<String> groupedTo(String trackUri) {
        return trackUri != null && trackUri.startsWith(GROUP_PREFIX)
                ? Optional.of(trackUri.substring(GROUP_PREFIX.length())) : Optional.empty();
    }

    /** Endless MP3 streams (Icecast style: http, audio/mpeg, no file extension) need Sonos' radio scheme. */
    public static URI forPlayback(URI url, String mimeType) {
        if (!"http".equalsIgnoreCase(url.getScheme()) || mimeType == null
                || !mimeType.strip().toLowerCase(Locale.ROOT).startsWith("audio/mpeg")) {
            return url;
        }
        String path = url.getRawPath() == null ? "" : url.getRawPath();
        String lastSegment = path.substring(path.lastIndexOf('/') + 1);
        if (lastSegment.contains(".")) {
            return url;
        }
        return URI.create("x-rincon-mp3radio://" + url.getRawAuthority() + (path.isEmpty() ? "/" : path)
                + (url.getRawQuery() == null ? "" : "?" + url.getRawQuery()));
    }
}
