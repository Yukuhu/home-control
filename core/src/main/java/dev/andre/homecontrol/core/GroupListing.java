package dev.andre.homecontrol.core;

import java.util.Optional;

/**
 * A connection whose device can be grouped with others; empty while unknown or disconnected. Found with
 * {@link DeviceHandle#feature}; an adapter whose connections offer it declares {@link Capability#GROUPING}.
 */
public interface GroupListing {
    Optional<SpeakerTopology> speakerTopology();
}
