package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.core.content.ContentSourceException;

/** A Jellyfin call failed; the message is user-facing and never contains a token. */
public class JellyfinException extends ContentSourceException {

    public enum Kind { INVALID_INPUT, UNREACHABLE, NOT_JELLYFIN, UNSUPPORTED_VERSION, UNAUTHORIZED, USER_NOT_FOUND, NOT_FOUND, SERVER_ERROR, BAD_RESPONSE }

    private final Kind kind;

    public JellyfinException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
