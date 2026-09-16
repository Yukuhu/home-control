package dev.andre.homecontrol.security;

/** The request needs an authenticated session. */
public class LoginRequiredException extends RuntimeException {

    public LoginRequiredException() {
        super("Log in first");
    }
}
