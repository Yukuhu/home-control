package dev.andre.homecontrol.adapters.links;

import dev.andre.homecontrol.core.playback.ServiceLinks;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Content ids inside service URLs, for adapters that launch apps by id instead of by URL. */
public final class ContentLinks {

    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final Pattern VIDEO_PATH = Pattern.compile("^/(?:shorts|live|embed)/([A-Za-z0-9_-]{11})(?:/.*)?$");

    private ContentLinks() {
    }

    public static Optional<String> youtubeVideoId(URI uri) {
        String host = host(uri);
        String path = uri.getPath() == null ? "" : uri.getPath();
        if (host.equals("youtu.be")) {
            return valid(path.startsWith("/") ? path.substring(1) : path);
        }
        if (!host.equals("youtube.com") && !host.endsWith(".youtube.com")) {
            return Optional.empty();
        }
        Optional<String> fromQuery = queryParameter(uri, "v").flatMap(ContentLinks::valid);
        if (fromQuery.isPresent()) {
            return fromQuery;
        }
        Matcher matcher = VIDEO_PATH.matcher(path);
        return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    public static Optional<String> netflixTitleId(URI uri) {
        return ServiceLinks.netflixTitleId(uri);
    }

    private static String host(URI uri) {
        return uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
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
