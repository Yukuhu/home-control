package dev.andre.homecontrol.core;

/** Published on every state transition of any device; the SSE layer forwards it as-is. */
public record DeviceStateChangedEvent(String deviceId, DeviceState state) {
}
