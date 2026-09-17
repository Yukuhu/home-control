package dev.andre.homecontrol.sources.sports.calendar;

import dev.andre.homecontrol.core.content.ContentSourceException;

/** A calendar fetch failed; the message is user-facing and never contains a path, query or credential. */
public class CalendarFetchException extends ContentSourceException {

    public enum Kind { BLOCKED, UNREACHABLE, UNAUTHORIZED, NOT_FOUND, BAD_RESPONSE, TOO_LARGE, NOT_A_CALENDAR }

    private final Kind kind;

    public CalendarFetchException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public CalendarFetchException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
