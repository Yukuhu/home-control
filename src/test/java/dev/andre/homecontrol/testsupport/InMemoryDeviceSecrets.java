package dev.andre.homecontrol.testsupport;

import dev.andre.homecontrol.core.DeviceSecrets;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** {@link DeviceSecrets} in memory, for adapter tests that do not need secrets.json. */
public final class InMemoryDeviceSecrets implements DeviceSecrets {

    private final Map<String, String> values = new ConcurrentHashMap<>();

    @Override
    public Optional<String> deviceSecret(String name) {
        return Optional.ofNullable(values.get(require(name)));
    }

    @Override
    public void putDeviceSecret(String name, String value) {
        values.put(require(name), value);
    }

    @Override
    public void removeDeviceSecrets(Collection<String> names) {
        names.forEach(name -> values.remove(require(name)));
    }

    /** Everything stored, by name. */
    public Map<String, String> all() {
        return Map.copyOf(values);
    }

    private static String require(String name) {
        if (name == null || !name.startsWith(PREFIX)) {
            throw new IllegalArgumentException("Not a device secret name");
        }
        return name;
    }
}
