package dev.andre.homecontrol.core;

/** What {@link CodePairing#submit} reports: the adapter translates its protocol's result into this. */
public sealed interface CodePairingOutcome {

    /** The device accepted the secret; a credential now exists for it. */
    record Paired() implements CodePairingOutcome {
    }

    /** The code did not match. The device will show a new one, so restart the flow. */
    record WrongCode() implements CodePairingOutcome {
    }

    /** Pairing could not proceed at all — a transport or protocol failure, not a wrong code. */
    record Failed(String reason) implements CodePairingOutcome {
    }
}
