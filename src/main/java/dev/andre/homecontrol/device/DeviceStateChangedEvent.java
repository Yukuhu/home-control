package dev.andre.homecontrol.device;

/** Published whenever a session's state changes, so the SSE layer can forward it. */
public record DeviceStateChangedEvent(DeviceState state) {
}
