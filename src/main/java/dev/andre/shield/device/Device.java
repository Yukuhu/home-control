package dev.andre.shield.device;

import java.time.Instant;

/**
 * A paired device. {@code certificateFingerprint} is the device certificate recorded
 * during pairing; the command channel refuses anything else (spec §6).
 */
public record Device(String id, String name, String host, int port,
                     String certificateFingerprint, Instant lastSeen) {

    public String certificateAlias() {
        return id;
    }
}
