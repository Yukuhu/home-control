package dev.andre.shield.protocol;

public enum DisconnectCause {

    /** The device hung up cleanly, or we closed the connection ourselves. */
    CLOSED,

    /** Nothing arrived within the stale timeout, so the connection is presumed dead. */
    STALE,

    /** The device refused our certificate: the pairing is gone and retrying is pointless. */
    UNPAIRED,

    ERROR
}
