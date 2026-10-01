package dev.andre.homecontrol.core;

import java.util.List;
import java.util.Optional;

public interface DeviceRegistry {

    List<Device> findAll();

    Optional<Device> findById(String id);

    /** The most recently paired device, by {@link Device#lastSeen}: the dashboard's default selection. */
    Optional<Device> first();

    void save(Device device);

    void delete(String id);
}
