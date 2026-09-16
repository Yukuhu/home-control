package dev.andre.homecontrol.security;

/** Too many password verifications are running at once. */
public class LoginBusyException extends RuntimeException {

    public LoginBusyException() {
        super("The server is busy checking other logins; try again in a moment");
    }
}
