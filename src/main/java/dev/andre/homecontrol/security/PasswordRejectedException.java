package dev.andre.homecontrol.security;

/** A new or current password was not accepted; the message is shown to the user. */
public class PasswordRejectedException extends RuntimeException {

    public PasswordRejectedException(String message) {
        super(message);
    }
}
