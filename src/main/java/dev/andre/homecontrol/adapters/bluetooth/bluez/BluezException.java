package dev.andre.homecontrol.adapters.bluetooth.bluez;

/** A BlueZ/D-Bus operation failed. {@link #failure()} classifies why, for the setup page and retry logic. */
public class BluezException extends Exception {

    private final BluezFailure failure;

    public BluezException(BluezFailure failure, String message) {
        super(message);
        this.failure = failure;
    }

    public BluezException(BluezFailure failure, String message, Throwable cause) {
        super(message, cause);
        this.failure = failure;
    }

    public BluezFailure failure() {
        return failure;
    }
}
