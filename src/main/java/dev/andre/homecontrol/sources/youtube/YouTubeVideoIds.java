package dev.andre.homecontrol.sources.youtube;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The video id inside a YouTube URL; the same rules the TV adapters use to launch the app by id. */
public final class YouTubeVideoIds {

    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final Pattern VIDEO_PATH = Pattern.compile("^/(?:shorts|live|embed)/([A-Za-z0-9_-]{11})(?:/.*)?$");

    private YouTubeVideoIds() {
    }

    /** {@code youtu.be/<id>}; on youtube.com or a subdomain {@code /watch?v=<id>} or {@code /shorts|live|embed/<id>}. */
    public static Optional<String> fromUrl(URI uri) {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getPath() == null ? "" : uri.getPath();
        if (host.equals("youtu.be")) {
            return valid(path.startsWith("/") ? path.substring(1) : path);
        }
        if (!host.equals("youtube.com") && !host.endsWith(".youtube.com")) {
            return Optional.empty();
        }
        if (path.equals("/watch")) {
            return queryParameter(uri, "v").flatMap(YouTubeVideoIds::valid);
        }
        Matcher matcher = VIDEO_PATH.matcher(path);
        return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private static Optional<String> valid(String candidate) {
        return VIDEO_ID.matcher(candidate).matches() ? Optional.of(candidate) : Optional.empty();
    }

    private static Optional<String> queryParameter(URI uri, String name) {
        String query = uri.getRawQuery();
        if (query == null) {
            return Optional.empty();
        }
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            String key = URLDecoder.decode(equals < 0 ? pair : pair.substring(0, equals), StandardCharsets.UTF_8);
            if (key.equals(name)) {
                return Optional.of(equals < 0 ? "" : URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
            }
        }
        return Optional.empty();
    }
}
