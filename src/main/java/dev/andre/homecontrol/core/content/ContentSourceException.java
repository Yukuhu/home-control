package dev.andre.homecontrol.core.content;

/** A content source could not answer. The message is user-facing and names the source; it never contains a credential. */
public class ContentSourceException extends RuntimeException {

    public ContentSourceException(String message) {
        super(message);
    }

    public ContentSourceException(String message, Throwable cause) {
        super(message, cause);
    }
}
