package dev.andre.homecontrol.adapters.androidtv.protocol;

public enum DisconnectCause {

    /** The device hung up cleanly, or we closed the connection ourselves. */
    CLOSED,

    /** Nothing arrived within the stale timeout, so the connection is presumed dead. */
    STALE,

    /** TLS authentication failed; the session confirms repeated ambiguous failures before latching. */
    UNPAIRED,

    ERROR
}
