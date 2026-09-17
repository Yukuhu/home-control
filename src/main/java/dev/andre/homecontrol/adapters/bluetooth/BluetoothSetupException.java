package dev.andre.homecontrol.adapters.bluetooth;

/** A pairing or setup operation could not be completed; the message is shown to the user as-is. */
public class BluetoothSetupException extends Exception {
    public BluetoothSetupException(String message) {
        super(message);
    }
}
