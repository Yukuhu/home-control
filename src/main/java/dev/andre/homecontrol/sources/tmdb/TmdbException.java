package dev.andre.homecontrol.sources.tmdb;

import dev.andre.homecontrol.core.content.ContentSourceException;

/** A TMDB call failed; the message is user-facing and never contains a credential. */
public class TmdbException extends ContentSourceException {

    public TmdbException(Kind kind, String message) {
        super(kind, message);
    }

}
