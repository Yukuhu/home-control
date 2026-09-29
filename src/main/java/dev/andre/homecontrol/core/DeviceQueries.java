package dev.andre.homecontrol.core;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** What is known about the registered devices: the registry, their composed live states and what their adapters offer. */
public interface DeviceQueries {

    /** Every registered device, ordered by name. */
    List<Device> devices();

    Optional<Device> device(String id);

    /** The most recently paired device: what {@code /} shows when no device is selected. */
    Optional<Device> defaultDevice();

    /** The composed state of the device's adapters; an unknown or handle-less device reads as DISCONNECTED. */
    DeviceState state(String id);

    /** The state of every registered device, by id, in {@link #devices()} order. */
    Map<String, DeviceState> states();

    /** What the device's switched-on adapters offer together; empty for an unknown id. */
    Set<Capability> capabilities(String id);

    /** The best foreground-app reporting among the device's adapters; {@code NONE} for an unknown id. */
    ForegroundAppReporting foregroundAppReporting(String id);

    /** Grouping as seen by the first of the device's handles that knows it; empty otherwise. */
    Optional<SpeakerTopology> speakerTopology(String id);

    /** Inputs from the first of the device's handles that lists any; empty when none does. */
    List<TvInput> inputs(String id);
}
