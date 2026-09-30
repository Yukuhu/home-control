package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.core.content.ContentSourceException;

/** A Jellyfin call failed; the message is user-facing and never contains a token. */
public class JellyfinException extends ContentSourceException {

    public JellyfinException(Kind kind, String message) {
        super(kind, message);
    }

}
