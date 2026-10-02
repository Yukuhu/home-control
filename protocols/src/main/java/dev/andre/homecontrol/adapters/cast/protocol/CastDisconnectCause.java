package dev.andre.homecontrol.adapters.cast.protocol;

public enum CastDisconnectCause {
    /** The receiver closed the channel (EOF or CLOSE from receiver-0). */
    CLOSED,
    /** Nothing arrived within the stale timeout, although we kept pinging. */
    STALE,
    /** Any other I/O failure. */
    ERROR
}
