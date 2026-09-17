package dev.andre.homecontrol.adapters.webos;

/** Registration did not produce a client key. Only re-pairing can fix {@code KEY_REJECTED}. */
class SsapPairingException extends SsapException {

    enum Reason { DECLINED, TIMED_OUT, KEY_REJECTED }

    private final Reason reason;

    SsapPairingException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    Reason reason() {
        return reason;
    }
}
