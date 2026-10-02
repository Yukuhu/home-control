package dev.andre.homecontrol.sources.http;

import dev.andre.homecontrol.core.content.ContentSourceException.Kind;

import java.util.Locale;

/**
 * Why an exchange failed: its kind, the host and a short reason, never the path, query, headers or a cause. A source
 * turns it into its own exception, usually with {@link #describe(String)}.
 *
 * @param limit the body cap that was exceeded, for {@link Kind#TOO_LARGE}; otherwise 0
 */
public record OutboundFailure(Kind kind, String host, String reason, int limit) {

    public static final String TIMED_OUT = "request timed out";
    public static final String INTERRUPTED = "request interrupted";
    public static final String CLOSED = "client is closed";
    public static final String FAILED = "request failed";
    public static final String UNKNOWN_HOST = "unknown host";
    public static final String REFUSED = "connection refused";
    public static final String ADDRESS_NOT_ALLOWED = "address not allowed";
    public static final String OTHER_ORIGIN = "redirect changes origin; configure the final source URL";
    public static final String TOO_LARGE = "response is too large";
    public static final String COMPRESSED = "response compression is not supported";
    public static final String TOO_MANY_REDIRECTS = "too many redirects";
    public static final String NO_REDIRECT_TARGET = "redirect has no destination";
    public static final String INVALID_REDIRECT = "redirect to a link Home Control does not follow";
    public static final String BUSY = "busy; try again later";

    /** The sentence a person reads, with {@code name} as the source is called in running text, e.g. "the calendar". */
    public String describe(String name) {
        return switch (kind) {
            case BLOCKED -> "Home Control does not connect to " + host + " (" + reason + ")";
            case TOO_LARGE -> capitalized(name) + " at " + host + " sent more than " + size(limit);
            case BAD_RESPONSE -> INVALID_REDIRECT.equals(reason)
                    ? capitalized(name) + " at " + host + " redirects to a link Home Control does not follow"
                    : capitalized(name) + " at " + host + " sent a response Home Control cannot read (" + reason + ")";
            case RATE_LIMITED -> "Home Control is busy talking to " + name + "; try again in a moment";
            default -> "Could not reach " + name + " at " + host + " (" + reason + ")";
        };
    }

    private static String capitalized(String name) {
        return name.isEmpty() ? name : name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
    }

    private static String size(int bytes) {
        if (bytes >= 1_048_576 && bytes % 1_048_576 == 0) {
            return bytes / 1_048_576 + " MB";
        }
        if (bytes >= 1_024 && bytes % 1_024 == 0) {
            return bytes / 1_024 + " KB";
        }
        return bytes + " bytes";
    }
}
