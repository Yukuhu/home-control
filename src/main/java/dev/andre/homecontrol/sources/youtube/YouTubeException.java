package dev.andre.homecontrol.sources.youtube;

import dev.andre.homecontrol.core.content.ContentSourceException;

/** A YouTube or Google failure with a user-facing message. Never carries a token, form body or query string. */
public class YouTubeException extends ContentSourceException {

    private final String reason;

    public YouTubeException(Kind kind, String message) {
        this(kind, message, null);
    }

    /** {@code reason} is Google's machine-readable reason, e.g. {@code quotaExceeded}; may be null. */
    public YouTubeException(Kind kind, String message, String reason) {
        super(kind, message);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
