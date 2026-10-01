package dev.andre.homecontrol.adapters.webos.protocol;

/** Registration did not produce a client key. Only re-pairing can fix {@code KEY_REJECTED}. */
public class SsapPairingException extends SsapException {

    public enum Reason { DECLINED, TIMED_OUT, KEY_REJECTED }

    private final Reason reason;

    SsapPairingException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
