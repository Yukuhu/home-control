package dev.andre.homecontrol.core.playback;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Turns a pasted URL into an ad-hoc {@link ContentItem} with one {@link PlayableRef.AppLink}. */
public final class AppLinks {

    /** amazon.com, amazon.de, amazon.co.uk, amazon.com.au — not amazon.evil.example. */
    private static final Pattern AMAZON_HOST =
            Pattern.compile("^(www\\.)?amazon\\.(com|[a-z]{2}|co\\.[a-z]{2}|com\\.[a-z]{2})$");

    private AppLinks() {
    }

    public static ContentItem fromUrl(String url) {
        URI uri = parse(url);
        String service = serviceOf(uri.getHost().toLowerCase(Locale.ROOT), uri.getPath());
        return new ContentItem("link:" + uri, "manual", ContentKind.VIDEO, uri.getHost(), null, null,
                List.of(new PlayableRef.AppLink(uri, service)));
    }

    private static URI parse(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Enter a link to open");
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
