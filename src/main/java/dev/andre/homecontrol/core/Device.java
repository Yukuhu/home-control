package dev.andre.homecontrol.core;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A registered device. {@code adapters} maps adapter id → that adapter's own settings
 * (strings only, so the registry file stays readable and adapter-agnostic). Insertion
 * order is significant: the first adapter is the primary one for state display.
 */
public record Device(String id, String name, DeviceKind kind, String host,
                     Map<String, Map<String, String>> adapters, Instant lastSeen) {

    public Device {
        Map<String, Map<String, String>> ordered = new LinkedHashMap<>();
        if (adapters != null) {
            adapters.forEach((adapterId, settings) ->
                    ordered.put(adapterId, settings == null ? Map.of() : Map.copyOf(settings)));
        }
        adapters = Collections.unmodifiableMap(ordered);
    }

    public Map<String, String> adapterSettings(String adapterId) {
        return adapters.getOrDefault(adapterId, Map.of());
    }

    public boolean hasAdapter(String adapterId) {
        return adapters.containsKey(adapterId);
    }

    public Device withAdapter(String adapterId, Map<String, String> settings) {
        Map<String, Map<String, String>> extended = new LinkedHashMap<>(adapters);
        extended.put(adapterId, settings);
        return new Device(id, name, kind, host, extended, lastSeen);
    }
}
