package dev.andre.homecontrol.device;

import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceAdapter;
import dev.andre.homecontrol.core.DeviceNotFoundException;
import dev.andre.homecontrol.core.DeviceRegistry;
import dev.andre.homecontrol.core.DeviceSettings;
import dev.andre.homecontrol.core.LearnedSettings;
import dev.andre.homecontrol.core.MacAddress;
import dev.andre.homecontrol.core.WakeOnLanAdapter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * The adapter settings a handle learns while connected and the Wake-on-LAN MAC the user types in. Every rewrite of a
 * device entry runs under the shared {@link RegistryLock}.
 */
final class AdapterSettingsStore implements DeviceSettings {

    private static final String NO_DEVICE_PREFIX = "No device with id ";

    private final DeviceRegistry registry;
    private final Map<String, DeviceAdapter> adapters;
    private final RegistryLock lock;

    AdapterSettingsStore(DeviceRegistry registry, Map<String, DeviceAdapter> adapters, RegistryLock lock) {
        this.registry = registry;
        this.adapters = adapters;
        this.lock = lock;
    }

    /**
     * What a handle learned while connected ({@link LearnedSettings}): merged into the adapter's
     * settings under the registry lock, without reconnecting. A no-op once the device was forgotten or
     * lost the adapter (a late write must never resurrect it), or when nothing changes. A MAC
     * address the user typed in is never replaced by a learned one.
     */
    void updateAdapterSettings(String id, String adapterId, Map<String, String> updates) {
        updateAdapterSettings(id, adapterId, updates, () -> true);
    }

    /**
     * Like {@link #updateAdapterSettings(String, String, Map)}, for a handle's connection: written only if
     * {@code current} still holds under the registry lock. A reconnect begins under that lock, so a superseded
     * connection can never overwrite what its successor saved.
     */
    void updateAdapterSettings(String id, String adapterId, Map<String, String> updates, BooleanSupplier current) {
        synchronized (lock) {
            if (!current.getAsBoolean()) {
                return;
            }
            Optional<Device> registered = registry.findById(id).filter(device -> device.hasAdapter(adapterId));
            if (registered.isEmpty()) {
                return;
            }
            Device device = registered.get();
            Map<String, String> settings = new LinkedHashMap<>(device.adapterSettings(adapterId));
            Map<String, String> accepted = new LinkedHashMap<>(updates);
            if ("true".equals(settings.get(WakeOnLanAdapter.MAC_ADDRESS_MANUAL))) {
                accepted.remove(WakeOnLanAdapter.MAC_ADDRESS);
                accepted.remove(WakeOnLanAdapter.MAC_ADDRESS_MANUAL);
            }
            settings.putAll(accepted);
            if (!settings.equals(device.adapterSettings(adapterId))) {
                registry.save(device.withAdapter(adapterId, settings));
            }
        }
    }

    /** True when one of the device's adapters can switch it on with Wake-on-LAN. */
    @Override
    public boolean wakesOnLan(String id) {
        return registry.findById(id)
                .map(device -> device.adapters().keySet().stream()
                        .anyMatch(adapterId -> adapters.get(adapterId) instanceof WakeOnLanAdapter))
                .orElse(false);
    }

    @Override
    public Optional<String> wakeOnLanMac(String id) {
        return registry.findById(id).flatMap(device -> device.adapters().keySet().stream()
                .filter(adapterId -> adapters.get(adapterId) instanceof WakeOnLanAdapter)
                .map(adapterId -> device.adapterSettings(adapterId).get(WakeOnLanAdapter.MAC_ADDRESS))
                .filter(mac -> mac != null && !mac.isBlank())
                .findFirst());
    }

    /**
     * Stores a hand-entered MAC on every Wake-on-LAN adapter of the device and stops adapters from
     * replacing it; blank clears it so they learn it again. No reconnect: handles read the MAC from
     * the registry when they wake the device. An invalid MAC throws {@link IllegalArgumentException}
     * before anything is written.
     */
    @Override
    public void setWakeOnLanMac(String id, String mac) {
        boolean clear = mac == null || mac.isBlank();
        String normalized = clear ? null : MacAddress.normalize(mac);
        synchronized (lock) {
            Device device = registry.findById(id)
                    .orElseThrow(() -> new DeviceNotFoundException(NO_DEVICE_PREFIX + id));
            Device updated = device;
            for (String adapterId : device.adapters().keySet()) {
                if (adapters.get(adapterId) instanceof WakeOnLanAdapter) {
                    Map<String, String> settings = new LinkedHashMap<>(updated.adapterSettings(adapterId));
                    if (clear) {
                        settings.remove(WakeOnLanAdapter.MAC_ADDRESS);
                        settings.remove(WakeOnLanAdapter.MAC_ADDRESS_MANUAL);
                    } else {
                        settings.put(WakeOnLanAdapter.MAC_ADDRESS, normalized);
                        settings.put(WakeOnLanAdapter.MAC_ADDRESS_MANUAL, "true");
                    }
                    updated = updated.withAdapter(adapterId, settings);
                }
            }
            registry.save(updated);
        }
    }
}
