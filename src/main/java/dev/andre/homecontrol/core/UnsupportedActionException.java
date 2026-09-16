package dev.andre.homecontrol.core;

/** The device has no adapter declaring the capability the action requires. HTTP 422. */
public class UnsupportedActionException extends RuntimeException {
    public UnsupportedActionException(String message) {
        super(message);
    }
}
