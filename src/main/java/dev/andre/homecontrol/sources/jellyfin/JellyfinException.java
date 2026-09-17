package dev.andre.homecontrol.sources.jellyfin;

/** A Jellyfin call failed; the message is user-facing and never contains a token. */
public class JellyfinException extends RuntimeException {

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
