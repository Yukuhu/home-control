package dev.andre.homecontrol.adapters.upnp.protocol;

import org.w3c.dom.Element;

/** RenderingControl volumes are device units; the rest of the system speaks percent (B). */
public final class VolumeRange {

    public static final int DEFAULT_MAXIMUM = 100;

    private VolumeRange() {
    }

    public static int maximum(byte[] scpd) {
        try {
            for (Element variable : UpnpXml.descendants(UpnpXml.parse(scpd), "stateVariable")) {
                if (UpnpXml.childText(variable, "name").filter("Volume"::equals).isPresent()) {
                    int max = UpnpXml.firstDescendant(variable, "allowedValueRange")
                            .flatMap(range -> UpnpXml.childText(range, "maximum"))
                            .map(Integer::parseInt)
                            .orElse(DEFAULT_MAXIMUM);
                    return max > 0 ? max : DEFAULT_MAXIMUM;
                }
            }
        } catch (IllegalArgumentException e) {
            // unreadable SCPD or a non-numeric maximum
        }
        return DEFAULT_MAXIMUM;
    }

    public static int toDevice(int percent, int max) {
        int range = max > 0 ? max : DEFAULT_MAXIMUM;
        return (int) Math.round(Math.clamp(percent, 0, 100) * range / 100.0);
    }

    public static int toPercent(int value, int max) {
        int range = max > 0 ? max : DEFAULT_MAXIMUM;
        return (int) Math.clamp(Math.round(value * 100.0 / range), 0, 100);
    }
}
