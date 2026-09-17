package dev.andre.homecontrol.core;

import java.util.Optional;

/** Implemented by handles whose device can be grouped with others; empty while unknown or disconnected. */
public interface GroupListing {
    Optional<SpeakerTopology> speakerTopology();
}
