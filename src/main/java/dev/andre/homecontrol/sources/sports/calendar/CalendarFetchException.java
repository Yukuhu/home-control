package dev.andre.homecontrol.sources.sports.calendar;

import dev.andre.homecontrol.core.content.ContentSourceException;

/** A calendar fetch failed; the message is user-facing and never contains a path, query or credential. */
public class CalendarFetchException extends ContentSourceException {

    public CalendarFetchException(Kind kind, String message) {
        super(kind, message);
    }

    public CalendarFetchException(Kind kind, String message, Throwable cause) {
        super(kind, message, cause);
    }

}
