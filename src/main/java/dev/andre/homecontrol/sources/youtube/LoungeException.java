package dev.andre.homecontrol.sources.youtube;

/** A failed Lounge step. The message names the step and never contains a lounge token or session id. */
public class LoungeException extends RuntimeException {

    public LoungeException(String step, String detail) {
        super(step + ": " + detail);
    }
}
