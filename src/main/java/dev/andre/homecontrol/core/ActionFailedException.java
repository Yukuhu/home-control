package dev.andre.homecontrol.core;

/** The device was reached but refused the action or never answered. HTTP 502; the message is user-facing. */
public class ActionFailedException extends RuntimeException {
    public ActionFailedException(String message) {
        super(message);
    }
}
