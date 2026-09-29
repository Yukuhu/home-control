package dev.andre.homecontrol.device;

import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DiscoveredDevice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;

/** Which registered device a paired or discovered adapter belongs to: pure functions over a snapshot, no registry, adapters or DNS. */
final class DeviceMatching {

    private DeviceMatching() {
    }

    /**
     * "A device that must be paired was paired, and we may already know it under another adapter"
     * (spec §5.1): the same host is the same device. The existing device keeps its id, name, kind and
     * adapter order (the first adapter stays primary); the adapter's settings are overlaid so values
     * the pairing does not send (a hand-entered MAC) survive a re-pair.
     */
    // DeviceManager.attach's five inputs plus the three it is made pure over (registry, clock, host match).
    @SuppressWarnings("java:S107")
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
        return new Device(uniqueId(registered, adapterId, host), name, kind, host,
                Map.of(adapterId, Map.copyOf(settings)), now);
    }

    /** Same address first; otherwise the single device with the same name. Never one that already has the adapter. */
    static Optional<Device> bestMatch(List<Device> registered, DiscoveredDevice found) {
        List<Device> candidates = registered.stream().filter(device -> !device.hasAdapter(found.adapterId())).toList();
        Optional<Device> byHost = candidates.stream()
                .filter(device -> device.host().equalsIgnoreCase(found.host()))
                .findFirst();
        if (byHost.isPresent()) {
            return byHost;
        }
        List<Device> byName = candidates.stream().filter(device -> sameName(device.name(), found.name())).toList();
        return byName.size() == 1 ? Optional.of(byName.getFirst()) : Optional.empty();
    }

    /**
     * For a newly adopted device: the receiver at its address, or else the single receiver with its name — and only
     * when no other registered device shares that name or that receiver's address, where it would belong instead.
     */
    static Optional<DiscoveredDevice> absorbable(Device device, List<DiscoveredDevice> receivers, List<Device> others) {
        Optional<DiscoveredDevice> byHost = receivers.stream()
                .filter(found -> found.host().equalsIgnoreCase(device.host()))
                .findFirst();
        if (byHost.isPresent()) {
            return byHost;
        }
        List<DiscoveredDevice> byName = receivers.stream()
                .filter(found -> sameName(found.name(), device.name()))
                .toList();
        if (byName.size() != 1) {
            return Optional.empty();
        }
        DiscoveredDevice only = byName.getFirst();
        boolean belongsElsewhere = others.stream().anyMatch(other ->
                sameName(other.name(), device.name()) || other.host().equalsIgnoreCase(only.host()));
        return belongsElsewhere ? Optional.empty() : Optional.of(only);
    }

    static boolean sameName(String a, String b) {
        return a != null && b != null && a.strip().equalsIgnoreCase(b.strip());
    }

    /** The re-paired adapters replace their old entries; every other adapter stays, in its place. */
    static Device keepOtherAdapters(Device existing, Device adopted) {
        Map<String, Map<String, String>> merged = new LinkedHashMap<>(existing.adapters());
        merged.putAll(adopted.adapters());
        return new Device(adopted.id(), adopted.name(), existing.kind(), adopted.host(), merged, adopted.lastSeen());
    }

    static String uniqueId(List<Device> registered, String adapterId, String host) {
        String base = adapterId + "-" + host.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceFirst("^-", "").replaceFirst("-$", "");
        Set<String> taken = registered.stream().map(Device::id).collect(Collectors.toSet());
        String id = base;
        for (int n = 2; taken.contains(id); n++) {
            id = base + "-" + n;
        }
        return id;
    }
}
