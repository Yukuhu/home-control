package dev.andre.homecontrol.content;

/** Where a cached rail stands relative to its content source. */
public enum RailStatus {
    /** Never loaded yet, or reset after its source disappeared and came back. */
    LOADING,
    /** Loaded successfully; {@code items} reflect the last fetch. */
    READY,
    /** The last fetch failed; {@code items} (if any) are the last good ones. */
    FAILED
}
