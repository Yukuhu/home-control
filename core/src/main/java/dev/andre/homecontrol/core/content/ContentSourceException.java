package dev.andre.homecontrol.core.content;

/** A content source could not answer. The message is user-facing and names the source; it never contains a credential. */
public class ContentSourceException extends RuntimeException {

    /** What went wrong, in terms every source shares; sources branch on it, the web edge does not. */
    public enum Kind {
        INVALID_INPUT, NOT_CONFIGURED, UNREACHABLE, BLOCKED, UNAUTHORIZED, REVOKED, FORBIDDEN, NOT_FOUND,
        RATE_LIMITED, QUOTA_EXHAUSTED, SERVER_ERROR, BAD_RESPONSE, TOO_LARGE
    }

    private final Kind kind;

    public ContentSourceException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public ContentSourceException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
