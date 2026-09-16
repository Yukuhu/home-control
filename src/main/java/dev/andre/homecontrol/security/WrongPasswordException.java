package dev.andre.homecontrol.security;

/** The password given as the current one is wrong — a guess, unlike other rejections. */
public class WrongPasswordException extends PasswordRejectedException {

    public WrongPasswordException(String message) {
        super(message);
    }
}
