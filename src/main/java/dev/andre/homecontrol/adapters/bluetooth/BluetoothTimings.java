package dev.andre.homecontrol.adapters.bluetooth;

import java.time.Duration;

/**
 * How often a {@link BluetoothSpeakerSession} polls BlueZ, idle and while playing. Production builds them from
 * {@link BluetoothProperties}; tests pass milliseconds.
 */
record BluetoothTimings(Duration pollInterval, Duration playingPollInterval) {

    static BluetoothTimings from(BluetoothProperties properties) {
        return new BluetoothTimings(properties.pollInterval(),
                properties.playingPollInterval());
    }
}
