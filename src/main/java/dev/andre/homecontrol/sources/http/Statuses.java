package dev.andre.homecontrol.sources.http;

import dev.andre.homecontrol.core.content.ContentSourceException.Kind;

/** What an unsuccessful HTTP status means for a content source; a source overrides only its real exceptions. */
public final class Statuses {

    private Statuses() {
    }

    public static Kind kindOf(int status) {
        return switch (status) {
            case 401, 403 -> Kind.UNAUTHORIZED;
            case 404, 410 -> Kind.NOT_FOUND;
            case 429 -> Kind.RATE_LIMITED;
            default -> status >= 500 ? Kind.SERVER_ERROR : Kind.BAD_RESPONSE;
        };
    }
}
