package dev.andre.homecontrol.core;

/** One input a TV reported, e.g. {@code HDMI_1} / "HDMI 1". */
public record TvInput(String id, String label) {
}
