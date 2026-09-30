package dev.andre.homecontrol.sources.sports.thesportsdb;

import dev.andre.homecontrol.core.content.ContentSourceException;

/** A TheSportsDB call failed; the message is user-facing and never contains the URL or the key. */
public class TheSportsDbException extends ContentSourceException {

    public TheSportsDbException(Kind kind, String message) {
        super(kind, message);
    }

}
