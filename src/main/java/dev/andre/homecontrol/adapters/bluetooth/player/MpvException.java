package dev.andre.homecontrol.adapters.bluetooth.player;

/** mpv answered, but refused. */
public class MpvException extends Exception {

    private final String error;

    private MpvException(String message, String error) {
        super(message);
        this.error = error;
    }

    public static MpvException refused(String command, String error) {
        return new MpvException("mpv refused " + command + ": " + error, error);
    }

    public static MpvException loadFailed(String reason) {
        return new MpvException("the stream could not be loaded (" + StreamRedaction.redact(reason) + ")", reason);
    }

    public String error() {
        return error;
    }
}
