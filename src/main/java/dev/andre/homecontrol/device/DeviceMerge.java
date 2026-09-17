package dev.andre.homecontrol.device;

import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

/**
 * "A device that must be paired was paired, and we may already know it under another adapter"
 * (spec §5.1): the same host is the same device. The existing device keeps its id, name, kind and
 * adapter order (the first adapter stays primary); the adapter's settings are overlaid so values
 * the pairing does not send (a hand-entered MAC) survive a re-pair.
 */
final class DeviceMerge {

    private DeviceMerge() {
    }

    static Device attach(List<Device> registered, String host, String name, DeviceKind kind, String adapterId,
                         Map<String, String> settings, Instant now, BiPredicate<String, String> sameHost) {
        for (Device existing : registered) {
            if (sameHost.test(existing.host(), host)) {
                Map<String, String> merged = new LinkedHashMap<>(existing.adapterSettings(adapterId));
                merged.putAll(settings);
                Device extended = existing.withAdapter(adapterId, merged);
                return new Device(extended.id(), extended.name(), extended.kind(), extended.host(),
                        extended.adapters(), now);
            }
        }
        return new Device(DeviceManager.uniqueId(registered, adapterId, host), name, kind, host,
                Map.of(adapterId, Map.copyOf(settings)), now);
    }
}
