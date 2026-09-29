package dev.andre.homecontrol.core;

import java.util.Collection;
import java.util.Optional;

/**
 * Credentials a device adapter keeps for a device: a TV's client key, the Android TV keystore's password. They are
 * encrypted at rest with the account credentials but never need the household login. Every name starts with
 * {@link #PREFIX}. Nothing here is ever logged or shown.
 */
public interface DeviceSecrets {

    String PREFIX = "device.";

    Optional<String> deviceSecret(String name);

    void putDeviceSecret(String name, String value);

    void removeDeviceSecrets(Collection<String> names);

    /**
     * A new reference naming one device's secrets: 16 random hex characters, not itself secret. It lives in the
     * adapter's settings, so a merge or split carries it along.
     */
    static String newReference() {
        return SecretReferences.next();
    }
}
