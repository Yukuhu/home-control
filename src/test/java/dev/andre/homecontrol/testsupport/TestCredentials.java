package dev.andre.homecontrol.testsupport;

import dev.andre.homecontrol.adapters.androidtv.protocol.ClientCertificate;

/**
 * One Android TV client certificate for a whole test JVM. Generating an RSA-2048 key takes long enough to show in
 * a suite's time; tests that only need some credential stored under an alias share this one.
 */
public final class TestCredentials {

    private TestCredentials() {
    }

    public static ClientCertificate clientCertificate() {
        return Holder.CERTIFICATE;
    }

    private static final class Holder {
        private static final ClientCertificate CERTIFICATE = ClientCertificate.generate("shield-remote");
    }
}
