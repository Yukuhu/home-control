package dev.andre.homecontrol.sources.youtube;

import dev.andre.homecontrol.core.content.ContentSourceException;

/** A YouTube or Google failure with a user-facing message. Never carries a token, form body or query string. */
public class YouTubeException extends ContentSourceException {

    public enum Kind {
        INVALID_INPUT, NOT_CONFIGURED, UNREACHABLE, UNAUTHORIZED, REVOKED, FORBIDDEN, NOT_FOUND,
        QUOTA_EXHAUSTED, SEARCH_LIMIT, SERVER_ERROR, BAD_RESPONSE
    }

    private final Kind kind;
    private final String reason;

    public YouTubeException(Kind kind, String message) {
        this(kind, message, null);
    }

    /** {@code reason} is Google's machine-readable reason, e.g. {@code quotaExceeded}; may be null. */
    public YouTubeException(Kind kind, String message, String reason) {
        super(message);
        this.kind = kind;
        this.reason = reason;
    }

    public Kind kind() {
        return kind;
    }

    public String reason() {
        return reason;
    }
}
