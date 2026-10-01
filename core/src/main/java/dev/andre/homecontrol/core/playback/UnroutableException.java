package dev.andre.homecontrol.core.playback;

/** Nothing on the item can reach the device. HTTP 422; the message is user-facing. */
public class UnroutableException extends RuntimeException {
    public UnroutableException(String message) {
        super(message);
    }
}
