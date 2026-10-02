package dev.andre.homecontrol.adapters.support;

import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceSecrets;
import dev.andre.homecontrol.core.Hosts;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * A TV's pairing key, kept as a device secret named by a reference in the adapter's settings. The reference is not
 * secret: it lives in devices.json, so a merge or split carries it along with the entry. Before 2B the key itself
 * sat in the settings; {@link #migrate} moves it. webOS and Tizen share this.
 */
public final class PairingKeys {

    public static final String KEY_REF = "keyRef";

    private final String adapterId;
    private final UnaryOperator<String> secretName;
    private final DeviceSecrets secrets;

    /** {@code secretName} turns a reference into the device secret's name. */
    public PairingKeys(String adapterId, UnaryOperator<String> secretName, DeviceSecrets secrets) {
        this.adapterId = adapterId;
        this.secretName = secretName;
        this.secrets = secrets;
    }

    /** The reference in the device's entry for this adapter, or null. */
    public String referenceOf(Device device) {
        String keyRef = device.adapterSettings(adapterId).get(KEY_REF);
        return keyRef == null || keyRef.isBlank() ? null : keyRef;
    }

    /** The key the device's entry names, or null when none is stored. */
    public String keyOf(Device device) {
        String keyRef = referenceOf(device);
        return keyRef == null ? null : secrets.deviceSecret(secretName.apply(keyRef)).orElse(null);
    }

    public void store(String keyRef, String key) {
        secrets.putDeviceSecret(secretName.apply(keyRef), key);
    }

    /**
     * A key just paired with the TV at {@code host}: stored under the reference its entry already has, so a re-pair
     * leaves no secret behind, or under a new one. Returns the reference for the entry's settings.
     */
    public String storePaired(List<Device> registered, String host, String key) {
        String keyRef = registered.stream()
                .filter(device -> Hosts.same(device.host(), host))
                .map(this::referenceOf)
                .filter(Objects::nonNull)
                .findFirst()
                .orElseGet(DeviceSecrets::newReference);
        store(keyRef, key);
        return keyRef;
    }

    /**
     * Moves a key still stored under {@code legacyKey} in the entry into a device secret under a new reference, or
     * under the entry's own reference if a hand edit left one next to the legacy key. Idempotent: an entry without the
     * legacy key comes back unchanged. The secret is stored before the caller saves the entry, so a crash in between
     * leaves that secret unused; the next start moves the key again under a new reference, and the pairing survives.
     */
    public Device migrate(Device device, String legacyKey) {
        Map<String, String> settings = device.adapterSettings(adapterId);
        String legacy = settings.get(legacyKey);
        if (legacy == null) {
            return device;
        }
        Map<String, String> next = new LinkedHashMap<>(settings);
        next.remove(legacyKey);
        if (!legacy.isBlank()) {
            String keyRef = referenceOf(device);
            if (keyRef == null) {
                keyRef = DeviceSecrets.newReference();
                next.put(KEY_REF, keyRef);
            }
            store(keyRef, legacy);
        }
        return device.withAdapter(adapterId, next);
    }

    /**
     * Removes the key of a device that is being forgotten, unless another of the {@code registered} devices still
     * names it: a re-pair can attach the adapter to another entry at the same address, which then shares the
     * reference.
     */
    public void forget(Device device, List<Device> registered) {
        String keyRef = referenceOf(device);
        if (keyRef == null) {
            return;
        }
        boolean stillUsed = registered.stream()
                .anyMatch(other -> !other.id().equals(device.id()) && keyRef.equals(referenceOf(other)));
        if (!stillUsed) {
            secrets.removeDeviceSecrets(List.of(secretName.apply(keyRef)));
        }
    }
}
