package dev.andre.shield.protocol;

import java.security.cert.X509Certificate;

public sealed interface PairingResult {

    /** The device accepted the secret; this certificate is now a credential. */
    record Paired(X509Certificate serverCertificate) implements PairingResult {
    }

    /** The code did not match. The device will show a new one, so restart the flow. */
    record WrongCode() implements PairingResult {
    }

    record Failed(String reason) implements PairingResult {
    }
}
