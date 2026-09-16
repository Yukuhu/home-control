package dev.andre.homecontrol.adapters.androidtv;

/**
 * What {@link PairingService#submit} reports to callers outside this adapter. A public
 * type in {@code adapters.androidtv} (not {@code adapters.androidtv.protocol}), so the
 * web layer can see the pairing outcome without importing a device protocol package
 * (global constraint) — {@link PairingService} translates the protocol-level
 * {@code PairingResult} into this.
 */
public sealed interface PairingOutcome {

    /** The device accepted the secret; a credential now exists for it. */
    record Paired() implements PairingOutcome {
    }

    /** The code did not match. The device will show a new one, so restart the flow. */
    record WrongCode() implements PairingOutcome {
    }

    /** Pairing could not proceed at all — a transport or protocol failure, not a wrong code. */
    record Failed(String reason) implements PairingOutcome {
    }
}
