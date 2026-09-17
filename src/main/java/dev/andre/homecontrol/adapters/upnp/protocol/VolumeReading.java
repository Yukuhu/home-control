package dev.andre.homecontrol.adapters.upnp.protocol;

/** A RenderingControl reading, volume already converted to percent. */
public record VolumeReading(int percent, boolean muted) {
}
