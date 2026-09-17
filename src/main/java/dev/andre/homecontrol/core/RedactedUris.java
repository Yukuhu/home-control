package dev.andre.homecontrol.core;

import java.net.URI;

/** Stream URLs can carry credentials in their query (Jellyfin ApiKey); logs and messages get this form. */
public final class RedactedUris {

    private RedactedUris() {
    }

    public static String withoutQuery(URI uri) {
        if (uri == null) {
            return "null";
        }
        String where = uri.getScheme() == null || uri.getHost() == null
                ? String.valueOf(uri.getRawPath())
                : uri.getScheme() + "://" + uri.getHost() + (uri.getPort() >= 0 ? ":" + uri.getPort() : "") + uri.getRawPath();
        return where + (uri.getRawQuery() == null ? "" : "?…");
    }
}
