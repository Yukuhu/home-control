package dev.andre.homecontrol.adapters.bluetooth.bluez;

/** What went wrong talking to BlueZ, coarse enough to word a fix on the setup page. */
public enum BluezFailure {
    /** No D-Bus system socket at the configured path. */
    NO_DBUS_SOCKET,
    /** The host's D-Bus refused this container. */
    ACCESS_DENIED,
    /** BlueZ is not running on the host. */
    BLUEZ_NOT_RUNNING,
    /** No Bluetooth adapter found on the host. */
    NO_ADAPTER,
    /** The Bluetooth adapter is off or blocked. */
    ADAPTER_OFF,
    /** The host has no Bluetooth audio service (PipeWire/PulseAudio) for this speaker. */
    NO_AUDIO_PROFILE,
    /** BlueZ does not know this adapter or device. */
    NOT_FOUND,
    /** The speaker refused pairing. */
    PAIRING_REJECTED,
    /** The speaker did not answer. */
    UNREACHABLE,
    /** The Bluetooth adapter is busy with another operation. */
    BUSY,
    /** The requested state already holds; nothing to do. */
    ALREADY_DONE,
    /** BlueZ did not answer in time. */
    TIMEOUT,
    /** Anything else. */
    FAILED
}
