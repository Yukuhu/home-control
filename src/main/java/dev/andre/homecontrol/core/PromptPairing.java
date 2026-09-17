package dev.andre.homecontrol.core;

/**
 * Pairing by accepting a prompt on the device itself (webOS, Tizen). The web layer lists every
 * bean of this type without knowing any protocol. {@link #pair} blocks until the device answers
 * or the adapter's pairing timeout elapses, and registers the device through the device manager.
 */
public interface PromptPairing {

    /** The adapter id discovered devices carry, e.g. {@code webos}. */
    String adapterId();

    /** For the setup page, e.g. "LG webOS TV". */
    String displayName();

    /** What the user must do, e.g. "Accept the request on the TV within 60 seconds." */
    String instructions();

    PromptPairingResult pair(String host, String name);
}
