package dev.andre.shield.device;

import java.util.List;
import java.util.Optional;

public interface DeviceRegistry {

    List<Device> findAll();

    Optional<Device> findById(String id);

    /** The single device the v1 UI controls. */
    Optional<Device> first();

    void save(Device device);

    void delete(String id);
}
