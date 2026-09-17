package dev.andre.homecontrol.sources.tmdb;

import dev.andre.homecontrol.core.content.ContentSourceException;

/** A TMDB call failed; the message is user-facing and never contains a credential. */
public class TmdbException extends ContentSourceException {

    public enum Kind { INVALID_INPUT, UNREACHABLE, UNAUTHORIZED, NOT_FOUND, RATE_LIMITED, SERVER_ERROR, BAD_RESPONSE }

    private final Kind kind;

    public TmdbException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public TmdbException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
