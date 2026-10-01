package dev.andre.homecontrol.core;

import java.io.IOException;

/**
 * Pairing by typing a code the device shows (Android TV). The web layer uses it without knowing any protocol; there
 * is no bean when the module is switched off.
 */
public interface CodePairing {

    boolean inProgress();

    /** Starts a pairing attempt; the device shows a code. A new attempt replaces an unfinished one. */
    void begin(String host, String name) throws IOException;

    CodePairingOutcome submit(String code);
}
