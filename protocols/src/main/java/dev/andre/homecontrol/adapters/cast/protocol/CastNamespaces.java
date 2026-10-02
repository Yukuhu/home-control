package dev.andre.homecontrol.adapters.cast.protocol;

/** CASTV2 namespaces and well-known endpoint ids. */
public final class CastNamespaces {

    public static final String CONNECTION = "urn:x-cast:com.google.cast.tp.connection";
    public static final String HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat";
    public static final String RECEIVER = "urn:x-cast:com.google.cast.receiver";
    public static final String MEDIA = "urn:x-cast:com.google.cast.media";

    /** Our sender id on every channel (pychromecast does the same). */
    public static final String SENDER_ID = "sender-0";
    /** The receiver platform itself: connection, heartbeat and receiver namespaces go here. */
    public static final String PLATFORM_RECEIVER_ID = "receiver-0";
    public static final String DEFAULT_MEDIA_RECEIVER_APP_ID = "CC1AD845";

    private CastNamespaces() {
    }
}
