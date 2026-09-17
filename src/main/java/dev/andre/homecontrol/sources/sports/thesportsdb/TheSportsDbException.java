package dev.andre.homecontrol.sources.sports.thesportsdb;

import dev.andre.homecontrol.core.content.ContentSourceException;

/** A TheSportsDB call failed; the message is user-facing and never contains the URL or the key. */
public class TheSportsDbException extends ContentSourceException {

    public enum Kind { UNREACHABLE, UNAUTHORIZED, NOT_FOUND, RATE_LIMITED, SERVER_ERROR, BAD_RESPONSE }

    private final Kind kind;

    public TheSportsDbException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public TheSportsDbException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
