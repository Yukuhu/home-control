package dev.andre.homecontrol.core.playback;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Turns a pasted URL into an ad-hoc {@link ContentItem} with one {@link PlayableRef.AppLink}. */
public final class AppLinks {

    /** amazon.com, amazon.de, amazon.co.uk, amazon.com.au — not amazon.evil.example. */
    private static final Pattern AMAZON_HOST =
            Pattern.compile("^(www\\.)?amazon\\.(com|[a-z]{2}|co\\.[a-z]{2}|com\\.[a-z]{2})$");

    /** Far beyond any real share link; keeps a pasted blob from reaching the parser and the device. */
    static final int MAX_LENGTH = 2048;

    /** Extensions whose URL a Cast Default Media Receiver can fetch and play directly. */
    private static final Map<String, String> MEDIA_TYPES = Map.ofEntries(
            Map.entry("mp4", "video/mp4"), Map.entry("m4v", "video/mp4"), Map.entry("webm", "video/webm"),
            Map.entry("mkv", "video/x-matroska"), Map.entry("m3u8", "application/x-mpegURL"),
            Map.entry("mpd", "application/dash+xml"), Map.entry("mp3", "audio/mpeg"), Map.entry("m4a", "audio/mp4"),
            Map.entry("aac", "audio/aac"), Map.entry("flac", "audio/flac"), Map.entry("ogg", "audio/ogg"),
            Map.entry("wav", "audio/wav"));

    private AppLinks() {
    }

    public static ContentItem fromUrl(String url) {
        URI uri = parse(url);
        String service = serviceOf(uri.getHost().toLowerCase(Locale.ROOT), uri.getPath());
        List<PlayableRef> playables = new ArrayList<>();
        playables.add(new PlayableRef.AppLink(uri, service));
        Optional<String> mediaType = mediaTypeOf(uri.getRawPath());
        mediaType.ifPresent(type -> playables.add(new PlayableRef.StreamUrl(uri, type)));
        String title = mediaType.isPresent() ? fileName(uri.getRawPath()) : uri.getHost();
        ContentKind kind = mediaType.filter(type -> type.startsWith("audio/")).isPresent() ? ContentKind.TRACK : ContentKind.VIDEO;
        return new ContentItem("link:" + uri, "manual", kind, title, null, null, playables);
    }

    static Optional<String> mediaTypeOf(String rawPath) {
        if (rawPath == null) {
            return Optional.empty();
        }
        String name = fileName(rawPath);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? Optional.empty()
                : Optional.ofNullable(MEDIA_TYPES.get(name.substring(dot + 1).toLowerCase(Locale.ROOT)));
    }

    private static String fileName(String rawPath) {
        return URLDecoder.decode(rawPath.substring(rawPath.lastIndexOf('/') + 1), StandardCharsets.UTF_8);
    }

    private static URI parse(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Enter a link to open");
        }
        if (url.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("That link is too long");
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("That is not a valid link", e);
        }
        String scheme = uri.getScheme();
        if (uri.getHost() == null || scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("Only http and https links can be opened on a device");
        }
        return uri;
    }

    /** Host-based detection; the service key drives the route description and later per-platform link builders. */
    static String serviceOf(String host, String path) {
        if (isOrUnder(host, "youtube.com") || host.equals("youtu.be")) {
            return "youtube";
        }
        if (isOrUnder(host, "netflix.com")) {
            return "netflix";
        }
        if (isOrUnder(host, "primevideo.com")
                || (AMAZON_HOST.matcher(host).matches() && path != null && path.contains("/video/"))) {
            return "primevideo";
        }
        if (isOrUnder(host, "dazn.com")) {
            return "dazn";
        }
        return "web";
    }

    /** The domain itself or one of its subdomains, never a look-alike such as {@code notyoutube.com}. */
    private static boolean isOrUnder(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }
}
