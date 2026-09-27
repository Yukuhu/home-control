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
        return where(uri) + (uri.getRawQuery() == null ? "" : "?…");
    }

    /** Scheme, host, port and path; never the user info. Just the path without a scheme or host. */
    private static String where(URI uri) {
        if (uri.getScheme() == null || uri.getHost() == null) {
            return String.valueOf(uri.getRawPath());
        }
        String port = uri.getPort() >= 0 ? ":" + uri.getPort() : "";
        return uri.getScheme() + "://" + uri.getHost() + port + uri.getRawPath();
    }
}
