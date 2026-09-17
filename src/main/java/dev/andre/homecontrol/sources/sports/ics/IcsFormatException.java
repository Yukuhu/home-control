package dev.andre.homecontrol.sources.sports.ics;

/** Message is user-facing and never contains calendar content beyond counts. */
public class IcsFormatException extends RuntimeException {

    public IcsFormatException(String message) {
        super(message);
    }
}
